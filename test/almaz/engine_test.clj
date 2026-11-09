(ns almaz.engine-test
  "The tiled processing engine: a layer's filter over the composite below it."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.engine :as eng]
            [almaz.models :as models]
            [almaz.pixels :as px]
            [jolt.ffi :as ffi]))

(defn- filled [w h f]
  (let [img (px/new-image w h) data (float-array (* w h 3))]
    (dotimes [y h]
      (dotimes [x w]
        (let [i (* 3 (+ (* y w) x)) [r g b] (f x y)]
          (aset data i (float r)) (aset data (inc i) (float g)) (aset data (+ i 2) (float b)))))
    (px/set-pixels! img data)
    img))

(defn- gauss [^java.util.Random rng sigma]
  (let [u1 (max 1e-12 (.nextDouble rng)) u2 (.nextDouble rng)]
    (* sigma (Math/sqrt (* -2.0 (Math/log u1))) (Math/cos (* 2.0 Math/PI u2)))))

(defn- max-diff [a b w h]
  (apply max (for [y (range 0 h 5) x (range 0 w 5)]
               (apply max (map #(Math/abs (- %1 %2)) (px/pixel a x y) (px/pixel b x y))))))

(def identity-op
  {:block 128 :overlap 32
   :run (fn [in out n] (ffi/copy in out (* 4 n)))})

(deftest identity-filter
  (testing "an identity filter reproduces the composite below, seamlessly"
    (let [w 333 h 250
          base (filled w h (fn [x y] [(/ x (double w)) (/ y (double h)) 0.5]))
          over (filled w h (constantly [1 0 0]))
          below [{:img over :opacity 0.5 :enabled true}]
          expect (px/new-image w h)
          out (px/new-image w h)
          seen (atom [])]
      (px/composite-into! expect base below)
      (is (= :done (eng/process! {:base base :below below :out out :op identity-op
                                  :focus [0 0] :progress #(swap! seen conj [%1 %2])})))
      (is (< (max-diff expect out w h) 1e-4))
      (testing "progress counts every tile"
        (is (= (count @seen) (second (last @seen))))
        (is (= (map first @seen) (range 1 (inc (count @seen))))))
      (doseq [i [base over expect out]] (px/free-image! i)))))

(deftest cancellation
  (let [base (filled 400 400 (constantly [0.5 0.5 0.5]))
        out (px/new-image 400 400)
        n (atom 0)]
    (is (= :cancelled (eng/process! {:base base :below [] :out out :op identity-op :focus [0 0]
                                     :progress (fn [_ _] (swap! n inc))
                                     :cancelled? #(>= @n 2)})))
    (is (= 2 @n))
    (px/free-image! base)
    (px/free-image! out)))

(deftest classic-sharpen
  (testing "unsharp leaves flat areas alone and steepens edges"
    (let [base (filled 200 200 (fn [x _] (if (< x 100) [0.3 0.3 0.3] [0.7 0.7 0.7])))
          out (px/new-image 200 200)]
      (eng/process! {:base base :below [] :out out :focus [0 0]
                     :op (eng/op-for {:kind :sharpen :model :classic :amount 1.0 :radius 2.0} nil)})
      (is (< (Math/abs (- 0.3 (first (px/pixel out 20 100)))) 1e-3))
      (is (< (first (px/pixel out 98 100)) 0.3) "undershoot on the dark side of the edge")
      (is (> (first (px/pixel out 101 100)) 0.7) "overshoot on the bright side")
      (px/free-image! base)
      (px/free-image! out))))

(deftest ai-denoise
  (if-not (models/available? :denoise)
    (println "skipping ai-denoise: the denoise model is not downloaded (the app fetches it on first use)")
    (testing "NAFNet removes most of the noise of a noisy gradient"
      (let [rng (java.util.Random. 3) w 600 h 400
            base (filled w h (fn [x y] (let [v (+ 0.25 (* 0.5 (/ x (double w))))]
                                         [(+ v (gauss rng 0.04)) (+ v (gauss rng 0.04)) (+ v (gauss rng 0.04))])))
            out (px/new-image w h)
            before (:noise (px/estimate base))]
        (is (= :done (eng/process! {:base base :below [] :out out :focus [0 0]
                                    :op (eng/op-for {:kind :denoise :model :ai :detail 0.0}
                                                    (models/session :denoise))})))
        (let [after (:noise (px/estimate out))]
          (is (< after (/ before 4)) (str "noise " before " -> " after)))
        (testing "and keeps the image: the mean level is unchanged"
          (is (< (Math/abs (- (first (px/pixel base 300 200)) (first (px/pixel out 300 200)))) 0.06)))
        (px/free-image! base)
        (px/free-image! out)))))

(deftest skips-masked-out-tiles
  (testing "tiles where the layer's mask is empty are not run, and the result says so"
    (let [base (filled 600 300 (constantly [0.5 0.5 0.5]))
          out (px/new-image 600 300)
          mask (px/new-mask 600 300 4 0.0)
          runs (atom 0)
          op (update identity-op :run (fn [f] (fn [& a] (swap! runs inc) (apply f a))))]
      (px/stroke! mask [60 150] [60 150] {:radius 40 :hardness 0.9})
      (is (= :partial (eng/process! {:base base :below [] :out out :op op :focus [0 0] :mask mask})))
      (is (< 0 @runs 15) (str @runs " of " 15 " tiles ran"))
      (px/free-mask! mask)
      (px/free-image! base)
      (px/free-image! out))))
