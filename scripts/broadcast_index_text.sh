#!/usr/bin/env bash
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-100.69.13.12:5555}"
FILE="${1:-}"
TITLE="${2:-}"
SOURCE="${3:-}"
RESULT="/sdcard/Android/data/com.termux.phonerag/files/last-result.json"

if [[ -z "$FILE" || ! -f "$FILE" ]]; then
  echo "usage: $0 FILE [TITLE] [SOURCE]" >&2
  exit 2
fi
TITLE="${TITLE:-$(basename "$FILE")}"
SOURCE="${SOURCE:-$FILE}"

REMOTE_DIR="/sdcard/Android/data/com.termux.phonerag/files/inbox"
REMOTE_FILE="$REMOTE_DIR/$(date +%s)-$(basename "$FILE")"
REMOTE_TITLE="$REMOTE_FILE.title"
REMOTE_SOURCE="$REMOTE_FILE.source"
adb -s "$SERIAL" shell "rm -f '$RESULT'"
adb -s "$SERIAL" shell "mkdir -p '$REMOTE_DIR'"
adb -s "$SERIAL" push "$FILE" "$REMOTE_FILE" >/dev/null
printf '%s' "$TITLE" > /tmp/phone-rag-title.txt
printf '%s' "$SOURCE" > /tmp/phone-rag-source.txt
adb -s "$SERIAL" push /tmp/phone-rag-title.txt "$REMOTE_TITLE" >/dev/null
adb -s "$SERIAL" push /tmp/phone-rag-source.txt "$REMOTE_SOURCE" >/dev/null
adb -s "$SERIAL" shell am start-foreground-service --user 0 \
  -n com.termux.phonerag/.PhoneRagService \
  -a com.termux.phonerag.INDEX \
  --es text_file "$REMOTE_FILE" \
  --es title_file "$REMOTE_TITLE" \
  --es source_file "$REMOTE_SOURCE" >/dev/null
for _ in $(seq 1 300); do
  if adb -s "$SERIAL" shell "test -s '$RESULT'" >/dev/null 2>&1; then
    adb -s "$SERIAL" shell "cat '$RESULT'"
    exit 0
  fi
  sleep 1
done
echo "timed out waiting for $RESULT" >&2
exit 1
