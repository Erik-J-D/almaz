#!/bin/bash
# Build the ONNX Runtime shim (libalmazinfer_onnxruntime). macOS: Homebrew's
# onnxruntime (CPU + CoreML EP). Linux: Microsoft's release tarball
# (onnxruntime-linux-x64-<ver>.tgz), via ONNXRUNTIME_INC / ONNXRUNTIME_LIB.
# GPL-2.0-or-later.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p lib

if [ "$(uname)" = "Darwin" ]; then
  ORT_INC="${ONNXRUNTIME_INC:-/opt/homebrew/include/onnxruntime}"
  ORT_LIB="${ONNXRUNTIME_LIB:-/opt/homebrew/lib}"
  clang++ -dynamiclib -std=c++17 -O2 -I"$ORT_INC" \
    -o lib/libalmazinfer_onnxruntime.dylib almazinfer_onnxruntime.cpp \
    -L"$ORT_LIB" -lonnxruntime
  echo "built lib/libalmazinfer_onnxruntime.dylib"
else
  ORT_INC="${ONNXRUNTIME_INC:?set ONNXRUNTIME_INC to the include dir of the release}"
  ORT_LIB="${ONNXRUNTIME_LIB:?set ONNXRUNTIME_LIB to the lib dir of the release}"
  g++ -shared -fPIC -std=c++17 -O2 -I"$ORT_INC" \
    -o lib/libalmazinfer_onnxruntime.so almazinfer_onnxruntime.cpp \
    -L"$ORT_LIB" -lonnxruntime -Wl,-rpath,"$ORT_LIB"
  echo "built lib/libalmazinfer_onnxruntime.so"
fi
