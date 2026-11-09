(ns almaz.ort-test
  "The ONNX Runtime backend against the trivial fixtures in test/almaz/fixtures/.
   Needs native/onnxruntime/build.sh."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.infer.ep :as ep]
            [almaz.infer.onnxruntime :as ort]
            [almaz.infer.protocol :as p]
            [jolt.ffi :as ffi]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-4))
(def in-data (mapv #(/ % 10.0) (range 48)))

(defn- with-session [ep artifact f]
  (let [b (ort/->OnnxruntimeBackend ep)
        s (p/load-model b {:artifact artifact :inputs ["input"] :outputs ["output"] :ep ep})]
    (try (f b s) (finally (p/free-session b s)))))

(deftest vector-path
  (testing "identity round-trips"
    (with-session :cpu "test/almaz/fixtures/identity.onnx"
      (fn [b s]
        (let [out (:data (get (p/run-session b s {"input" {:name "input" :shape [1 3 4 4] :data in-data}}) "output"))]
          (is (= 48 (count out)))
          (is (every? true? (map close? in-data out)))))))
  (testing "add1 adds one"
    (with-session :cpu "test/almaz/fixtures/add1.onnx"
      (fn [b s]
        (let [out (:data (get (p/run-session b s {"input" {:name "input" :shape [1 3 4 4] :data in-data}}) "output"))]
          (is (every? true? (map #(close? (+ %1 1.0) %2) in-data out))))))))

(deftest native-path
  (testing "run-native reads and writes caller-owned buffers, no copies through Clojure"
    (doseq [ep (if (= :macos (ep/detect-platform)) [:cpu :coreml] [:cpu])]
      (with-session ep "test/almaz/fixtures/add1.onnx"
        (fn [b s]
          (let [in (ffi/alloc (* 4 48)) out (ffi/alloc (* 4 48))]
            (ffi/write-array in :float (float-array in-data))
            (p/run-native b s [{:name "input" :shape [1 3 4 4] :ptr in :count 48}]
                          [{:name "output" :ptr out :count 48}])
            (let [o (ffi/read-array out :float 48)]
              (is (every? true? (map #(< (Math/abs (- (+ %1 1.0) %2)) 1e-2) in-data o)) (str ep)))
            (ffi/free in)
            (ffi/free out)))))))

(deftest load-error
  (is (thrown? Exception (p/load-model (ort/->OnnxruntimeBackend :cpu)
                                       {:artifact "nope.onnx" :inputs ["input"] :outputs ["output"]}))))
