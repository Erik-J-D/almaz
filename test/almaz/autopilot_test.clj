(ns almaz.autopilot-test
  "Autopilot: image estimates -> which filters to add and how strong."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.autopilot :as ap]))

(deftest suggestions
  (testing "a clean, sharp image needs nothing"
    (is (= [] (mapv :kind (ap/suggest {:noise 0.002 :sharpness 0.9})))))
  (testing "visible noise adds denoise, stronger for more noise"
    (let [lo (first (ap/suggest {:noise 0.012 :sharpness 0.9}))
          hi (first (ap/suggest {:noise 0.04 :sharpness 0.9}))]
      (is (= :denoise (:kind lo) (:kind hi)))
      (is (< (:opacity lo) (:opacity hi)))
      (is (<= (:opacity hi) 1.0))))
  (testing "soft images add sharpen after denoise"
    (is (= [:denoise :sharpen] (mapv :kind (ap/suggest {:noise 0.03 :sharpness 0.1}))))
    (is (= [:sharpen] (mapv :kind (ap/suggest {:noise 0.001 :sharpness 0.1})))))
  (testing "strengths are in [0.2, 1]"
    (doseq [s (ap/suggest {:noise 0.5 :sharpness 0.0})]
      (is (<= 0.2 (:opacity s) 1.0)))))

(deftest describe
  (is (string? (ap/describe {:noise 0.03 :sharpness 0.2}))))

(deftest noisy
  (is (ap/noisy? {:noise 0.02}))
  (is (not (ap/noisy? {:noise 0.001})))
  (is (not (ap/noisy? nil))))
