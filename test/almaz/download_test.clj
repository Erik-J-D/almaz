(ns almaz.download-test
  "Fetching models: checksums, atomic installs, where models live."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.download :as dl]
            [almaz.models :as models]))

(defn- tmp-dir []
  (let [d (str (or (System/getenv "TMPDIR") "/tmp/") "almaz-dl-" (System/nanoTime))]
    (.mkdirs (java.io.File. d))
    d))

(defn- write-file [path s] (spit path s) path)
(defn- exists? [p] (.exists (java.io.File. p)))

(def abc-sha "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

(deftest sha256
  (let [d (tmp-dir)]
    (is (= abc-sha (dl/sha256 (write-file (str d "/abc") "abc"))))
    (is (= "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
           (dl/sha256 (write-file (str d "/empty") ""))))
    (is (nil? (dl/sha256 (str d "/missing"))))))

(deftest fetching
  (let [d (tmp-dir)
        src (write-file (str d "/src.bin") "abc")
        url (str "file://" src)]
    (testing "a verified download lands at its destination"
      (let [dest (str d "/out/model.onnx")
            seen (atom [])]
        (is (= dest (dl/fetch! {:url url :sha256 abc-sha :dest dest
                                :progress (fn [got total] (swap! seen conj [got total]))})))
        (is (= abc-sha (dl/sha256 dest)))
        (is (not (exists? (str dest ".part"))) "no partial file left behind")))
    (testing "a checksum mismatch leaves nothing at the destination"
      (let [dest (str d "/bad/model.onnx")
            e (try (dl/fetch! {:url url :sha256 (apply str (repeat 64 "0")) :dest dest}) nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= :checksum-mismatch (:reason (ex-data e))))
        (is (not (exists? dest)))
        (is (not (exists? (str dest ".part"))))))
    (testing "an unreachable URL is an error, not a file"
      (let [dest (str d "/none/model.onnx")]
        (is (thrown? clojure.lang.ExceptionInfo
                     (dl/fetch! {:url (str "file://" d "/nope") :sha256 abc-sha :dest dest})))
        (is (not (exists? dest)))))
    (testing "ensure! keeps a file that already verifies, without fetching"
      (let [dest (str d "/out/model.onnx")]
        (is (= dest (dl/ensure! {:url "file:///definitely/not/there" :sha256 abc-sha :dest dest})))))
    (testing "ensure! replaces a corrupt file"
      (let [dest (write-file (str d "/corrupt.onnx") "xyz")]
        (is (= dest (dl/ensure! {:url url :sha256 abc-sha :dest dest})))
        (is (= abc-sha (dl/sha256 dest)))))))

(deftest model-locations
  (testing "an explicit directory wins"
    (is (= "/opt/m" (models/model-dir {:env "/opt/m" :os :macos :home "/Users/a" :dev? true}))))
  (testing "a source checkout uses its models/almaz"
    (is (= "models/almaz" (models/model-dir {:os :macos :home "/Users/a" :dev? true}))))
  (testing "otherwise the platform's per-user data directory"
    (is (= "/Users/a/Library/Application Support/almaz/models"
           (models/model-dir {:os :macos :home "/Users/a"})))
    (is (= "/home/a/.local/share/almaz/models" (models/model-dir {:os :linux :home "/home/a"})))
    (is (= "/x/almaz/models" (models/model-dir {:os :linux :home "/home/a" :xdg "/x"})))))

(deftest registry-is-pinned
  (doseq [[id m] models/registry]
    (is (re-matches #"https://.+" (:url m)) (str id))
    (is (re-matches #"[0-9a-f]{64}" (:sha256 m)) (str id))
    (is (pos? (:size m)) (str id))))
