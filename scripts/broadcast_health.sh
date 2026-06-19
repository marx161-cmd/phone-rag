#!/usr/bin/env bash
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-100.69.13.12:5555}"
RESULT="/sdcard/Android/data/com.termux.phonerag/files/last-result.json"

adb -s "$SERIAL" shell "rm -f '$RESULT'"
adb -s "$SERIAL" shell am broadcast -a com.termux.phonerag.HEALTH -n com.termux.phonerag/.PhoneRagReceiver >/dev/null
for _ in $(seq 1 20); do
  if adb -s "$SERIAL" shell "test -s '$RESULT'" >/dev/null 2>&1; then
    adb -s "$SERIAL" shell "cat '$RESULT'"
    exit 0
  fi
  sleep 0.5
done
echo "timed out waiting for $RESULT" >&2
exit 1
