#ifndef ALMAZINFER_ONNXRUNTIME_H
#define ALMAZINFER_ONNXRUNTIME_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ALMAZ_MAX_RANK 4

/* Dense float tensor. MUST match almazinfer_coreml.h's almz_tensor and the
   ffi/layout in almaz.infer (the struct layout is the wire contract). */
typedef struct almz_tensor {
  const char* name;
  int64_t shape[ALMAZ_MAX_RANK];
  int32_t rank;
  float* data;
  size_t count;
} almz_tensor;

/* Load an ONNX model with the given execution provider ("cpu"; "cuda"/"tensorrt"/
   "openvino" require a GPU-enabled ORT build). Returns an opaque handle or NULL. */
void* almz_ort_load(const char* path, const char* ep, char** err);

/* Run the model. 0 on success. Outputs are written into caller buffers. */
int almz_ort_predict(void* model,
                     int32_t n_in, const almz_tensor* in,
                     int32_t n_out, almz_tensor* out, char** err);

/* Release a model handle. */
void almz_ort_free(void* model);

/* Release a string returned via a char** err out-param. */
void almz_ort_free_string(char* s);

#ifdef __cplusplus
}
#endif

#endif
