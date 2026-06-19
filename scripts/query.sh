#!/usr/bin/env bash
set -euo pipefail

QUERY="${1:-}"
TOP_K="${2:-6}"

if [[ -z "$QUERY" ]]; then
  echo "usage: $0 QUERY [TOP_K]" >&2
  exit 2
fi

jq -n --arg query "$QUERY" --argjson top_k "$TOP_K" '{query:$query,top_k:$top_k}' |
  curl -sS -X POST http://127.0.0.1:8791/query \
    -H 'Content-Type: application/json' \
    --data-binary @-
echo
