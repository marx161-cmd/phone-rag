#!/usr/bin/env bash
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-100.69.13.12:5555}"
QUERY="${1:-}"
TOP_K="${2:-6}"
RESULT="/sdcard/Android/data/com.termux.phonerag/files/last-result.json"
REMOTE_DIR="/sdcard/Android/data/com.termux.phonerag/files/inbox"
REMOTE_QUERY="$REMOTE_DIR/query-$(date +%s).txt"

if [[ -z "$QUERY" ]]; then
  echo "usage: $0 QUERY [TOP_K]" >&2
  exit 2
fi

adb -s "$SERIAL" shell "rm -f '$RESULT'"
adb -s "$SERIAL" shell "mkdir -p '$REMOTE_DIR'"
printf '%s' "$QUERY" > /tmp/phone-rag-query.txt
adb -s "$SERIAL" push /tmp/phone-rag-query.txt "$REMOTE_QUERY" >/dev/null
adb -s "$SERIAL" shell am start-foreground-service --user 0 \
  -n com.termux.phonerag/.PhoneRagService \
  -a com.termux.phonerag.QUERY \
  --es query_file "$REMOTE_QUERY" \
  --ei top_k "$TOP_K" >/dev/null
for _ in $(seq 1 120); do
  if adb -s "$SERIAL" shell "test -s '$RESULT'" >/dev/null 2>&1; then
    adb -s "$SERIAL" shell "cat '$RESULT'"
    exit 0
  fi
  sleep 1
done
echo "timed out waiting for $RESULT" >&2
exit 1
