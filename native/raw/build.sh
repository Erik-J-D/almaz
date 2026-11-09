#!/bin/bash
# Build libalmazinfer_raw.dylib — the LibRaw shim for the almaz app.
# Links LibRaw's static reentrant archive (built with `make -f Makefile.dist library`).
# GPL-2.0-or-later.
set -euo pipefail

cd "$(dirname "$0")"
mkdir -p lib

LIBRAW_DIR="${LIBRAW_DIR:-$HOME/src/libraw}"

clang++ -dynamiclib -std=c++17 -O2 \
  -I"$LIBRAW_DIR" -I../img \
  -o lib/libalmazinfer_raw.dylib \
  almazinfer_raw.cpp \
  "$LIBRAW_DIR/lib/libraw_r.a" \
  -lz -lm -lstdc++

echo "built lib/libalmazinfer_raw.dylib"
