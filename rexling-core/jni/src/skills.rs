use anyhow::{Context, Result, ensure};
use rex_core::transport::Endpoint;
use serde_json::{Value, json};
use std::{
    collections::HashMap,
    sync::{
        Arc, Mutex, OnceLock,
        atomic::{AtomicU64, Ordering},
    },
    time::Duration,
};
use tokio::sync::oneshot;

const MAX_JOBS: usize = 4;
const DISCOVERY_TIMEOUT: Duration = Duration::from_secs(20);

struct Job {
    state: Arc<Mutex<Value>>,
    cancel: oneshot::Sender<()>,
}
static JOBS: OnceLock<Mutex<HashMap<u64, Job>>> = OnceLock::new();
static NEXT: AtomicU64 = AtomicU64::new(1);
fn jobs() -> &'static Mutex<HashMap<u64, Job>> {
    JOBS.get_or_init(|| Mutex::new(HashMap::new()))
}

/// Starts skill discovery for the pane in `value` ({endpoint, session, block}).
pub fn create(value: Value) -> Result<u64> {
    let field = |key: &str| {
        value[key]
            .as_str()
            .filter(|s| !s.is_empty())
            .map(str::to_owned)
            .with_context(|| format!("Skills {key} missing"))
    };
    let endpoint = Endpoint::new(&field("endpoint")?, Duration::from_secs(10))?;
    let session = field("session")?;
    let block = field("block")?;
    let state = Arc::new(Mutex::new(json!({"phase":"running"})));
    let shared = state.clone();
    let (cancel, rx) = oneshot::channel();
    let id = NEXT.fetch_add(1, Ordering::Relaxed);
    let mut registry = jobs()
        .lock()
        .map_err(|_| anyhow::anyhow!("Skills registry unavailable"))?;
    ensure!(registry.len() < MAX_JOBS, "Too many skill lookups are running");
    std::thread::Builder::new()
        .name("pina-skills".into())
        .spawn(move || {
            let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| -> Result<Option<Value>> {
                let runtime = tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()?;
                runtime.block_on(async {
                    tokio::select! {
                        biased;
                        _ = rx => Ok(None),
                        discovery = rex_core::skills::discover(endpoint, &session, &block, DISCOVERY_TIMEOUT) => discovery.map(|d| Some(d.to_json())),
                    }
                })
            }));
            let next = match result {
                Ok(Ok(Some(mut discovery))) => {
                    discovery["phase"] = json!("done");
                    discovery
                }
                Ok(Ok(None)) => json!({"phase":"canceled"}),
                Ok(Err(error)) => json!({"phase":"failed","error":format!("{error:#}")}),
                Err(_) => json!({"phase":"failed","error":"Skill lookup failed"}),
            };
            if let Ok(mut state) = shared.lock() {
                *state = next;
            }
        })
        .context("Could not start skill lookup")?;
    registry.insert(id, Job { state, cancel });
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
        .unwrap_or_else(|| json!({"phase":"failed","error":"Skill lookup is closed"}))
}

pub fn destroy(id: u64) {
    if let Ok(mut jobs) = jobs().lock()
        && let Some(job) = jobs.remove(&id)
    {
        let _ = job.cancel.send(());
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn wait(id: u64) -> Value {
        let deadline = std::time::Instant::now() + Duration::from_secs(30);
        loop {
            let state = poll(id);
            if state["phase"] != "running" || std::time::Instant::now() > deadline {
                return state;
            }
            std::thread::sleep(Duration::from_millis(20));
        }
    }

    #[test]
    fn rejects_requests_without_a_pane() {
        assert!(create(json!({"endpoint":"unix:///tmp/none.sock","session":"session:a"})).is_err());
        assert!(create(json!({"endpoint":"host-label","session":"session:a","block":"block:a"})).is_err());
    }

    #[test]
    fn unreachable_server_fails_without_panicking() {
        let id = create(json!({"endpoint":"unix:///nonexistent/pina-skills.sock","session":"session:a","block":"block:a"})).unwrap();
        let state = wait(id);
        destroy(id);
        assert_eq!(state["phase"], "failed", "{state}");
        assert!(!state["error"].as_str().unwrap_or_default().is_empty());
    }

    /// Read-only. REX_SKILLS_SERVER, REX_SKILLS_SESSION and REX_SKILLS_BLOCK name a pane you own.
    #[test]
    #[ignore]
    fn live_skills_job() {
        let var = |key: &str| std::env::var(key).unwrap_or_else(|_| panic!("{key}"));
        let started = std::time::Instant::now();
        let id = create(json!({"endpoint":var("REX_SKILLS_SERVER"),"session":var("REX_SKILLS_SESSION"),"block":var("REX_SKILLS_BLOCK")})).unwrap();
        let state = wait(id);
        destroy(id);
        assert_eq!(state["phase"], "done", "{state}");
        let commands = state["commands"].as_array().cloned().unwrap_or_default();
        assert!(!commands.is_empty());
        let mut sources = serde_json::Map::new();
        for command in &commands {
            let key = command["plugin"].as_str().map_or_else(|| command["source"].as_str().unwrap_or("?").to_owned(), |p| format!("plugin:{p}"));
            let count = sources.entry(key).or_insert(json!(0));
            *count = json!(count.as_u64().unwrap_or(0) + 1);
        }
        let summary = json!({"phase":state["phase"],"elapsed_ms":started.elapsed().as_millis() as u64,"count":commands.len(),
            "by_source":sources,"unreadable":state["unreadable"],"cwd":state["cwd"],
            "sample":commands.iter().take(3).map(|c|&c["name"]).collect::<Vec<_>>()});
        let text = serde_json::to_string_pretty(&summary).unwrap();
        match std::env::var("REX_SKILLS_OUTPUT") {
            Ok(path) => std::fs::write(path, text).unwrap(),
            Err(_) => println!("{text}"),
        }
    }
}
