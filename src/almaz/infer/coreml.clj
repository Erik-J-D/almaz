(ns almaz.infer.coreml
  "CoreML / ANE backend (macOS) via our Objective-C++ shim
   (native/coreml/lib/libalmazinfer_coreml.dylib).

   The shim wraps the Core ML framework behind a plain C API; ONNX -> .mlmodelc
   conversion happens at build time with coremltools (dev-only), and the app
   ships the compiled .mlmodelc."
  (:require [jolt.ffi :as ffi]
            [almaz.infer.protocol :as p]
            [almaz.infer.ffi :as a]))

(ffi/defcfn coreml-load "almz_coreml_load" [:pointer :pointer] :pointer :blocking)
(ffi/defcfn coreml-predict "almz_coreml_predict"
  [:pointer :int32 :pointer :int32 :pointer :pointer] :int :blocking)
(ffi/defcfn coreml-free "almz_coreml_free" [:pointer] :void)
(ffi/defcfn coreml-free-string "almz_free_string" [:pointer] :void)

(defn- run-predict
  "Shared predict: marshal tensors, call the shim, read outputs back."
  [handle ins outs tensors]
  (let [in-entries (mapv (fn [n] (select-keys (tensors n) [:name :shape :data])) ins)
        out-count (count (:data (tensors (first ins))))]  ; v1: same-size I/O
    (with-open [ar (ffi/confined-arena)]
      (let [in-arr (a/write-tensor-array! ar in-entries)
            out-arr (a/alloc-output-array! ar outs out-count)
            errslot (ffi/alloc ar :pointer)]
        (ffi/write errslot :pointer ffi/null 0)
        (let [code (coreml-predict handle (count ins) in-arr (count outs) out-arr errslot)]
          (if (zero? code)
            (a/read-output-array! out-arr outs)
            (throw (ex-info (or (a/read-and-free-error errslot coreml-free-string)
                                "coreml predict failed")
                            {:backend :coreml}))))))))

(defrecord CoremlBackend []
  p/Backend
  (backend-id  [_] :coreml)
  (available?  [_] true)  ; TODO: gate on the shim dylib being loaded
  (load-model  [_ spec]
    (with-open [ar (ffi/confined-arena)]
      (let [errslot (ffi/alloc ar :pointer)]
        (ffi/write errslot :pointer ffi/null 0)
        (let [h (coreml-load (ffi/string->ptr ar (:artifact spec)) errslot)]
          (if (ffi/null? h)
            (throw (ex-info (or (a/read-and-free-error errslot coreml-free-string)
                                "coreml load failed")
                            {:backend :coreml :spec spec}))
            {:backend :coreml :handle h :inputs (:inputs spec) :outputs (:outputs spec)})))))
  (run-session [_ session tensors]
    (run-predict (:handle session) (:inputs session) (:outputs session) tensors))
  (run-native [_ session inputs outputs]
    (with-open [ar (ffi/confined-arena)]
      (let [in-arr (a/native-tensor-array! ar inputs)
            out-arr (a/native-tensor-array! ar outputs)
            errslot (ffi/alloc ar :pointer)]
        (ffi/write errslot :pointer ffi/null 0)
        (when-not (zero? (coreml-predict (:handle session) (count inputs) in-arr (count outputs) out-arr errslot))
          (throw (ex-info (or (a/read-and-free-error errslot coreml-free-string) "predict failed")
                          {:backend :coreml}))))))
  (free-session [_ session]
    (when-let [h (:handle session)] (coreml-free h))))
