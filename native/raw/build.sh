#!/bin/bash
# Build the LibRaw shim (libalmazinfer_raw). Links LibRaw's static reentrant
# archive (make -f Makefile.dist library; on Linux build it with -fPIC, since
# it ends up in a shared library). GPL-2.0-or-later.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p lib
LIBRAW_DIR="${LIBRAW_DIR:-$HOME/src/libraw}"

if [ "$(uname)" = "Darwin" ]; then
  clang++ -dynamiclib -std=c++17 -O2 -I"$LIBRAW_DIR" -I../img \
    -o lib/libalmazinfer_raw.dylib almazinfer_raw.cpp \
    "$LIBRAW_DIR/lib/libraw_r.a" -lz -lm -lc++
  echo "built lib/libalmazinfer_raw.dylib"
else
  g++ -shared -fPIC -std=c++17 -O2 -I"$LIBRAW_DIR" -I../img \
    -o lib/libalmazinfer_raw.so almazinfer_raw.cpp \
    "$LIBRAW_DIR/lib/libraw_r.a" -lz -lm -lpthread
  echo "built lib/libalmazinfer_raw.so"
fi
