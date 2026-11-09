#ifndef ALMAZINFER_COREML_H
#define ALMAZINFER_COREML_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ALMAZ_MAX_RANK 4

/* A dense float tensor. `data` is a caller-owned buffer of `count` floats.
   For inputs the shim reads it; for outputs the shim writes it. */
typedef struct almz_tensor {
  const char* name;
  int64_t shape[ALMAZ_MAX_RANK];
  int32_t rank;
  float* data;
  size_t count;
} almz_tensor;

/* Load a compiled CoreML model (.mlmodelc directory). Returns an opaque handle,
   or NULL on failure (then *err is a malloc'd string; free with almz_free_string). */
void* almz_coreml_load(const char* path, char** err);

/* Run the model. Returns 0 on success, non-zero on error. */
int almz_coreml_predict(void* model,
                        int32_t n_in, const almz_tensor* in,
                        int32_t n_out, almz_tensor* out, char** err);

/* Release a model handle returned by almz_coreml_load. */
void almz_coreml_free(void* model);

/* Release a string returned via a `char** err` out-param. */
void almz_free_string(char* s);

#ifdef __cplusplus
}
#endif

#endif
