#!/bin/sh
# Uploads non-image files through the JNI upload job into a disposable Rex session,
# then checks the typed paths and file bytes. Touches no other session.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
SERVER=${REX_UPLOAD_SERVER:-"unix://$HOME/Library/Application%20Support/rex/server.sock"}
REX=${REX_BIN:-"$HOME/Library/Application Support/com.superlogical.rex/RexServer/Versions/Current/Rex Server.app/Contents/MacOS/rex"}
OUT=${1:-"$ROOT/docs/pina/evidence/upload-check.txt"}
mkdir -p "$(dirname -- "$OUT")"
WORK=$(mktemp -d)
export PATH="$HOME/.cargo/bin:$PATH" GHOSTTY_PREFIX="$ROOT/rexling-core/.build/ghostty-host" CARGO_TARGET_DIR="$ROOT/rexling-core/.build/rust-host"

printf 'Pina upload check\nline two with ünïcode\n' > "$WORK/meeting notes 2026.txt"
python3 - "$WORK/design review.pdf" <<'EOF'
import sys
body = b"BT /F1 18 Tf 20 40 Td (Pina upload) Tj ET\n"
pdf = (b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n2 0 obj<</Type/Pages/Kids[3 0 R]/Count 1>>endobj\n"
       b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 200 100]/Contents 4 0 R/Resources<</Font<</F1 5 0 R>>>>>>endobj\n"
       + f"4 0 obj<</Length {len(body)}>>stream\n".encode() + body + b"endstream endobj\n"
       b"5 0 obj<</Type/Font/Subtype/Type1/BaseFont/Helvetica>>endobj\ntrailer<</Root 1 0 R>>\n%EOF\n")
open(sys.argv[1], "wb").write(pdf)
EOF

"$REX" new pina-upload-test --json > "$WORK/session.json"
SESSION=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["session_id"])' "$WORK/session.json")
BLOCK=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["initial_windows"][0]["block_ids"][0])' "$WORK/session.json")
cleanup() { "$REX" kill "$SESSION" >/dev/null 2>&1 || true; rm -rf "$WORK"; }
trap cleanup EXIT

upload() {
  REX_UPLOAD_SERVER="$SERVER" REX_UPLOAD_SESSION="$SESSION" REX_UPLOAD_BLOCK="$BLOCK" REX_UPLOAD_FILES="$1" \
    cargo test --quiet --locked --manifest-path "$ROOT/rexling-core/Cargo.toml" -p rexling-jni live_upload_job -- --ignored --nocapture 2>&1 | grep '"phase"'
}
ready() {
  for _ in $(seq 1 60); do
    "$REX" capture -s "$SESSION" -b "$BLOCK" --trim | tail -1 | grep -q '% *$' && return 0
    sleep 0.5
  done
  echo "shell prompt never appeared" >&2; return 1
}
typed() { sleep 2; "$REX" capture -s "$SESSION" -b "$BLOCK" --trim --unwrap | tail -1; }

{
  echo "Pina upload check, $(date -u +%Y-%m-%dT%H:%M:%SZ)"
  echo "server: $SERVER"
  echo "disposable session: $SESSION block: $BLOCK"
  echo
  ready
  echo "== single file"
  upload "$WORK/meeting notes 2026.txt"
  SINGLE=$(typed)
  echo "typed: $SINGLE"
  "$REX" send-key -s "$SESSION" -b "$BLOCK" ctrl+u >/dev/null
  echo
  echo "== batch of two"
  upload "$WORK/design review.pdf
$WORK/meeting notes 2026.txt"
  BATCH=$(typed)
  echo "typed: $BATCH"
  echo
  echo "== bytes"
  python3 - "$WORK" "$SINGLE" "$BATCH" <<'EOF'
import hashlib, re, sys, shlex
work, single, batch = sys.argv[1:]
def paths(line):
    words = shlex.split(line.split("% ", 1)[-1])
    return [w for w in words if w.startswith("/")]
def sha(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()
ok = True
for label, line, names in [("single", single, ["meeting notes 2026.txt"]), ("batch", batch, ["design review.pdf", "meeting notes 2026.txt"])]:
    found = paths(line)
    if [p.rsplit("/", 1)[-1] for p in found] != names:
        ok = False
        print(f"{label}: unexpected typed paths {found}")
        continue
    for path, name in zip(found, names):
        local, remote = sha(f"{work}/{name}"), sha(path)
        ok &= local == remote
        print(f"{label}: {name} local={local} uploaded={remote} {'MATCH' if local == remote else 'MISMATCH'}")
print("result:", "PASS" if ok else "FAIL")
sys.exit(0 if ok else 1)
EOF
} | tee "$OUT"

cleanup
trap - EXIT
if "$REX" ls --json 2>/dev/null | grep -q "$SESSION"; then echo "session still listed: $SESSION" | tee -a "$OUT"; exit 1; fi
echo "cleanup: killed $SESSION; it no longer appears in rex ls" | tee -a "$OUT"
grep -q '^result: PASS' "$OUT"
