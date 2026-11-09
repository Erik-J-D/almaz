#!/bin/bash
# Fetch the RAW samples the native tests use (not committed: they're large).
# The Sony sample is from raw.pixls.us (CC0). DSC_9852.NEF (Nikon Z 9, HE)
# isn't public; tests that need it skip without it.
set -euo pipefail
cd "$(dirname "$0")/../.."

fetch() { # url file sha256
  if [ -f "$2" ] && echo "$3  $2" | shasum -a 256 -c --status 2>/dev/null; then
    echo "have $2"; return
  fi
  curl -fL --retry 3 -o "$2.part" "$1"
  echo "$3  $2.part" | shasum -a 256 -c --status || { echo "checksum mismatch for $2" >&2; rm -f "$2.part"; exit 1; }
  mv "$2.part" "$2"
  echo "fetched $2"
}

fetch https://raw.pixls.us/data/Sony/ILCE-7M3/UNCOMPRESSED_14bit.ARW ILCE-7M3.ARW \
  ece80551abf64949dbe826985a80f1d9b265401ee4ea6b989e1829549616fdcd
