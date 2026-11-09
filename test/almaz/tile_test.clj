(ns almaz.tile-test
  "Tile planning for fixed-size models: windows, neighbour crossfades, and the
   viewport-first processing order."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.infer.tile :as tile]))

(deftest block-bounds
  (testing "covers [0 n) with no gaps"
    (let [b (tile/block-bounds 1000 512 32)]
      (is (= 0 (ffirst b)))
      (is (= 1000 (last (last b))))
      (is (every? (fn [[[_ e1] [s2 _]]] (<= s2 e1)) (map vector b (rest b))))))
  (testing "one window when the block covers everything"
    (is (= [[0 100]] (tile/block-bounds 100 512 32)))))

(deftest fixed-bounds
  (testing "every window is exactly `block` wide and the last ends at n"
    (let [b (tile/fixed-bounds 1000 512 64)]
      (is (every? (fn [[s e]] (= 512 (- e s))) b))
      (is (= 0 (ffirst b)))
      (is (= 1000 (second (last b))))
      (is (every? (fn [[[_ e1] [s2 _]]] (>= (- e1 s2) 64)) (map vector b (rest b)))
          "neighbours overlap by at least `overlap`")))
  (testing "an axis shorter than the block gets one padded window"
    (is (= [[0 512]] (tile/fixed-bounds 300 512 64))))
  (testing "an axis exactly one block long"
    (is (= [[0 512]] (tile/fixed-bounds 512 512 64)))))

(deftest grid
  (let [g (tile/grid 1000 600 512 64)]
    (testing "rows x cols tiles, indexed, all block-sized"
      (is (= 6 (count g)))
      (is (= (range 6) (map :i g)))
      (is (every? #(= 512 (:w %) (:h %)) g)))
    (testing "positions spread evenly, last flush with the edge"
      (is (= [[0 0] [244 0] [488 0] [0 88] [244 88] [488 88]] (map (juxt :x :y) g))))))

(deftest ramps
  (let [g (tile/grid 1000 600 512 64)
        [t0 t1 _ t3 t4] g]
    (testing "nothing done: the tile overwrites, no fades"
      (is (= {:l 0 :t 0 :r 0 :b 0} (tile/ramps g t0 #{}))))
    (testing "fades in over the overlap on each side whose neighbour is done"
      (is (= {:l 268 :t 0 :r 0 :b 0} (tile/ramps g t1 #{0})))
      (is (= {:l 0 :t 0 :r 268 :b 0} (tile/ramps g t0 #{1})))
      (is (= {:l 0 :t 424 :r 0 :b 0} (tile/ramps g t3 #{0})))
      (is (= {:l 268 :t 424 :r 0 :b 0} (tile/ramps g t4 #{1 3}))))))

(deftest focus-order
  (testing "tiles nearest the focus point come first"
    (let [g (tile/grid 2000 2000 512 64)
          o (tile/by-focus g [1999 1999])]
      (is (= (count g) (count o)))
      (is (= (set g) (set o)))
      (is (= (last g) (first o)))
      (is (= (first g) (last o))))))
