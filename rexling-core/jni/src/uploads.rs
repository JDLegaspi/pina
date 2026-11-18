use anyhow::{Context, Result};
use rex_core::{
    transport::Endpoint,
    upload::{self, Phase, Request, UploadFile},
};
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    path::Path,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};
use tokio::sync::oneshot;
struct Job {
    state: Arc<Mutex<Value>>,
    cancel: Option<oneshot::Sender<()>>,
}
static JOBS: OnceLock<Mutex<HashMap<u64, Job>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
fn jobs() -> &'static Mutex<HashMap<u64, Job>> {
    JOBS.get_or_init(|| Mutex::new(HashMap::new()))
}
/// `value` is {endpoint, session, block, files: [{path, name, ref}], batch?}; `batch` is required for several files.
pub fn create(value: Value) -> Result<u64> {
    let endpoint = Endpoint::new(
        value["endpoint"]
            .as_str()
            .context("Upload server missing")?,
        Duration::from_secs(10),
    )?;
    let text = |value: &Value, key: &str| {
        value[key]
            .as_str()
            .filter(|v| !v.is_empty())
            .map(str::to_owned)
            .with_context(|| format!("Upload {key} missing"))
    };
    let session = text(&value, "session")?;
    let block = text(&value, "block")?;
    let files = value["files"]
        .as_array()
        .context("Upload files missing")?
        .iter()
        .map(|file| Ok((text(file, "path")?, text(file, "name")?, text(file, "ref")?)))
        .collect::<Result<Vec<_>>>()?;
    anyhow::ensure!(!files.is_empty() && files.len() <= upload::MAX_FILES, "Choose from 1 to {} files", upload::MAX_FILES);
    let batch = value["batch"].as_str().filter(|v| !v.is_empty()).map(str::to_owned);
    anyhow::ensure!(files.len() == 1 || batch.is_some(), "Upload batch missing");
    let state = Arc::new(Mutex::new(upload::status(Phase::Preparing, 0, 0, 0, "")));
    let shared = state.clone();
    let (tx, rx) = oneshot::channel();
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    let mut registry = jobs()
        .lock()
        .map_err(|_| anyhow::anyhow!("Upload registry unavailable"))?;
    anyhow::ensure!(registry.is_empty(), "An upload is already running");
    let worker = std::thread::Builder::new()
        .name("rexling-upload".into())
        .spawn(move || {
            let result =
                std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<()> {
                    let runtime = tokio::runtime::Builder::new_current_thread()
                        .enable_all()
                        .build()?;
                    let specs: Vec<UploadFile> = files
                        .iter()
                        .map(|(path, name, reference)| UploadFile { path: Path::new(path), name, reference })
                        .collect();
                    let (phase, error) = runtime.block_on(upload::upload(
                        Request {
                            endpoint,
                            session: &session,
                            block: &block,
                            files: &specs,
                            batch: batch.as_deref(),
                        },
                        rx,
                        |phase, sent, total, file| {
                            if let Ok(mut state) = shared.lock() {
                                *state = upload::status(phase, sent, total, file, "");
                            }
                        },
                    ));
                    if let Ok(mut state) = shared.lock() {
                        let sent = state["sent"].as_u64().unwrap_or(0);
                        let total = state["total"].as_u64().unwrap_or(0);
                        let file = state["file"].as_u64().unwrap_or(0) as usize;
                        *state = upload::status(phase, sent, total, file, &error);
                    }
                    Ok(())
                }));
            let error = match result {
                Ok(Ok(())) => None,
                Ok(Err(error)) => Some(error.to_string()),
                Err(_) => Some("Upload worker failed".into()),
            };
            if let Some(error) = error {
                if let Ok(mut state) = shared.lock() {
                    *state = upload::status(Phase::Uncertain, 0, 0, 0, &error);
                }
            }
        });
    worker.context("Could not start upload worker")?;
    registry.insert(
        id,
        Job {
            state,
            cancel: Some(tx),
        },
    );
    Ok(id)
}
pub fn poll(id: u64) -> Value {
    jobs()
        .lock()
        .ok()
        .and_then(|jobs| {
            jobs.get(&id)
                .and_then(|job| job.state.lock().ok().map(|state| state.clone()))
        })
        .unwrap_or_else(|| json!({"phase":"failed","error":"Upload is no longer available"}))
}
pub fn destroy(id: u64) {
    if let Ok(mut jobs) = jobs().lock() {
        if let Some(mut job) = jobs.remove(&id) {
            if let Some(cancel) = job.cancel.take() {
                let _ = cancel.send(());
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Writes to the pane: REX_UPLOAD_SERVER, REX_UPLOAD_SESSION and REX_UPLOAD_BLOCK must name a disposable pane.
    /// REX_UPLOAD_FILES holds newline-separated paths, uploaded in order as one batch.
    #[test]
    #[ignore]
    fn live_upload_job() {
        let var = |key: &str| std::env::var(key).unwrap_or_else(|_| panic!("{key}"));
        let files: Vec<String> = var("REX_UPLOAD_FILES").lines().filter(|l| !l.is_empty()).map(str::to_owned).collect();
        let files: Vec<Value> = files
            .iter()
            .enumerate()
            .map(|(index, path)| {
                let name = Path::new(path).file_name().unwrap().to_string_lossy().into_owned();
                json!({"path":path,"name":name,"ref":format!("upload:00000000-0000-4000-8000-{:012x}",std::process::id() as usize*64+index)})
            })
            .collect();
        let mut request = json!({"endpoint":var("REX_UPLOAD_SERVER"),"session":var("REX_UPLOAD_SESSION"),"block":var("REX_UPLOAD_BLOCK"),"files":files});
        if files.len() > 1 {
            request["batch"] = json!(format!("batch:{}", std::process::id()));
        }
        let id = create(request).unwrap();
        let deadline = std::time::Instant::now() + Duration::from_secs(60);
        let state = loop {
            let state = poll(id);
            if matches!(state["phase"].as_str(), Some("done" | "failed" | "uncertain" | "unsupported" | "canceled")) || std::time::Instant::now() > deadline {
                break state;
            }
            std::thread::sleep(Duration::from_millis(20));
        };
        destroy(id);
        println!("{state}");
        assert_eq!(state["phase"], "done", "{state}");
    }
}
