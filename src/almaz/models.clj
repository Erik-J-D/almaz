(ns almaz.models
  "The restoration models and their sessions.

   Both are official NAFNet checkpoints (megvii-research, Apache-2.0) exported
   by native/tools/export_nafnet.py to fixed 512x512 ONNX graphs (see README):
     :denoise  NAFNet-SIDD-width64  real sensor noise
     :sharpen  NAFNet-REDS-width64  deblur (motion/defocus, compression)

   One ONNX file per model; the device comes from almaz.infer.ep (Core ML
   through ONNX Runtime on macOS, CUDA/TensorRT/CPU on Linux). Sessions load
   lazily (Core ML compiles a model the first time, then caches it) and are
   shared."
  (:require [almaz.infer.ep :as ep]
            [almaz.infer.onnxruntime :as ort]
            [almaz.infer.protocol :as p]
            [jolt.ffi :as ffi]))

;; libc: the ONNX Runtime shim reads ALMAZ_COREML_CACHE from the environment.
(ffi/defcfn setenv* "setenv" [:string :string :int32] :int32)

(def registry
  {:denoise {:label "NAFNet SIDD" :artifact "models/almaz/nafnet-sidd-512.onnx"
             :inputs ["image"] :outputs ["output"] :block 512 :overlap 64}
   :sharpen {:label "NAFNet REDS" :artifact "models/almaz/nafnet-reds-512.onnx"
             :inputs ["image"] :outputs ["output"] :block 512 :overlap 64}})

(defn available? [id]
  (some-> (get-in registry [id :artifact]) java.io.File. .exists))

(defn- coreml-cache []
  (let [dir (str (System/getenv "HOME") "/Library/Caches/almaz/coreml")]
    (.mkdirs (java.io.File. dir))
    dir))

(defn devices
  "Device specs to try, best first."
  []
  (let [platform (ep/detect-platform)]
    (ep/select-backends platform (if (= platform :macos) {:coreml true} {}))))

(defonce ^:private sessions (atom {}))
(def ^:private load-lock (Object.))

(defn- load-session [id]
  (let [spec (get registry id)]
    (loop [[d & more] (devices) last-error nil]
      (if-not d
        (throw (ex-info (str "cannot load model " id) {:id id} last-error))
        (let [backend (ort/->OnnxruntimeBackend (:ep d))
              r (try {:backend backend :ep (:ep d) :spec spec
                      :session (p/load-model backend (assoc spec :ep (:ep d)))}
                     (catch Exception e e))]
          (if (instance? Exception r) (recur more r) r))))))

(defn session
  "The loaded session for model `id`: {:backend :session :spec :ep}. Loads
   (and on macOS compiles) on first use; later calls return the same one."
  [id]
  (or (get @sessions id)
      (locking load-lock
        (or (get @sessions id)
            (let [s (do (when (= :macos (ep/detect-platform))
                          (setenv* "ALMAZ_COREML_CACHE" (coreml-cache) 0))
                        (load-session id))]
              (swap! sessions assoc id s)
              s)))))

(defn free-all! []
  (doseq [{:keys [backend session]} (vals @sessions)]
    (p/free-session backend session))
  (reset! sessions {}))
