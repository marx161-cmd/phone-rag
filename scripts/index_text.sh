#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 ]]; then
  echo "usage: $0 FILE [TITLE] [SOURCE]" >&2
  exit 2
fi

FILE="$1"
TITLE="${2:-$(basename "$FILE")}"
SOURCE="${3:-$FILE}"

jq -n --rawfile text "$FILE" --arg title "$TITLE" --arg source "$SOURCE" \
  '{text:$text,title:$title,source:$source}' |
  curl -sS -X POST http://127.0.0.1:8791/index \
    -H 'Content-Type: application/json' \
    --data-binary @-
echo
