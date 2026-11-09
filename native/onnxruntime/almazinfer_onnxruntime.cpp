// almazinfer_onnxruntime.cpp — a thin C++ shim exposing ONNX Runtime (the C++
// API, which goes through OrtGetApiBase under the hood) behind a plain C API so
// jolt.ffi can bind it directly. Mirrors almazinfer_coreml.mm so both backends
// present the same almz_tensor contract.
//
// CPU-only build (brew onnxruntime). To add GPU execution providers, rebuild
// against a CUDA/TensorRT/OpenVINO-enabled ORT and extend the ep switch below
// with the matching OrtSessionOptionsAppendExecutionProvider_* call.
//
// Build (macOS, Homebrew onnxruntime):
//   clang++ -dynamiclib -std=c++17 -O2 -I/opt/homebrew/include/onnxruntime \
//       -o lib/libalmazinfer_onnxruntime.dylib almazinfer_onnxruntime.cpp \
//       -L/opt/homebrew/lib -lonnxruntime
//
// License: GPL-2.0-or-later.

#include <onnxruntime_cxx_api.h>

#include "almazinfer_onnxruntime.h"

#include <cstring>
#include <cstdlib>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

struct AlmzOrtModel {
  Ort::Env env{ORT_LOGGING_LEVEL_WARNING, "almaz"};
  Ort::SessionOptions opts;
  std::unique_ptr<Ort::Session> session;
  std::vector<std::string> input_names;
  std::vector<std::string> output_names;
};

static char* almz_strdup(const char* s) {
  char* out = (char*)malloc(strlen(s) + 1);
  strcpy(out, s);
  return out;
}

void almz_ort_free_string(char* s) {
  free(s);
}

void* almz_ort_load(const char* path, const char* ep, char** err) {
  try {
    auto owned = std::make_unique<AlmzOrtModel>();
    auto* m = owned.get();
    std::string provider = ep ? ep : "cpu";
    m->opts.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
    if (provider == "cpu" || provider.empty()) {
      // CPU is the default execution provider; nothing to append.
    } else if (provider == "coreml") {
#ifdef __APPLE__
      // ORT's built-in CoreML EP: ANE / GPU / CPU chosen by Core ML. Compiled
      // models are cached (ALMAZ_COREML_CACHE) so later loads skip compilation.
      std::unordered_map<std::string, std::string> o{{"ModelFormat", "MLProgram"},
                                                     {"MLComputeUnits", "ALL"}};
      if (const char* cache = getenv("ALMAZ_COREML_CACHE")) o["ModelCacheDirectory"] = cache;
      m->opts.AppendExecutionProvider("CoreML", o);
#else
      if (err) *err = almz_strdup("the CoreML execution provider is macOS-only");
      return nullptr;
#endif
    } else if (provider == "cuda") {
      OrtCUDAProviderOptions o{};
      m->opts.AppendExecutionProvider_CUDA(o);
    } else {
      if (err) *err = almz_strdup(
          ("execution provider '" + provider + "' not compiled into this ONNX Runtime build").c_str());
      return nullptr;
    }

    m->session = std::make_unique<Ort::Session>(m->env, path, m->opts);
    Ort::AllocatorWithDefaultOptions alloc;
    size_t n_in = m->session->GetInputCount();
    for (size_t i = 0; i < n_in; i++) {
      auto n = m->session->GetInputNameAllocated(i, alloc);
      m->input_names.push_back(n.get());
    }
    size_t n_out = m->session->GetOutputCount();
    for (size_t i = 0; i < n_out; i++) {
      auto n = m->session->GetOutputNameAllocated(i, alloc);
      m->output_names.push_back(n.get());
    }
    return owned.release();
  } catch (const std::exception& e) {
    if (err) *err = almz_strdup(e.what());
    return nullptr;
  }
}

void almz_ort_free(void* model) {
  delete static_cast<AlmzOrtModel*>(model);
}

int almz_ort_predict(void* model,
                     int32_t n_in, const almz_tensor* in,
                     int32_t n_out, almz_tensor* out, char** err) {
  try {
    auto* m = static_cast<AlmzOrtModel*>(model);
    Ort::MemoryInfo mem = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);

    std::vector<const char*> in_names;
    in_names.reserve(n_in);
    std::vector<Ort::Value> in_vals;
    in_vals.reserve(n_in);
    for (int32_t i = 0; i < n_in; i++) {
      const almz_tensor* t = &in[i];
      std::vector<int64_t> shape(t->shape, t->shape + t->rank);
      in_names.push_back(t->name);
      in_vals.push_back(Ort::Value::CreateTensor<float>(
          mem, (float*)t->data, t->count, shape.data(), shape.size()));
    }

    std::vector<const char*> out_names;
    out_names.reserve(n_out);
    for (int32_t i = 0; i < n_out; i++) out_names.push_back(out[i].name);

    auto outs = m->session->Run(Ort::RunOptions{nullptr},
                                in_names.data(), in_vals.data(), in_names.size(),
                                out_names.data(), out_names.size());

    for (int32_t i = 0; i < n_out; i++) {
      auto& v = outs[i];
      auto info = v.GetTensorTypeAndShapeInfo();
      size_t count = info.GetElementCount();
      if (out[i].count != 0 && out[i].count < count) {
        if (err) *err = almz_strdup("output buffer too small");
        return -1;
      }
      const float* src = v.GetTensorData<float>();
      memcpy(out[i].data, src, count * sizeof(float));
      out[i].count = count;
      auto shp = info.GetShape();
      out[i].rank = static_cast<int32_t>(shp.size());
      for (size_t r = 0; r < shp.size() && r < ALMAZ_MAX_RANK; r++)
        out[i].shape[r] = shp[r];
    }
    return 0;
  } catch (const Ort::Exception& e) {
    if (err) *err = almz_strdup(e.what());
    return -1;
  }
}
