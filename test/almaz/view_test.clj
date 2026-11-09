(ns almaz.view-test
  "Viewport math: fit, zoom about the cursor, pan, coordinate mapping."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.view :as v]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-6))

(def base {:mode :single :cw 1000 :ch 500 :scale 1.0 :ox 0.0 :oy 0.0 :zoom :fit :split 0.5})

(deftest fit
  (testing "the whole image fits inside the margin and is centred"
    (let [vw (v/fit base 4000 1000)
          s (/ (- 1000 (* 2 v/margin)) 4000.0)]
      (is (close? s (:scale vw)))
      (is (close? (/ (- 1000 (* s 4000)) 2) (:ox vw)))
      (is (close? (/ (- 500 (* s 1000)) 2) (:oy vw)))
      (is (= :fit (:zoom vw)))))
  (testing "side-by-side fits each image into half the width"
    (let [vw (v/fit (assoc base :mode :side) 1000 1000)]
      (is (close? (/ (- 500 (* 2 v/margin)) 1000.0) (:scale vw)))
      (is (= 500 (v/pane-width vw))))))

(deftest mapping
  (let [vw (assoc base :scale 2.0 :ox 10.0 :oy -20.0)]
    (testing "screen <-> image round-trips"
      (let [[ix iy] (v/->image vw 110 80)]
        (is (close? 50.0 ix))
        (is (close? 50.0 iy))
        (is (= [110.0 80.0] (mapv double (v/->screen vw ix iy))))))))

(deftest zoom-at
  (testing "the image point under the cursor stays put"
    (let [vw (v/fit base 4000 1000)
          before (v/->image vw 300 200)
          z (v/zoom-at vw 2.0 300 200)]
      (is (close? (* 2 (:scale vw)) (:scale z)))
      (is (= :manual (:zoom z)))
      (let [after (v/->image z 300 200)]
        (is (close? (first before) (first after)))
        (is (close? (second before) (second after))))))
  (testing "scale is clamped"
    (is (close? v/max-scale (:scale (v/zoom-at base 1e6 0 0))))
    (is (close? v/min-scale (:scale (v/zoom-at base 1e-9 0 0))))))

(deftest set-scale
  (testing "100% keeps the pane centre fixed"
    (let [vw (v/fit base 4000 1000)
          c (v/->image vw 500 250)
          z (v/set-scale vw 1.0)]
      (is (close? 1.0 (:scale z)))
      (is (close? (first c) (first (v/->image z 500 250)))))))

(deftest pan
  (let [p (v/pan (assoc base :zoom :fit) 5 -7)]
    (is (close? 5.0 (:ox p)))
    (is (close? -7.0 (:oy p)))
    (is (= :manual (:zoom p)))))

(deftest visible-rect
  (testing "the image region the pane shows, clipped to the image"
    (let [vw (assoc base :scale 1.0 :ox -100.0 :oy 0.0)]
      (is (= [100.0 0.0 900.0 500.0] (mapv double (v/visible-rect vw 1000 800)))))))

(deftest split-hit
  (testing "the split divider is grabbed within a few pixels"
    (let [vw (assoc base :mode :split :split 0.5)]
      (is (v/on-split? vw 503))
      (is (not (v/on-split? vw 540)))
      (is (not (v/on-split? (assoc vw :mode :single) 500))))))
