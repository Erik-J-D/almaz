(ns almaz.geom-test
  "Orientation, crop rectangles and the display <-> source mapping."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.geom :as g]))

(defn- close? [a b] (< (Math/abs (- (double a) (double b))) 1e-6))
(defn- pt= [[a b] [c d]] (and (close? a c) (close? b d)))
(defn- rect= [r s] (every? true? (map close? r s)))

(deftest orientation-ops
  (testing "four quarter turns come back to upright"
    (is (= 0 (nth (iterate #(g/rotate % 1) 0) 4)))
    (is (= 3 (g/rotate 0 -1))))
  (testing "flipping twice is a no-op, either way"
    (doseq [o (range 8)]
      (is (= o (g/flip-h (g/flip-h o))))
      (is (= o (g/flip-v (g/flip-v o))))))
  (testing "a horizontal then a vertical flip is a half turn"
    (is (= 2 (g/flip-v (g/flip-h 0)))))
  (testing "a quarter turn swaps the sides"
    (is (= [300 400] (g/oriented-size 1 400 300)))
    (is (= [400 300] (g/oriented-size 2 400 300)))
    (is (= [300 400] (g/oriented-size 5 400 300)))))

(deftest orient-mapping
  (let [w 400 h 300]
    (testing "upright is the identity"
      (is (pt= [10 20] (g/apply-xf (g/orient-xf 0 w h) [10 20]))))
    (testing "a clockwise quarter turn puts the top-left corner top-right"
      (is (pt= [300 0] (g/apply-xf (g/orient-xf 1 w h) [0 0])))
      (is (pt= [300 400] (g/apply-xf (g/orient-xf 1 w h) [400 0]))))
    (testing "a mirror puts the left edge on the right"
      (is (pt= [400 0] (g/apply-xf (g/orient-xf 4 w h) [0 0]))))
    (testing "each orientation maps the source rect onto the oriented rect"
      (doseq [o (range 8)
              :let [[ow oh] (g/oriented-size o w h)
                    xf (g/orient-xf o w h)]]
        (is (rect= [0 0 ow oh] (g/xf-rect xf [0 0 w h])) (str "orientation " o))))
    (testing "the display ops agree with the mapping: rotate then flip"
      ;; source top-left after a cw turn is at the top right; a horizontal
      ;; flip of the display brings it to the top left
      (let [o (g/flip-h (g/rotate 0 1))]
        (is (pt= [0 0] (g/apply-xf (g/orient-xf o w h) [0 0])))))))

(deftest affine
  (let [a [2 0 5 0 3 -1]
        b [0 -1 10 1 0 0]]
    (testing "compose applies the right one first"
      (is (pt= (g/apply-xf a (g/apply-xf b [3 4])) (g/apply-xf (g/compose a b) [3 4]))))
    (testing "invert undoes"
      (is (pt= [3 4] (g/apply-xf (g/invert (g/compose a b)) (g/apply-xf (g/compose a b) [3 4])))))))

(def doc {:w 400 :h 300})

(deftest display
  (testing "no crop: the whole image, oriented"
    (is (= [400 300] (g/display-size doc false)))
    (is (= [300 400] (g/display-size (assoc doc :orient 1) false))))
  (testing "cropped: only the crop, unless editing it"
    (let [d (assoc doc :crop-x 100 :crop-y 50 :crop-w 200 :crop-h 100)]
      (is (= [200 100] (g/display-size d false)))
      (is (= [400 300] (g/display-size d true)))
      (testing "display -> source lands in the crop"
        (is (pt= [100 50] (g/apply-xf (g/display->source d false) [0 0])))
        (is (pt= [300 150] (g/apply-xf (g/display->source d false) [200 100]))))
      (testing "and through a quarter turn"
        (let [d (assoc d :orient 1)]
          (is (= [100 200] (g/display-size d false)))
          ;; display top-left is the crop's bottom-left in the source
          (is (pt= [100 150] (g/apply-xf (g/display->source d false) [0 0])))))
      (testing "the crop as the editor shows it"
        (is (rect= [100 50 200 100] (g/display-crop d)))
        (is (rect= [150 100 100 200] (g/display-crop (assoc d :orient 1))))))))

(deftest set-display-crop
  (testing "a display rect is stored back in source pixels"
    (doseq [o (range 8)]
      (let [d (assoc doc :orient o)
            r (g/display-crop (merge d {:crop-x 100 :crop-y 50 :crop-w 200 :crop-h 100}))]
        (is (= {:crop-x 100 :crop-y 50 :crop-w 200 :crop-h 100}
               (select-keys (g/with-display-crop d r) [:crop-x :crop-y :crop-w :crop-h]))
            (str "orientation " o)))))
  (testing "the whole image means no crop"
    (is (nil? (:crop-w (g/with-display-crop doc [0 0 400 300]))))))

(deftest constrain
  (testing "an aspect ratio: the largest such rect around the centre, inside the image"
    (is (rect= [50 0 300 300] (g/fit-aspect [0 0 400 300] 1.0 [400 300])))
    (is (rect= [0 37.5 400 225] (g/fit-aspect [0 0 400 300] (/ 16.0 9) [400 300]))))
  (testing "a fixed size is centred on the crop and kept inside the image"
    (is (rect= [0 0 100 50] (g/fit-size [0 0 60 40] [100 50] [400 300])))
    (testing "too large: scaled down to fit, keeping its shape"
      (is (rect= [0 75 400 150] (g/fit-size [0 0 400 300] [800 300] [400 300]))))))

(deftest drag
  (let [bounds [400 300]
        r [100 100 200 100]]
    (testing "moving keeps the size and stays inside"
      (is (rect= [150 120 200 100] (g/drag-crop r :move [50 20] bounds nil)))
      (is (rect= [200 200 200 100] (g/drag-crop r :move [500 500] bounds nil))))
    (testing "a corner moves two edges"
      (is (rect= [100 100 250 130] (g/drag-crop r :se [50 30] bounds nil))))
    (testing "an edge moves one"
      (is (rect= [80 100 220 100] (g/drag-crop r :w [-20 0] bounds nil))))
    (testing "edges stop at the image"
      (is (rect= [0 100 300 100] (g/drag-crop r :w [-500 0] bounds nil))))
    (testing "and can't cross"
      (let [[_ _ w _] (g/drag-crop r :e [-500 0] bounds nil)]
        (is (>= w g/min-crop))))
    (testing "with an aspect, the corner keeps it"
      (let [[x y w h] (g/drag-crop r :se [100 0] bounds 2.0)]
        (is (close? 2.0 (/ w h)))
        (is (= [100 100] [x y]))
        (is (<= (+ y h) 300))))
    (testing "an aspect-locked rect grows inside the image only"
      (let [[x y w h] (g/drag-crop r :se [400 400] bounds 2.0)]
        (is (close? 2.0 (/ w h)))
        (is (<= (+ x w) 400.0001))
        (is (<= (+ y h) 300.0001))))))

(deftest handles
  (let [r [100 100 200 100]]
    (testing "the handle under a point (display coords, tolerance)"
      (is (= :nw (g/handle-at r [102 98] 8)))
      (is (= :e (g/handle-at r [300 150] 8)))
      (is (= :move (g/handle-at r [200 150] 8)))
      (is (nil? (g/handle-at r [10 10] 8))))))

(deftest constraints
  (let [d (assoc doc :constrain :free)]
    (testing "the menu: free and custom first, aspect presets, then sizes"
      (is (= [:free :custom-size :custom-ratio :original] (take 4 (map :key g/constraints))))
      (is (some #(= "16:9" (:label %)) g/constraints))
      (is (some #(= "1920 × 1080 px" (:label %)) g/constraints)))
    (testing "the aspect each one keeps (display w/h)"
      (is (nil? (g/constraint-aspect d)))
      (is (close? (/ 4.0 3) (g/constraint-aspect (assoc d :constrain :original))))
      (is (close? 0.75 (g/constraint-aspect (assoc d :constrain :original :orient 1))) "turned: portrait")
      (is (close? (/ 16.0 9) (g/constraint-aspect (assoc d :constrain :r16-9))))
      (is (close? 1.5 (g/constraint-aspect (assoc d :constrain :custom-ratio :ratio-w 3.0 :ratio-h 2.0))))
      (is (close? (/ 1920.0 1080) (g/constraint-aspect (assoc d :constrain :s1920x1080))))
      (is (nil? (g/constraint-aspect (assoc d :constrain :custom-size))) "custom size resizes freely"))
    (testing "choosing one fits the crop to it"
      (let [d2 (g/constrain d :square)]
        (is (= :square (:constrain d2)))
        (is (= [300 300] (g/display-size d2 false))))
      (testing "a size preset larger than the image keeps its shape"
        (let [[w h] (g/display-size (g/constrain d :s1920x1080) false)]
          (is (= 400 w))
          (is (< (Math/abs (- (/ w (double h)) (/ 1920.0 1080))) 0.01)))))
    (testing "custom size: an exact display size, centred, inside the image"
      (is (= [120 80] (g/display-size (g/with-crop-size d [120 80]) false)))
      (is (= [400 300] (g/display-size (g/with-crop-size d [900 900]) false))))
    (testing "free leaves the crop alone"
      (let [d2 (g/with-crop-size d [120 80])]
        (is (= (g/crop-rect d2) (g/crop-rect (g/constrain d2 :free))))))))
