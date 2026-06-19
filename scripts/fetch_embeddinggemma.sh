#!/usr/bin/env bash
set -euo pipefail

OUT="${1:-$HOME/homelab/models/embeddinggemma-litert}"
REPO="${EMBEDDINGGEMMA_REPO:-litert-community/embeddinggemma-300m}"

mkdir -p "$OUT"

if command -v hf >/dev/null 2>&1; then
  hf download "$REPO" --local-dir "$OUT"
elif command -v huggingface-cli >/dev/null 2>&1; then
  huggingface-cli download "$REPO" --local-dir "$OUT"
else
  python -m huggingface_hub.commands.huggingface_cli download "$REPO" --local-dir "$OUT"
fi

find "$OUT" -maxdepth 2 -type f \( -name '*.tflite' -o -name 'sentencepiece.model' \) -print
