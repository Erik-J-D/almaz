(ns almaz.infer.ep
  "Device / execution-provider selection.

   Pure — takes a platform keyword and a capability probe and returns an ordered
   list of device specs (best first). The actual hardware probe (does this Mac
   have ANE? does this box have CUDA? OpenCL?) is done elsewhere and passed in,
   so this is fully testable without hardware.

   A device spec is a map the feature code hands to the backend loader:

     {:backend :coreml}
     {:backend :onnxruntime :ep :cuda}
     {:backend :onnxruntime :ep :cpu}")

(def default-caps
  "Every capability is false until the host probe says otherwise; CPU is always
   available and is appended as the last resort, not stored here.
   :coreml is ONNX Runtime's CoreML execution provider (ANE/GPU, macOS);
   :ane our own CoreML shim, which needs a precompiled .mlmodelc."
  {:coreml false :ane false :cuda false :tensorrt false :opencl false})

(defn select-backends
  "Ordered device specs for `platform` (:macos | :linux) given a caps map.
   CPU is always the final fallback. macOS prefers Core ML (through ONNX
   Runtime's execution provider); Linux prefers a GPU execution provider
   (CUDA > TensorRT > OpenCL) over CPU."
  [platform caps]
  (let [caps (merge default-caps caps)]
    (case platform
      :macos (cond-> []
               (:coreml caps)  (conj {:backend :onnxruntime :ep :coreml})
               true            (conj {:backend :onnxruntime :ep :cpu}))
      :linux (cond-> []
               (:cuda caps)    (conj {:backend :onnxruntime :ep :cuda})
               (:tensorrt caps)(conj {:backend :onnxruntime :ep :tensorrt})
               (:opencl caps)  (conj {:backend :onnxruntime :ep :opencl})
               true            (conj {:backend :onnxruntime :ep :cpu}))
      [{:backend :onnxruntime :ep :cpu}])))

(defn best
  "The first device spec in the ordered list."
  [platform caps]
  (first (select-backends platform caps)))

(defn detect-platform
  "The current platform: :macos, :linux or :other. ALMAZ_PLATFORM overrides."
  []
  (or (some-> (System/getenv "ALMAZ_PLATFORM") keyword)
      (let [os (str (System/getProperty "os.name"))]
        (cond (re-find #"(?i)mac|darwin" os) :macos
              (re-find #"(?i)linux" os) :linux
              :else :other))))
