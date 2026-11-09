# almaz

AI **denoise** and **sharpen** for photos (camera RAW, JPEG, PNG, TIFF) as a
native Clojure-on-Jolt app with a Blender-style UI (`glimmer-bui`). Inference
runs on open NAFNet models through ONNX Runtime: **Core ML (ANE/GPU)** on
macOS, CUDA/TensorRT on Linux, CPU everywhere.

See [PLAN.md](PLAN.md) for the target analysis and plan.

## What it does

- Opens RAW files at full resolution through LibRaw (Nikon HE included), plus
  JPEG / PNG / TIFF (8 and 16 bit). The camera's embedded preview shows while
  the RAW decodes.
- **Filters are layers.** Each layer (Denoise, Sharpen) runs over the composite
  of the layers below it and blends back through its **strength** and its own
  **mask**. Masks are painted with a soft round brush (size, hardness) and have
  a **feather** (gaussian) so edges blend smoothly into the layer below; Fill,
  Clear and Invert act on the whole mask.
- **Autopilot** measures noise and sharpness when an image opens and adds the
  layers it needs at suggested strengths.
- Processing is tiled (512 px, crossfaded overlaps, no seams) and starts at
  the part of the image being viewed. Edits re-run only what they invalidate:
  a filter's settings re-run it and the layers above; its strength, mask or
  feather only re-run the layers above. Tiles a mask leaves untouched are
  skipped.
- Views: single, split (draggable divider) and side by side, with a navigator,
  zoom presets, wheel zoom about the pointer, drag to pan, double click for
  fit / 100%.
- Filmstrip of opened images; only the current one keeps full-resolution
  pixels.
- Export the full-resolution composite as JPEG, 16-bit TIFF, or 8/16-bit PNG,
  keeping the photo's shooting metadata (camera, lens, exposure, aperture, ISO,
  focal length, capture time) as EXIF. It is read from the source's own EXIF
  (JPEG, PNG, and TIFF-based RAW such as NEF/ARW/DNG/CR2) or, for other RAW
  formats, from LibRaw.

Timings on an M-series Mac (Core ML), 45 MP Nikon Z9 file: decode ~3 s,
denoise ~45 s, sharpen ~45 s for the whole frame, export ~6 s.

## Data model

Documents and their filter layers live in a [relatum](https://github.com/yogthos/relatum)
database (`almaz.doc`): `:doc` and `:layer` tables with typed columns, checks
(status and kind sets, opacity in [0, 1]), and a cascading foreign key from
layers to their document. Functions take the db and return the new one; the
app keeps it in one reactive cell (`almaz.state/db*`). UI-only state (view,
tool, selection, job progress) stays in plain cells.

## Layout

```
src/almaz/
  core.clj              the app: layout + -main
  ui/canvas.clj         image pane: rendering, overlays, pan/zoom/brush input
  ui/sidebar.clj        navigator, autopilot, layer panels + mask tools
  ui/chrome.clj         top bar, status bar, filmstrip
  ui/units.clj          sizes in widget units (HiDPI)
  state.clj             reactive state: the relatum db + UI cells
  actions.clj           user commands
  worker.clj            background thread: load, process, thumbnails, export
  lifecycle.clj         native memory ownership (frees on the UI thread)
  doc.clj               data model: relatum tables (:doc, :layer), staleness
  view.clj              viewport math (pure)
  autopilot.clj         estimates -> layers (pure)
  engine.clj            runs one layer over the composite, tile by tile
  models.clj            model registry + sessions
  pixels.clj            FFI: native pixel engine
  raw.clj               FFI: LibRaw shim
  infer/                backend protocol, EP selection, tile planning,
                        ONNX Runtime + Core ML backends
native/
  img/                  pixel engine (C): 16-bit images + mip pyramid, masks,
                        compositing, tiles, filters, estimators, file I/O
  onnxruntime/          C++ shim over ONNX Runtime (CPU, CoreML, CUDA EPs)
  raw/                  C++ shim over LibRaw
  coreml/               ObjC++ shim over Core ML (.mlmodelc)
  tools/                model export scripts
test/almaz/             clojure.test suites (pure + native)
```

## Build

```sh
# BUI kit (see glimmer-bui's README), then:
native/img/build.sh           # needs libjpeg, libpng, libtiff (brew install jpeg-turbo libpng libtiff)
native/onnxruntime/build.sh   # needs `brew install onnxruntime`
native/raw/build.sh           # needs LibRaw, below
native/coreml/build.sh        # optional (our own Core ML shim; needs Xcode)
```

**glimmer-bui**: `deps.edn` points at a local checkout
(`../jlt-commons/glimmer-bui`, branch `props-diff-events`), which fixes the
flicker/jitter (props are diffed on re-render instead of re-applied, which
relaid the whole UI and snapped sliders back mid-drag), passes pointer
positions to raw events, routes panel children into the panel body, and adds
layout/colour props, canvas items and the file dialog. Switch back to a git
coordinate once that branch is merged.

**LibRaw** (the 202611 snapshot, with the Nikon HE decoder):

```sh
git clone --depth 1 https://github.com/LibRaw/LibRaw ~/src/libraw
cd ~/src/libraw && make -f Makefile.dist -j8 library   # lib/libraw_r.a
```

## Models

Both are official NAFNet checkpoints (megvii-research; MIT, with parts from
BasicSR under Apache-2.0), exported to fixed 512x512 ONNX graphs. The app
downloads them on first use from the
[`models-v1` release](https://github.com/yogthos/almaz/releases/tag/models-v1)
and verifies each against its pinned SHA-256 (`almaz.models/registry`) before
using it. They go to `~/Library/Application Support/almaz/models` on macOS,
`$XDG_DATA_HOME/almaz/models` (or `~/.local/share/almaz/models`) on Linux,
`models/almaz` in a source checkout, or `$ALMAZ_MODELS` when set.

To re-export them yourself, `native/tools/export_nafnet.py` defines the
architecture inline and loads checkpoints with torch's safe `weights_only`
unpickler:

```sh
mkdir -p models/ckpt models/almaz
curl -L -o models/ckpt/NAFNet-SIDD-width64.pth \
  https://huggingface.co/spaces/chuxiaojie/NAFNet/resolve/main/NAFNet-SIDD-width64.pth
curl -L -o models/ckpt/NAFNet-REDS-width64.pth \
  https://huggingface.co/spaces/chuxiaojie/NAFNet/resolve/main/NAFNet-REDS-width64.pth
# sha256: cd685efa... (SIDD), 175fe8b3... (REDS)
python3 -m venv native/tools/venv && native/tools/venv/bin/pip install torch onnx onnxscript
native/tools/venv/bin/python native/tools/export_nafnet.py \
  models/ckpt/NAFNet-SIDD-width64.pth models/almaz/nafnet-sidd-512.onnx --arch sidd
native/tools/venv/bin/python native/tools/export_nafnet.py \
  models/ckpt/NAFNet-REDS-width64.pth models/almaz/nafnet-reds-512.onnx --arch reds
```

The first run on macOS compiles each model for Core ML (cached in
`~/Library/Caches/almaz/coreml`).

> The Qualcomm AI Hub NAFNet exports used earlier are **not** used: both blow
> up (outputs in the hundreds) on real photos. Also note the models expect a
> demosaiced image: LibRaw's half-size decode leaves per-pixel independent
> channel noise that the SIDD model has never seen, so the app always decodes
> RAW at full resolution.

## Run and test

```sh
jolt test      # all suites (pure + native; `jolt -M:test pure` for the pure ones)
jolt check     # load every namespace
jolt run       # the app (opens DSC_9852.NEF when present, or files given as args)
```

Batch processing (no window) whenever an option is given:

```sh
jolt run --showSettings DSC_9852.NEF                     # autopilot picks the filters
jolt run --noise strength=60 -o out *.NEF                # adjust what autopilot chose
jolt run --override --sharpen model=classic radius=2 x.jpg   # exactly these filters
jolt run --help                                          # every option
```

Results are written as `<name>-almaz.<ext>` with the input's EXIF; the exit
status is 0 when every file went through, 1 when some failed, 2 for a usage
error.

Interactive development: `jolt nrepl-server`, then from the REPL

```clojure
(require '[glimmer.core :as ui] '[almaz.core :as core] '[almaz.worker :as worker]
         '[almaz.actions :as act])
(worker/start!)
(act/add-images! ["DSC_9852.NEF"])
(ui/run core/app :title "Almaz" :width 1500 :height 960)   ; returns; the REPL stays live
;; after editing: (require 'almaz.ui.sidebar :reload) (ui/reload! core/app)
```

Native libraries can't be reloaded: restart the server after rebuilding one.
`ALMAZ_SCRIPT=file.clj jolt run` loads a file shortly after startup (driving
the UI in smoke tests).

## License

GPL-2.0-or-later (glimmer-bui's BUI kit and the shims are GPL). The NAFNet
weights are MIT licensed (Copyright (c) 2022 megvii-model), with parts from
BasicSR under Apache-2.0; the release ships their license text.
