(ns almaz.ep-test
  "Device / execution-provider selection."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.infer.ep :as ep]))

(deftest macos
  (testing "the CoreML execution provider first, CPU last"
    (let [s (ep/select-backends :macos {:coreml true})]
      (is (= {:backend :onnxruntime :ep :coreml} (first s)))
      (is (= :cpu (:ep (last s))))))
  (testing "no CoreML: straight to the CPU"
    (is (= [{:backend :onnxruntime :ep :cpu}] (ep/select-backends :macos {})))))

(deftest linux
  (testing "CUDA > TensorRT > OpenCL > CPU"
    (is (= [:cuda :tensorrt :cpu] (mapv :ep (ep/select-backends :linux {:cuda true :tensorrt true}))))
    (is (= [:tensorrt :cpu] (mapv :ep (ep/select-backends :linux {:tensorrt true}))))
    (is (= [:cpu] (mapv :ep (ep/select-backends :linux {}))))))
