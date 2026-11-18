//! Native single-file terminal uploads. No automatic retry after reservation or transfer.
use crate::{
    control::{Control, RpcError},
    transport::{self, Endpoint, Ws},
};
use anyhow::{Context, bail, ensure};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use futures_util::{FutureExt, SinkExt, StreamExt};
use serde_json::{Value, json};
use std::{collections::BTreeSet, io::Read, path::Path, time::Duration};
use tokio::sync::oneshot;
use tokio_tungstenite::tungstenite::{
    Message,
    protocol::{CloseFrame, frame::coding::CloseCode},
};

pub const BYTES: &str = "rex.data.bytes.v1";
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Phase {
    Preparing,
    Sending,
    Finishing,
    Done,
    Unsupported,
    Failed,
    Uncertain,
    Canceled,
}
impl Phase {
    pub fn name(self) -> &'static str {
        match self {
            Self::Preparing => "preparing",
            Self::Sending => "sending",
            Self::Finishing => "finishing",
            Self::Done => "done",
            Self::Unsupported => "unsupported",
            Self::Failed => "failed",
            Self::Uncertain => "uncertain",
            Self::Canceled => "canceled",
        }
    }
}
pub struct Request<'a> {
    pub endpoint: Endpoint,
    pub session: &'a str,
    pub block: &'a str,
    pub files: &'a [UploadFile<'a>],
    /// Required for more than one file. Rex types a batch's paths together once its last file arrives.
    pub batch: Option<&'a str>,
}
pub struct UploadFile<'a> {
    pub path: &'a Path,
    pub name: &'a str,
    pub reference: &'a str,
}
pub const MAX_FILE_BYTES: u64 = 512 * 1024 * 1024;
pub const MAX_FILES: usize = 32;

/// Progress reports (phase, bytes confirmed across all files, total bytes, index of the file being sent).
/// All callbacks are metadata only; file content/tickets/paths are never logged.
pub async fn upload(
    request: Request<'_>,
    mut cancel: oneshot::Receiver<()>,
    progress: impl Fn(Phase, u64, u64, usize),
) -> (Phase, String) {
    let mut phase = Phase::Preparing;
    let mut ws: Option<Ws> = None;
    let operation = async {
        let count = request.files.len();
        ensure!((1..=MAX_FILES).contains(&count), "Choose from 1 to {MAX_FILES} files");
        ensure!(count == 1 || request.batch.is_some(), "Several files need a batch");
        let mut opened = Vec::with_capacity(count);
        for spec in request.files {
            let file = std::fs::File::open(spec.path).context("Could not read the staged file")?;
            let size = file.metadata()?.len();
            ensure!(size > 0, "{} is empty", spec.name);
            ensure!(size <= MAX_FILE_BYTES, "Choose files smaller than 512 MiB");
            opened.push((file, size));
        }
        let total: u64 = opened.iter().map(|(_, size)| size).sum();
        let mut control = Control::connect_upload(request.endpoint.clone()).await?;
        if control.welcome()["api_revision"].as_u64().unwrap_or(0) < 11 {
            phase = Phase::Unsupported;
            return Ok::<(), anyhow::Error>(());
        }
        control.attach(request.session).await?;
        let mut base = 0;
        for (index, (spec, (mut file, size))) in request.files.iter().zip(opened).enumerate() {
            let last = index + 1 == count;
            let report = |phase: Phase, sent: u64, _: u64| progress(phase, base + sent, total, index);
            let mut args = json!({"ref":spec.reference,"name":spec.name,"size":size});
            if let Some(batch) = request.batch {
                args["batch"] = json!(batch);
                args["index"] = json!(index);
                args["count"] = json!(count);
            }
            let result = control
                .call("com.superlogical.terminal.upload_begin", json!({"session_id":request.session,"block_id":request.block,"args":args}))
                .await;
            let ticket = match result {
                Ok(value) => value,
                Err(error) => {
                    if index == 0
                        && error
                            .downcast_ref::<RpcError>()
                            .is_some_and(|e| e.code == "method_not_found" || e.code == "revision_too_low")
                    {
                        phase = Phase::Unsupported;
                        return Ok(());
                    }
                    if let Some(rpc) = error.downcast_ref::<RpcError>() {
                        match rpc.code.as_str() {
                            "too_large" => bail!("{} exceeds the Rex server's upload limit", spec.name),
                            "conflict" => {
                                bail!("Rex cannot accept this upload right now. Try again shortly")
                            }
                            "not_found" | "session_not_found" => {
                                bail!("The original pane is no longer available")
                            }
                            _ => (),
                        }
                    }
                    return Err(error);
                }
            };
            let bytes =
                URL_SAFE_NO_PAD.decode(ticket["ticket"].as_str().context("Upload ticket missing")?)?;
            ensure!(bytes.len() == 16, "Invalid upload ticket");
            ws = Some(request.endpoint.open("/api/data", BYTES).await?);
            let stream = ws.as_mut().unwrap();
            let mut hello = vec![1];
            hello.extend(bytes);
            stream.send(Message::Binary(hello.into())).await?;
            let welcome = tokio::time::timeout(request.endpoint.timeout, transport::receive(stream))
                .await
                .context("Upload handshake timed out")??;
            ensure!(
                matches!(welcome,Message::Binary(ref b) if b.len()==32 && b[0]==0x81 && b[17..20]==[0,0,0] && b[24..32].iter().any(|&n|n!=0)),
                "Upload handshake rejected"
            );
            phase = Phase::Sending;
            report(phase, 0, size);
            let mut sent = 0;
            let mut acknowledged = 0;
            let mut checkpoints = BTreeSet::new();
            let mut checkpoint = 0;
            // Pipeline checkpoints rather than paying an RTT for each chunk. A pong
            // confirms transport receipt of preceding frames, not file commit.
            const CHECKPOINT: u64 = 64 * 1024;
            const IN_FLIGHT: u64 = 4 * 1024 * 1024;
            let mut buffer = [0u8; 16384];
            while sent < size {
                let limit = ((size - sent) as usize).min(buffer.len());
                let read = file.read(&mut buffer[..limit])?;
                ensure!(read > 0, "The staged file changed during upload");
                tokio::time::timeout(
                    Duration::from_secs(30),
                    stream.send(Message::Binary(buffer[..read].to_vec().into())),
                )
                .await
                .context("Upload stalled")??;
                sent += read as u64;
                if sent - checkpoint >= CHECKPOINT || sent == size {
                    checkpoint = sent;
                    let mut ping = b"rex-upload".to_vec();
                    ping.extend(sent.to_le_bytes());
                    checkpoints.insert(sent);
                    tokio::time::timeout(
                        Duration::from_secs(30),
                        stream.send(Message::Ping(ping.into())),
                    )
                    .await
                    .context("Upload checkpoint stalled")??;
                }
                // Poll pending replies while writing, but never cancel a partially
                // completed Sink::send. WebSocket next() retains its buffered state.
                while let Some(message) = stream.next().now_or_never() {
                    let message = message.context("Upload connection lost")??;
                    if acknowledge(message, &mut checkpoints, &mut acknowledged)? {
                        report(phase, acknowledged, size);
                    }
                    stream.flush().await?;
                }
                while sent - acknowledged >= IN_FLIGHT {
                    receive_ack(stream, &mut checkpoints, &mut acknowledged, size, &report).await?;
                }
            }
            while acknowledged < size {
                receive_ack(stream, &mut checkpoints, &mut acknowledged, size, &report).await?;
            }
            ensure!(
                file.read(&mut [0u8; 1])? == 0,
                "The staged file changed during upload"
            );
            // Anything after attempting the last EOF may have typed the paths: never offer a blind retry.
            if last {
                phase = Phase::Finishing;
            }
            report(phase, sent, size);
            stream.send(Message::Binary(Vec::new().into())).await?;
            tokio::time::timeout(Duration::from_secs(30), async {
                loop {
                    match stream.next().await.context("Upload connection lost")?? {
                        Message::Close(Some(close)) if close.code == CloseCode::Normal => {
                            let _ = stream.flush().await;
                            return Ok::<(), anyhow::Error>(());
                        }
                        Message::Close(_) => bail!("The Rex server refused the upload"),
                        Message::Ping(_) | Message::Pong(_) => stream.flush().await?,
                        _ => bail!("Unexpected upload response"),
                    }
                }
            })
            .await
            .context("Upload confirmation timed out")??;
            ws = None;
            base += size;
        }
        phase = Phase::Done;
        progress(phase, total, total, count - 1);
        let _ = control.close().await;
        Ok(())
    };
    let outcome = tokio::select! {biased;result=operation=>Some(result),_= &mut cancel=>None};
    if let Some(stream) = ws.as_mut() {
        let _ = tokio::time::timeout(
            Duration::from_secs(2),
            stream.send(Message::Close(Some(CloseFrame {
                code: CloseCode::Normal,
                reason: "".into(),
            }))),
        )
        .await;
    }
    match outcome {
        Some(Ok(())) => (phase, String::new()),
        Some(Err(error)) if phase == Phase::Finishing => (
            Phase::Uncertain,
            format!(
                "The file paths may already have been typed. Check the original pane before trying again. {error}"
            ),
        ),
        Some(Err(error)) => (Phase::Failed, error.to_string()),
        None if phase == Phase::Finishing => (
            Phase::Uncertain,
            "Upload stopped while finishing. Check the original pane before trying again.".into(),
        ),
        None => (Phase::Canceled, String::new()),
    }
}

pub fn status(phase: Phase, sent: u64, total: u64, file: usize, error: &str) -> Value {
    json!({"phase":phase.name(),"sent":sent,"total":total,"file":file,"error":error})
}

fn acknowledge(
    message: Message,
    checkpoints: &mut BTreeSet<u64>,
    acknowledged: &mut u64,
) -> anyhow::Result<bool> {
    match message {
        Message::Pong(bytes) if bytes.len() == 18 && &bytes[..10] == b"rex-upload" => {
            let offset = u64::from_le_bytes(bytes[10..].try_into().unwrap());
            if checkpoints.contains(&offset) && offset > *acknowledged {
                *acknowledged = offset;
                checkpoints.retain(|value| *value > offset);
                return Ok(true);
            }
        }
        Message::Ping(_) | Message::Pong(_) => (),
        Message::Close(_) => bail!("Upload connection closed before all bytes were received"),
        _ => bail!("Unexpected upload response"),
    }
    Ok(false)
}

async fn receive_ack(
    stream: &mut Ws,
    checkpoints: &mut BTreeSet<u64>,
    acknowledged: &mut u64,
    total: u64,
    progress: &impl Fn(Phase, u64, u64),
) -> anyhow::Result<()> {
    tokio::time::timeout(Duration::from_secs(30), async {
        loop {
            let message = stream.next().await.context("Upload connection lost")??;
            let advanced = acknowledge(message, checkpoints, acknowledged)?;
            stream.flush().await?;
            if advanced {
                progress(Phase::Sending, *acknowledged, total);
                return Ok::<(), anyhow::Error>(());
            }
        }
    })
    .await
    .context("Upload reception timed out")?
}
