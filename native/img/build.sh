#!/bin/bash
# Build libalmazimg — the almaz pixel engine (libjpeg, libpng, libtiff).
# GPL-2.0-or-later.
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p lib
if [ "$(uname)" = "Darwin" ]; then
  P="${HOMEBREW_PREFIX:-/opt/homebrew}"
  clang -dynamiclib -std=c11 -O3 -Wall -Wno-unused-function \
    -I"$P/include" -o lib/libalmazimg.dylib almazimg.c exif.c sha256.c \
    -L"$P/lib" -ljpeg -lpng16 -ltiff -lm -lpthread
  echo "built lib/libalmazimg.dylib"
else
  cc -shared -fPIC -std=gnu11 -O3 -Wall -Wno-unused-function \
    $(pkg-config --cflags libjpeg libpng tiff-4 2>/dev/null) -o lib/libalmazimg.so almazimg.c exif.c sha256.c \
    $(pkg-config --libs libjpeg libpng tiff-4 2>/dev/null || echo -ljpeg -lpng -ltiff) -lm -lpthread
  echo "built lib/libalmazimg.so"
fi
