#!/usr/bin/env bash
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-100.69.13.12:5555}"

adb -s "$SERIAL" shell am force-stop com.termux.phonerag
adb -s "$SERIAL" shell su -c 'rm -f /data/user/0/com.termux.phonerag/files/pocket-rag.db'
adb -s "$SERIAL" shell rm -f /sdcard/Android/data/com.termux.phonerag/files/last-result.json
echo "cleared phone-rag DB"
