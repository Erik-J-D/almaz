(ns almaz.check
  "Load-check every namespace, the UI included (needs the BUI dylib and the
   native shims built)."
  (:require [almaz.infer.protocol]
            [almaz.infer.ep]
            [almaz.infer.tile]
            [almaz.infer.ffi]
            [almaz.infer.coreml]
            [almaz.infer.onnxruntime]
            [almaz.core]))

(defn -main [& _]
  (println "almaz: all namespaces load"))
