#!/usr/bin/env bash
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-100.69.13.12:5555}"

adb -s "$SERIAL" shell am start -n com.termux.phonerag/.MainActivity >/dev/null
adb -s "$SERIAL" forward tcp:8791 tcp:8791
curl -sS http://127.0.0.1:8791/health
echo
