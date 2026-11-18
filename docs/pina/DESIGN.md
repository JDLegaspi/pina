# Pina design and decision trail

Pina is an Android client for a Rex server, forked from [rexling](https://github.com/shulhi/rexling) (Apache-2.0). It adds an agent-first home screen, an Apple-style look, a native slash-command picker for Claude Code skills, and uploads of any file type.

## Decisions

| # | Decision | Why | Alternatives rejected |
| --- | --- | --- | --- |
| 1 | Fork rexling instead of writing a new client | Rex's terminal stream opens with a Ghostty-format binary snapshot (`GHOSTSNP`), so a faithful terminal needs libghostty. Rexling already ships it through its Rust core, plus uploads, alerts, built-in Tailscale and SSH. | A pure Kotlin client would have to skip the snapshot and lose scrollback, or reimplement Ghostty's format. |
| 2 | Keep the Kotlin package `dev.shulhi.rex` and change only `applicationId` to `dev.jdlegaspi.pina` | The JNI symbols (`Java_dev_shulhi_rex_NativeBridge_*`) are bound to the package. Changing `applicationId` alone lets Pina install next to Rexling. | Renaming every Kotlin file and all 13 JNI exports adds churn and no user value. |
| 3 | Home lists every terminal pane, built from the existing Activity scan | `activity.rs` already walks every session and pane and reads `program_status`. Emitting idle panes too gives the folder view its data with no second scanner. | A second Kotlin-side scan duplicates the Rust actor and its limits. |
| 4 | Status sections in a fixed order, Blocked, Working, Error, Done, Idle, from one `AgentStatus` enum | The declaration order is the section order. One table drives the titles, tint and grouping. | Per-screen `when` chains. |
| 5 | Folder view groups by Rex session label | This matches the Mac sidebar's folder mode, where the headers are session labels and the rows are panes. | Grouping by working directory: several panes share a session but differ in cwd. |
| 6 | Notifications reuse rexling's `AttentionLedger` and background monitor | It already alerts on `blocked`, `error` and `done` transitions with deep links into the pane. Pina turns it on by default and asks for notification permission on first launch. | A new service would duplicate the monitor connection. |
| 7 | Slash commands use a native picker fed by a Rust `skills` job over `list_dir` and `download_begin` | The skills live on the Mac, not the phone. One control connection lists the skill directories and reads each `SKILL.md` front matter. The terminal's working directory comes from Rex, so project skills resolve correctly. The JNI job dials the pane's own endpoint string, so it takes the same route as the terminal (phone network, built-in Tailscale or SSH). The list is cached per server and directory, in memory and in `SharedPreferences`, and the picker opens on the cached list while it refreshes. | Parsing Claude Code's own TUI menu out of the screen is brittle. |
| 8 | `/` opens the picker only in a Claude Code pane whose prompt Pina believes is empty | `PromptLine` reduces every key and paste Pina sends to a pane into Empty, NonEmpty or Unknown. Enter, Ctrl-C, Ctrl-U and Escape empty it. Typing or pasting fills it. Arrows and editing keys make it Unknown, which counts as not empty. A pane Pina has not typed into counts as empty. A `/` inside a path still types normally. Dismissing the picker types the `/` it held back. A toolbar `/` button always opens the picker. | Intercepting every `/` breaks shell and editor use. Reading the prompt off the screen depends on Claude Code's layout. |
| 9 | Uploads accept any file through Rex `upload_begin` and keep the original file name | Rex types the uploaded path into the terminal. Claude Code reads it from there. Several files travel as one Rex batch over one control connection, so their paths are typed together, space separated, once the last file lands. A batch spread over separate connections is dropped by the server (observed on the live server). | A separate upload service, which needs another server. Sequential single uploads, which run their paths together with no separator. |
| 10 | Apple look through design tokens (iOS system colors, Inter type scale, inset grouped lists, segmented control, large titles) | This matches Rex on the Mac. Inter stands in for SF Pro, whose license limits it to Apple platforms. | Material 3 defaults. |

## Verification

- Baseline: unmodified rexling builds with `:app:assembleDebug` before any change.
- Every workstream ends with `:app:assembleDebug` and `:app:testDebugUnitTest`.
- The Android emulator hangs on this Mac ("hvf is not enabled on this aarch64 host", emulator 37.2.13 on macOS 26.5), so on-device checks run on a physical phone instead.
- Without an emulator, JVM screenshot tests (Robolectric native graphics and Roborazzi) render the home list, banner, picker, onboarding and Add server screens from synthetic fixtures. `:app:recordRoborazziDebug` writes them to `docs/pina/screenshots/`. A plain `:app:testDebugUnitTest` renders them without comparing.
- Pure grouping and skill-parsing logic have unit tests. So do picker ranking, the prompt-state reducer, the skill cache and upload naming.

### Live evidence

Checks against a real Rex server write to `docs/pina/evidence/`. That directory is gitignored because its contents name real sessions, panes and paths, so regenerate it locally when you need it. The Rust commands below run from `rexling-core` after `mkdir -p ../docs/pina/evidence`, with `GHOSTTY_PREFIX="$PWD/.build/ghostty-host" CARGO_TARGET_DIR="$PWD/.build/rust-host"` set.

- `live_home_scan_dump` is an ignored `rexling-jni` test that runs the activity scan read-only against a server you own. Run `REX_HOME_SCAN_SERVER=<endpoint> REX_HOME_SCAN_OUTPUT="$PWD/../docs/pina/evidence/home-scan.json" cargo test -p rexling-jni live_home_scan_dump -- --ignored`.
- `RealScanScreenshotTest` renders that dump into `home-live-status-light.png` and `home-live-folders-light.png` in the same directory during `:app:recordRoborazziDebug`. It skips when the dump is missing. With `PINA_WRITE_EVIDENCE=1` it also writes `home-scan-summary.txt`.
- `live_skills_job` is an ignored `rexling-jni` test that runs the JNI skills job read-only against a pane you own. Run `REX_SKILLS_SERVER=<endpoint> REX_SKILLS_SESSION=<session id> REX_SKILLS_BLOCK=<block id> REX_SKILLS_OUTPUT="$PWD/../docs/pina/evidence/skills-jni-job.json" cargo test -p rexling-jni live_skills_job -- --ignored`.
- `scripts/upload-check.sh` creates a disposable Rex session, uploads a text file and then a two-file batch through the JNI upload job, checks the typed paths and SHA-256 of each uploaded file, and kills only that session. It writes `docs/pina/evidence/upload-check.txt`. Set `REX_UPLOAD_SERVER` and `REX_BIN` when Rex is not at its default macOS location.
