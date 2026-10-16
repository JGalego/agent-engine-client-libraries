#!/usr/bin/env bash
# Fetches the embedding model. The reports themselves come from the FDA and NHTSA APIs at ingest.
# Safe to re-run.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/data/models/minilm" "$ROOT/data/cache"

fetch() {
  local dest="$1"
  local url="$2"
  if [[ -s "$dest" ]]; then
    echo "present $dest"
    return 0
  fi
  echo "get $url"
  curl -fL --retry 5 --retry-delay 2 --retry-all-errors -o "$dest.partial" "$url"
  mv "$dest.partial" "$dest"
}

fetch "$ROOT/data/models/minilm/vocab.txt" \
  "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/vocab.txt"
fetch "$ROOT/data/models/minilm/tokenizer.json" \
  "https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2/resolve/main/tokenizer.json"
fetch "$ROOT/data/models/minilm/model.onnx" \
  "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model.onnx"
fetch "$ROOT/data/models/minilm/model_quantized.onnx" \
  "https://huggingface.co/Xenova/all-MiniLM-L6-v2/resolve/main/onnx/model_quantized.onnx"
ls -lh "$ROOT/data/models/minilm/model_quantized.onnx" "$ROOT/data/models/minilm/model.onnx"
echo "download-sources done"
