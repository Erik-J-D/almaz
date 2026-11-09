// almazinfer_coreml.mm — a thin ObjC++ shim exposing the Core ML framework
// (and thus the ANE) behind a plain C API so jolt.ffi can bind it directly.
//
// Build (macOS, Xcode toolchain):
//   clang++ -fobjc-arc -dynamiclib -std=c++17 \
//       -o lib/libalmazinfer_coreml.dylib almazinfer_coreml.mm \
//       -framework Foundation -framework CoreML
//
// License: GPL-2.0-or-later.

#import <Foundation/Foundation.h>
#import <CoreML/CoreML.h>

#include "almazinfer_coreml.h"

#include <stdlib.h>
#include <string.h>

static char* almz_err(NSError* e) {
  if (!e) return NULL;
  NSString* s = [e localizedDescription];
  const char* c = [s UTF8String];
  char* out = (char*)malloc(strlen(c) + 1);
  strcpy(out, c);
  return out;
}

void almz_free_string(char* s) {
  free(s);
}

void* almz_coreml_load(const char* path, char** err) {
  @autoreleasepool {
    NSString* p = [NSString stringWithUTF8String:path];
    NSURL* url = [NSURL fileURLWithPath:p];
    NSError* e = nil;
    MLModel* model = [MLModel modelWithContentsOfURL:url error:&e];
    if (!model) {
      if (err) *err = almz_err(e);
      return NULL;
    }
    return (__bridge_retained void*)model;   // caller owns one +1 reference
  }
}

void almz_coreml_free(void* model) {
  MLModel* m = (__bridge_transfer MLModel*)model;   // consumes the +1 reference
  (void)m;                                          // released at end of scope
}

static MLMultiArray* make_input(const almz_tensor* t, char** err) {
  NSMutableArray* shape = [NSMutableArray arrayWithCapacity:t->rank];
  size_t count = 1;
  for (int32_t r = 0; r < t->rank; r++) {
    [shape addObject:@(t->shape[r])];
    count *= (size_t)t->shape[r];
  }
  NSError* e = nil;
  // Let CoreML allocate a contiguous buffer, then copy the caller's data in.
  // (initWithDataPointer:strides: is stricter than documented about strides,
  //  so we avoid it for inputs.)
  MLMultiArray* ma = [[MLMultiArray alloc] initWithShape:shape
                                                dataType:MLMultiArrayDataTypeFloat32
                                                   error:&e];
  if (!ma) {
    if (err) *err = almz_err(e);
    return nil;
  }
  memcpy(ma.dataPointer, t->data, count * sizeof(float));
  return ma;
}

int almz_coreml_predict(void* model,
                        int32_t n_in, const almz_tensor* in,
                        int32_t n_out, almz_tensor* out, char** err) {
  @autoreleasepool {
    MLModel* m = (__bridge MLModel*)model;

    NSMutableDictionary* dict = [NSMutableDictionary dictionaryWithCapacity:n_in];
    for (int32_t i = 0; i < n_in; i++) {
      MLMultiArray* ma = make_input(&in[i], err);
      if (!ma) return -1;
      NSString* name = [NSString stringWithUTF8String:in[i].name];
      dict[name] = ma;
    }

    NSError* pe = nil;
    MLDictionaryFeatureProvider* inputs =
      [[MLDictionaryFeatureProvider alloc] initWithDictionary:dict error:&pe];
    if (!inputs) { if (err) *err = almz_err(pe); return -1; }

    id<MLFeatureProvider> outputs = [m predictionFromFeatures:inputs error:&pe];
    if (!outputs) { if (err) *err = almz_err(pe); return -1; }

    for (int32_t i = 0; i < n_out; i++) {
      NSString* name = [NSString stringWithUTF8String:out[i].name];
      MLFeatureValue* fv = [outputs featureValueForName:name];
      MLMultiArray* ma = fv.multiArrayValue;
      if (!ma) {
        if (err) {
          char* n = (char*)malloc(strlen(out[i].name) + 64);
          snprintf(n, strlen(out[i].name) + 64, "output '%s' is not a multi-array", out[i].name);
          *err = n;
        }
        return -1;
      }
      NSUInteger count = ma.count;
      if (out[i].count != 0 && out[i].count < count) {
        if (err) {
          char* n = (char*)malloc(strlen(out[i].name) + 80);
          snprintf(n, strlen(out[i].name) + 80, "output '%s' buffer too small (%zu < %zu)",
                   out[i].name, (size_t)out[i].count, (size_t)count);
          *err = n;
        }
        return -1;
      }
      const void* src = ma.dataPointer;
      if (!src) {
        if (err) {
          char* n = (char*)malloc(strlen(out[i].name) + 64);
          snprintf(n, strlen(out[i].name) + 64, "output '%s' has no dataPointer", out[i].name);
          *err = n;
        }
        return -1;
      }
      memcpy(out[i].data, src, count * sizeof(float));
      out[i].count = count;
    }
    return 0;
  }
}
