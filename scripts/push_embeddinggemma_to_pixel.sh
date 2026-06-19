#!/usr/bin/env bash
set -euo pipefail

SERIAL="${ANDROID_SERIAL:-100.69.13.12:5555}"
MODEL_DIR="${1:-$HOME/homelab/models/embeddinggemma-litert}"
REMOTE="/data/local/tmp/embeddinggemma"

MODEL_PATH="${EMBEDDINGGEMMA_TFLITE:-}"
TOKENIZER_PATH="${EMBEDDINGGEMMA_TOKENIZER:-}"

if [[ -z "$MODEL_PATH" ]]; then
  MODEL_PATH="$(find "$MODEL_DIR" -type f -name '*seq512_mixed-precision.tflite' | sort | head -n 1)"
fi
if [[ -z "$MODEL_PATH" ]]; then
  MODEL_PATH="$(find "$MODEL_DIR" -type f -name '*seq1024_mixed-precision.tflite' | sort | head -n 1)"
fi
if [[ -z "$MODEL_PATH" ]]; then
  MODEL_PATH="$(find "$MODEL_DIR" -type f -name '*.tflite' | sort | head -n 1)"
fi
if [[ -z "$TOKENIZER_PATH" ]]; then
  TOKENIZER_PATH="$(find "$MODEL_DIR" -type f -name 'sentencepiece.model' | sort | head -n 1)"
fi

if [[ -z "$MODEL_PATH" || ! -f "$MODEL_PATH" ]]; then
  echo "No .tflite model found under $MODEL_DIR" >&2
  exit 1
fi
if [[ -z "$TOKENIZER_PATH" || ! -f "$TOKENIZER_PATH" ]]; then
  echo "No sentencepiece.model found under $MODEL_DIR" >&2
  exit 1
fi

adb -s "$SERIAL" shell "mkdir -p '$REMOTE'"
adb -s "$SERIAL" push "$MODEL_PATH" "$REMOTE/model.tflite"
adb -s "$SERIAL" push "$TOKENIZER_PATH" "$REMOTE/sentencepiece.model"
adb -s "$SERIAL" shell "ls -lh '$REMOTE'"
