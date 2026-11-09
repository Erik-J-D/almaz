(ns almaz.infer.onnxruntime
  "ONNX Runtime backend (CPU + Linux GPU execution providers) via our C++ shim
   (native/onnxruntime/lib/libalmazinfer_onnxruntime.dylib).

   Same ONNX graph every backend loads; here it runs on the CPU EP (brew build).
   A GPU-enabled ORT build adds the CUDA/TensorRT/OpenVINO EPs — the shim's ep
   switch is the extension point."
  (:require [jolt.ffi :as ffi]
            [almaz.infer.protocol :as p]
            [almaz.infer.ffi :as a]))

(ffi/defcfn ort-load "almz_ort_load" [:pointer :pointer :pointer] :pointer :blocking)
(ffi/defcfn ort-predict "almz_ort_predict"
  [:pointer :int32 :pointer :int32 :pointer :pointer] :int :blocking)
(ffi/defcfn ort-free "almz_ort_free" [:pointer] :void)
(ffi/defcfn ort-free-string "almz_ort_free_string" [:pointer] :void)

(defn- run-predict
  [handle ins outs tensors]
  (let [in-entries (mapv (fn [n] (select-keys (tensors n) [:name :shape :data])) ins)
        out-count (count (:data (tensors (first ins))))]  ; v1: same-size I/O
    (with-open [ar (ffi/confined-arena)]
      (let [in-arr (a/write-tensor-array! ar in-entries)
            out-arr (a/alloc-output-array! ar outs out-count)
            errslot (ffi/alloc ar :pointer)]
        (ffi/write errslot :pointer ffi/null 0)
        (let [code (ort-predict handle (count ins) in-arr (count outs) out-arr errslot)]
          (if (zero? code)
            (a/read-output-array! out-arr outs)
            (throw (ex-info (or (a/read-and-free-error errslot ort-free-string)
                                "onnxruntime predict failed")
                            {:backend :onnxruntime}))))))))

(defrecord OnnxruntimeBackend [ep]
  p/Backend
  (backend-id  [_] :onnxruntime)
  (available?  [_] true)  ; TODO: gate on the shim dylib being loaded
  (load-model  [_ spec]
    (with-open [ar (ffi/confined-arena)]
      (let [errslot (ffi/alloc ar :pointer)]
        (ffi/write errslot :pointer ffi/null 0)
        (let [h (ort-load (ffi/string->ptr ar (:artifact spec))
                          (ffi/string->ptr ar (name (:ep spec :cpu))) errslot)]
          (if (ffi/null? h)
            (throw (ex-info (or (a/read-and-free-error errslot ort-free-string)
                                "onnxruntime load failed")
                            {:backend :onnxruntime :spec spec}))
            {:backend :onnxruntime :ep (:ep spec :cpu)
             :handle h :inputs (:inputs spec) :outputs (:outputs spec)})))))
  (run-session [_ session tensors]
    (run-predict (:handle session) (:inputs session) (:outputs session) tensors))
  (run-native [_ session inputs outputs]
    (with-open [ar (ffi/confined-arena)]
      (let [in-arr (a/native-tensor-array! ar inputs)
            out-arr (a/native-tensor-array! ar outputs)
            errslot (ffi/alloc ar :pointer)]
        (ffi/write errslot :pointer ffi/null 0)
        (when-not (zero? (ort-predict (:handle session) (count inputs) in-arr (count outputs) out-arr errslot))
          (throw (ex-info (or (a/read-and-free-error errslot ort-free-string) "predict failed")
                          {:backend :onnxruntime}))))))
  (free-session [_ session]
    (when-let [h (:handle session)] (ort-free h))))
