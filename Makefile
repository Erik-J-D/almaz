# macOS build. Assumes jolt and Homebrew; fetches and builds everything else.
# Third-party sources go in .deps/, pinned to the commits CI uses.
SHELL := /bin/bash
DEPS := $(CURDIR)/.deps
LIBRAW := $(DEPS)/libraw
BUI := $(DEPS)/bui
LIBRAW_SHA := 0b87f5c907d7ee0e7641dd1b6a6c9140b9451922
BUI_SHA := 79be8e30bbbc28c8224274d342081e8f522d34cf
BREW_PKGS := cmake jpeg-turbo libpng libtiff onnxruntime
JOBS := $(shell sysctl -n hw.ncpu)

# glimmer-bui dlopens libbui.dylib by bare name. Call aliases (-M:x), not
# tasks: tasks relaunch jolt via a shell, and SIP strips DYLD_* there.
JOLT := DYLD_LIBRARY_PATH=$(BUI)/lib jolt

SHIMS := native/img/lib/libalmazimg.dylib \
         native/onnxruntime/lib/libalmazinfer_onnxruntime.dylib \
         native/raw/lib/libalmazinfer_raw.dylib \
         native/coreml/lib/libalmazinfer_coreml.dylib

.PHONY: all brew native run test check samples clean distclean

all: native

brew: $(DEPS)/brew.stamp

$(DEPS)/brew.stamp:
	@mkdir -p $(DEPS)
	for p in $(BREW_PKGS); do brew list --versions $$p >/dev/null || brew install $$p; done
	touch $@

# shallow clone of one commit: $(call fetch,url,dir,sha)
define fetch
	rm -rf $(2) && git init -q $(2)
	git -C $(2) fetch -q --depth 1 $(1) $(3)
	git -C $(2) checkout -q FETCH_HEAD
endef

$(LIBRAW)/lib/libraw_r.a:
	$(call fetch,https://github.com/LibRaw/LibRaw,$(LIBRAW),$(LIBRAW_SHA))
	$(MAKE) -C $(LIBRAW) -f Makefile.dist -j$(JOBS) library

$(BUI)/lib/libbui.dylib: $(DEPS)/brew.stamp
	$(call fetch,https://github.com/ppmpreetham/BUI,$(BUI),$(BUI_SHA))
	cmake -S $(BUI) -B $(BUI)/build -DCMAKE_BUILD_TYPE=Release
	cmake --build $(BUI)/build --parallel
	mkdir -p $(BUI)/lib
	c++ -shared -o $@ \
	  -force_load $(BUI)/build/kit/libbui_kit.a \
	  -force_load $(BUI)/build/gpu/libbui_gpu.a \
	  -force_load $(BUI)/build/core/libbui_core.a \
	  -framework Cocoa -framework IOKit -framework CoreVideo \
	  -framework AVFoundation -framework CoreMedia -lm -lpthread

native: $(BUI)/lib/libbui.dylib $(SHIMS)

native/img/lib/libalmazimg.dylib: $(wildcard native/img/*.[chm]) $(DEPS)/brew.stamp
	native/img/build.sh

native/onnxruntime/lib/libalmazinfer_onnxruntime.dylib: $(wildcard native/onnxruntime/*.cpp native/onnxruntime/*.h) $(DEPS)/brew.stamp
	native/onnxruntime/build.sh

native/raw/lib/libalmazinfer_raw.dylib: $(wildcard native/raw/*.cpp native/raw/*.h) $(LIBRAW)/lib/libraw_r.a
	LIBRAW_DIR=$(LIBRAW) native/raw/build.sh

native/coreml/lib/libalmazinfer_coreml.dylib: $(wildcard native/coreml/*.mm native/coreml/*.h)
	native/coreml/build.sh

# make run ARGS="--help"
run: native
	$(JOLT) -M:run $(ARGS)

test: native
	$(JOLT) -M:test

check: native
	$(JOLT) -M:check

samples:
	native/tools/fetch_samples.sh

clean:
	rm -rf native/*/lib

distclean: clean
	rm -rf $(DEPS)
