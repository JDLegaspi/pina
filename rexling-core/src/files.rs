//! Read-only access to files on the Rex host, resolved from a terminal block.
use crate::{
    control::{Control, PendingCall},
    transport::{Endpoint, Ws},
    upload::BYTES,
};
use anyhow::{Context, Result, bail, ensure};
use base64::{Engine, engine::general_purpose::URL_SAFE_NO_PAD};
use futures_util::{SinkExt, StreamExt, future::join_all};
use percent_encoding::percent_decode_str;
use serde_json::{Value, json};
use std::time::Duration;
use tokio_tungstenite::tungstenite::{
    Message,
    protocol::{CloseFrame, frame::coding::CloseCode},
};

pub const READ_CONCURRENCY: usize = 8;
const PIPELINE: usize = 16;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DirStatus {
    Ok,
    Missing,
    NotDir,
    Denied,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DirEntry {
    pub name: String,
    pub file: bool,
    pub symlink: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DirListing {
    pub path: String,
    pub home: String,
    pub status: DirStatus,
    pub entries: Vec<DirEntry>,
    pub truncated: bool,
}
impl DirListing {
    pub fn parse(value: &Value) -> Result<Self> {
        let status = match value["status"].as_str() {
            Some("ok") => DirStatus::Ok,
            Some("missing") => DirStatus::Missing,
            Some("not_dir") => DirStatus::NotDir,
            Some("denied") => DirStatus::Denied,
            other => bail!("unknown list_dir status {other:?}"),
        };
        let entries = value["entries"]
            .as_array()
            .map(|entries| {
                entries
                    .iter()
                    .filter_map(|entry| {
                        Some(DirEntry {
                            name: entry["name"].as_str()?.to_owned(),
                            file: entry["file"].as_bool().unwrap_or(false),
                            symlink: entry["symlink"].as_bool().unwrap_or(false),
                        })
                    })
                    .collect()
            })
            .unwrap_or_default();
        Ok(Self {
            path: value["path"]
                .as_str()
                .context("list_dir path missing")?
                .into(),
            home: value["home"].as_str().unwrap_or("").into(),
            status,
            entries,
            truncated: value["truncated"].as_bool().unwrap_or(false),
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Location {
    pub cwd: Option<String>,
    pub home: Option<String>,
}

/// One control connection attached to a terminal's session. The outer `Result`
/// of batch methods fails only when the connection itself fails.
pub struct Files {
    control: Control,
    endpoint: Endpoint,
    session: String,
    block: String,
}
impl Files {
    pub async fn connect(endpoint: Endpoint, session: &str, block: &str) -> Result<Self> {
        let mut control = Control::connect_files(endpoint.clone()).await?;
        let revision = control.welcome()["api_revision"].as_u64().unwrap_or(0);
        ensure!(
            revision >= 11,
            "Rex server is too old to read files (API revision {revision})"
        );
        control.attach(session).await?;
        Ok(Self {
            control,
            endpoint,
            session: session.into(),
            block: block.into(),
        })
    }

    async fn calls(&mut self, requests: Vec<(&str, Value)>) -> Result<Vec<Result<Value>>> {
        let mut results = Vec::with_capacity(requests.len());
        for window in requests.chunks(PIPELINE) {
            let mut pending = Vec::with_capacity(window.len());
            for (method, args) in window {
                let payload = json!({"session_id":self.session,"block_id":self.block,"args":args});
                pending.push(
                    self.control
                        .start_call(&format!("com.superlogical.terminal.{method}"), payload)
                        .await?,
                );
            }
            let replies = join_all(pending.into_iter().map(PendingCall::receive));
            tokio::pin!(replies);
            loop {
                tokio::select! {
                    biased;
                    replies = &mut replies => { results.extend(replies); break; }
                    message = self.control.next_message() => { message?; }
                }
            }
        }
        Ok(results)
    }

    pub async fn list_dirs(
        &mut self,
        paths: &[String],
        files: bool,
    ) -> Result<Vec<Result<DirListing>>> {
        let requests = paths
            .iter()
            .map(|path| ("list_dir", json!({"path":path,"files":files})))
            .collect();
        Ok(self
            .calls(requests)
            .await?
            .into_iter()
            .map(|reply| DirListing::parse(&reply?))
            .collect())
    }

    pub async fn list_dir(&mut self, path: &str, files: bool) -> Result<DirListing> {
        self.list_dirs(&[path.into()], files).await?.remove(0)
    }

    /// Reads at most `max_bytes` of each file, `READ_CONCURRENCY` data connections at a time.
    pub async fn read_files(
        &mut self,
        paths: &[String],
        max_bytes: usize,
    ) -> Result<Vec<Result<Vec<u8>>>> {
        let mut contents = Vec::with_capacity(paths.len());
        for window in paths.chunks(READ_CONCURRENCY) {
            let mut requests = Vec::with_capacity(window.len());
            for path in window {
                requests.push(("download_begin", json!({"ref":download_ref()?,"path":path})));
            }
            let tickets = self.calls(requests).await?;
            let endpoint = &self.endpoint;
            contents.extend(
                join_all(
                    tickets
                        .into_iter()
                        .map(|ticket| async move { download(endpoint, ticket?, max_bytes).await }),
                )
                .await,
            );
        }
        Ok(contents)
    }

    pub async fn read_file(&mut self, path: &str, max_bytes: usize) -> Result<Vec<u8>> {
        self.read_files(&[path.into()], max_bytes).await?.remove(0)
    }

    /// The foreground process's working directory, falling back to the last one a program reported.
    pub async fn location(&mut self) -> Result<Location> {
        let mut replies = self
            .calls(vec![("process", json!({})), ("pwd", json!({}))])
            .await?
            .into_iter();
        let process = replies.next().unwrap().unwrap_or_default();
        let pwd = replies.next().unwrap().unwrap_or_default();
        let cwd = process["foreground"]["cwd"]
            .as_str()
            .map(str::to_owned)
            .or_else(|| pwd["pwd"].as_str().map(reported_dir));
        let home = match process["home"].as_str().filter(|home| !home.is_empty()) {
            Some(home) => Some(home.to_owned()),
            None => Some(self.list_dir("~", false).await?.home).filter(|home| !home.is_empty()),
        };
        Ok(Location { cwd, home })
    }

    pub async fn close(mut self) {
        let _ = self.control.close().await;
    }
}

fn download_ref() -> Result<String> {
    let mut bytes = [0u8; 16];
    getrandom::fill(&mut bytes).map_err(|_| anyhow::anyhow!("Could not create download ID"))?;
    Ok(format!("download:{}", uuid_v4(bytes)))
}

fn uuid_v4(mut bytes: [u8; 16]) -> String {
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    let hex: String = bytes.iter().map(|b| format!("{b:02x}")).collect();
    format!(
        "{}-{}-{}-{}-{}",
        &hex[..8],
        &hex[8..12],
        &hex[12..16],
        &hex[16..20],
        &hex[20..]
    )
}

fn reported_dir(pwd: &str) -> String {
    match url::Url::parse(pwd) {
        Ok(url) if url.scheme() == "file" => percent_decode_str(url.path())
            .decode_utf8_lossy()
            .into_owned(),
        _ => pwd.to_owned(),
    }
}

async fn download(endpoint: &Endpoint, begun: Value, max_bytes: usize) -> Result<Vec<u8>> {
    let ticket = URL_SAFE_NO_PAD.decode(
        begun["ticket"]
            .as_str()
            .context("download ticket missing")?,
    )?;
    ensure!(ticket.len() == 16, "invalid download ticket");
    let mut ws = endpoint.open("/api/data", BYTES).await?;
    let result = tokio::time::timeout(endpoint.timeout, receive(&mut ws, ticket, max_bytes))
        .await
        .context("download timed out")
        .and_then(|result| result);
    // A normal close ends the stream; anything else leaves the server holding it for a resume.
    let _ = tokio::time::timeout(
        Duration::from_secs(2),
        ws.send(Message::Close(Some(CloseFrame {
            code: CloseCode::Normal,
            reason: "".into(),
        }))),
    )
    .await;
    result
}

async fn receive(ws: &mut Ws, ticket: Vec<u8>, max_bytes: usize) -> Result<Vec<u8>> {
    let mut hello = vec![1];
    hello.extend(ticket);
    ws.send(Message::Binary(hello.into())).await?;
    let mut welcomed = false;
    let mut data = Vec::new();
    loop {
        match ws.next().await.context("download connection dropped")?? {
            Message::Binary(bytes) if !welcomed => {
                if bytes.len() == 2 && bytes[0] == 0x82 {
                    bail!("download rejected (reason {})", bytes[1]);
                }
                ensure!(
                    bytes.len() == 32 && bytes[0] == 0x81 && bytes[17..20] == [0, 0, 0],
                    "invalid download handshake"
                );
                welcomed = true;
            }
            Message::Binary(bytes) => {
                let take = bytes.len().min(max_bytes - data.len());
                data.extend_from_slice(&bytes[..take]);
                if data.len() >= max_bytes {
                    return Ok(data);
                }
            }
            Message::Close(Some(close)) if welcomed && close.code == CloseCode::Normal => {
                return Ok(data);
            }
            Message::Close(close) => bail!("download closed: {close:?}"),
            Message::Ping(_) | Message::Pong(_) | Message::Frame(_) => ws.flush().await?,
            Message::Text(_) => bail!("unexpected text on download stream"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn listing_parses_every_status_and_rejects_unknown() {
        let listing = DirListing::parse(&json!({"path":"/h/.claude/skills","home":"/h","status":"ok","truncated":false,"entries":[{"name":"rex"},{"name":"a.md","file":true,"symlink":true}]})).unwrap();
        assert_eq!(listing.status, DirStatus::Ok);
        assert_eq!(
            listing.entries[1],
            DirEntry {
                name: "a.md".into(),
                file: true,
                symlink: true
            }
        );
        assert!(!listing.entries[0].file);
        let missing = DirListing::parse(
            &json!({"path":"/x","home":"","status":"missing","entries":null,"truncated":false}),
        )
        .unwrap();
        assert_eq!(
            (missing.status, missing.entries.len()),
            (DirStatus::Missing, 0)
        );
        assert!(DirListing::parse(&json!({"path":"/x","status":"weird","entries":[]})).is_err());
    }

    #[test]
    fn download_refs_are_lowercase_v4_uuids() {
        let id = uuid_v4([0xff; 16]);
        assert_eq!(id, "ffffffff-ffff-4fff-bfff-ffffffffffff");
        let reference = download_ref().unwrap();
        assert_eq!(reference.len(), "download:".len() + 36);
        assert_eq!(&reference[23..24], "4");
    }

    #[test]
    fn reported_directories_accept_osc7_urls_and_plain_paths() {
        assert_eq!(
            reported_dir("file://mac.local/Users/j/My%20Project"),
            "/Users/j/My Project"
        );
        assert_eq!(reported_dir("/Users/j/plain"), "/Users/j/plain");
    }
}
