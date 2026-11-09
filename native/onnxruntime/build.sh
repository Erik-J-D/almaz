#!/bin/bash
# Build libalmazinfer_onnxruntime.dylib — the ONNX Runtime shim for the almaz app.
# CPU-only build against the Homebrew onnxruntime. GPL-2.0-or-later.
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p lib

ORT_INC="${ONNXRUNTIME_INC:-/opt/homebrew/include/onnxruntime}"
ORT_LIB="${ONNXRUNTIME_LIB:-/opt/homebrew/lib}"

clang++ -dynamiclib -std=c++17 -O2 \
  -I"$ORT_INC" \
  -o lib/libalmazinfer_onnxruntime.dylib \
  almazinfer_onnxruntime.cpp \
  -L"$ORT_LIB" -lonnxruntime

echo "built lib/libalmazinfer_onnxruntime.dylib"
