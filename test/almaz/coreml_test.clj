(ns almaz.coreml-test
  "Our CoreML shim against a trivial MIL-built model
   (test/almaz/fixtures/add1.mlmodelc, output = input + 1.0). Needs
   native/coreml/build.sh."
  (:require [clojure.test :refer [deftest is]]
            [almaz.infer.coreml :as cm]
            [almaz.infer.ep :as ep]
            [almaz.infer.protocol :as p]))

;; Loose tolerance on purpose: CoreML may run this on the ANE in fp16.
(deftest add1
  (if-not (= :macos (ep/detect-platform))
    (println "skipping coreml add1: Core ML is macOS-only")
  (let [b (cm/->CoremlBackend)
        s (p/load-model b {:artifact "test/almaz/fixtures/add1.mlmodelc" :inputs ["x"] :outputs ["add_0"]})
        in-data (mapv #(/ % 10.0) (range 48))]
    (try
      (let [out (:data (get (p/run-session b s {"x" {:name "x" :shape [1 3 4 4] :data in-data}}) "add_0"))]
        (is (= 48 (count out)))
        (is (every? true? (map #(< (Math/abs (- (+ %1 1.0) %2)) 1e-2) in-data out))))
      (finally (p/free-session b s))))))
