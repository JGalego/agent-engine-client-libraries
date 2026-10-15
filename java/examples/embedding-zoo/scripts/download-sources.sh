#!/usr/bin/env bash
# Fetches the demo's real source files. Safe to re-run; the IMS zip resumes.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
mkdir -p "$ROOT/data/ims" "$ROOT/data/models/minilm" "$ROOT/data/logs" "$ROOT/data/mvtec" "$ROOT/data/cache"

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
fetch "$ROOT/data/logs/HDFS_2k.log" \
  "https://raw.githubusercontent.com/logpai/loghub/master/HDFS/HDFS_2k.log"
fetch "$ROOT/data/logs/HDFS_README.md" \
  "https://raw.githubusercontent.com/logpai/loghub/master/HDFS/README.md"
fetch "$ROOT/data/logs/LOGHUB_LICENSE.txt" \
  "https://raw.githubusercontent.com/logpai/loghub/master/LICENSE"

if [[ ! -x "$ROOT/data/tools/7zz" ]]; then
  echo "get 7-Zip 26.02 (extracts the IMS RAR; junrar stops early on this archive)"
  mkdir -p "$ROOT/data/tools"
  curl -fL --retry 5 --retry-delay 2 -o "$ROOT/data/tools/7z-linux-x64.tar.xz" \
    "https://www.7-zip.org/a/7z2602-linux-x64.tar.xz"
  tar -xJf "$ROOT/data/tools/7z-linux-x64.tar.xz" -C "$ROOT/data/tools" 7zz License.txt
  mv "$ROOT/data/tools/License.txt" "$ROOT/data/tools/7zip-License.txt"
  chmod +x "$ROOT/data/tools/7zz"
fi

if [[ ! -s "$ROOT/data/ims/IMS.zip" ]]; then
  echo "get IMS zip (about 1 GB)"
  curl -fL --retry 5 --retry-delay 2 -C - --output "$ROOT/data/ims/IMS.zip" \
    "https://phm-datasets.s3.amazonaws.com/NASA/4.+Bearings.zip"
fi
ls -lh "$ROOT/data/ims/IMS.zip" "$ROOT/data/models/minilm/model.onnx" "$ROOT/data/logs/HDFS_2k.log"
echo "download-sources done"
