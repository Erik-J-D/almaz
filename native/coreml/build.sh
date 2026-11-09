#!/bin/bash
# Build libalmazinfer_coreml.dylib — the CoreML/ANE shim for the almaz app.
# GPL-2.0-or-later.
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p lib

clang++ -fobjc-arc -dynamiclib -std=c++17 \
  -O2 \
  -o lib/libalmazinfer_coreml.dylib \
  almazinfer_coreml.mm \
  -framework Foundation -framework CoreML

echo "built lib/libalmazinfer_coreml.dylib"
