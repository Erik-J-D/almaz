(ns almaz.history-test
  "Undo/redo: snapshots of a document's edits, restoring them, and the
   bounded stack with coalescing."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.doc :as d]
            [almaz.history :as h]))

(def db0 (-> (d/empty-db) (d/add-doc {:path "/x/a.NEF" :raw? true}) (d/update-doc 1 assoc :w 400 :h 300)))

(defn- with-layer [db kind] (d/ensure-layer db 1 kind))

(deftest snapshot-and-restore
  (let [[db lid] (with-layer db0 :hsl)
        snap (h/snapshot db 1)
        edited (-> db
                   (d/set-param lid :hs-sat 0.5)
                   (d/update-layer lid assoc :opacity 0.4)
                   (d/update-doc 1 assoc :orient 1 :crop-x 10 :crop-y 10 :crop-w 100 :crop-h 50))]
    (testing "restoring puts settings, strength and geometry back"
      (let [back (h/restore edited 1 snap)]
        (is (= 0.0 (:hs-sat (d/layer back lid))))
        (is (= 1.0 (:opacity (d/layer back lid))))
        (is (= [0 nil] [(:orient (d/doc back 1)) (:crop-w (d/doc back 1))]))))
    (testing "but not native handles or processing state"
      (let [busy (d/update-layer edited lid assoc :img 1234 :done-sig "x")
            back (h/restore busy 1 snap)]
        (is (= 1234 (:img (d/layer back lid))))
        (is (= "x" (:done-sig (d/layer back lid))))))
    (testing "a layer made after the snapshot is switched off, not removed"
      (let [[db2 sid] (with-layer db :sharpen)
            back (h/restore db2 1 snap)]
        (is (some? (d/layer back sid)))
        (is (false? (:enabled (d/layer back sid))))))))

(deftest stack
  (let [s (fn [n] {:state n})
        hs (-> h/empty
               (h/record (s 1) {:key [:a] :at 0} 3)
               first
               (h/record (s 2) {:key [:b] :at 10} 3)
               first)]
    (testing "undo gives the last entry back and keeps the current state for redo"
      (let [[hs2 e] (h/undo hs (s :now))]
        (is (= 2 (:state e)))
        (is (h/can-redo? hs2))
        (let [[hs3 r] (h/redo hs2 (s :back))]
          (is (= :now (:state r)))
          (is (= :back (:state (second (h/undo hs3 (s :x))))) "undoing the redo"))))
    (testing "the same key soon after is the same step (a slider drag)"
      (let [[hs2 dropped] (h/record hs (s 3) {:key [:b] :at 500} 3)]
        (is (= hs hs2))
        (is (= [(s 3)] dropped) "the unneeded snapshot comes back to be freed")))
    (testing "but not after a pause"
      (is (= 3 (count (:undo (first (h/record hs (s 3) {:key [:b] :at 5000} 3))))))
      (is (= 3 (count (:undo (first (h/record hs (s 3) {:key nil :at 11} 3))))) "nil keys never merge"))
    (testing "a new edit drops the redo entries"
      (let [[hs2] (h/undo hs (s :now))
            [hs3 dropped] (h/record hs2 (s 9) {:key [:c] :at 100} 3)]
        (is (not (h/can-redo? hs3)))
        (is (= [(s :now)] dropped))))
    (testing "at most `limit` steps: the oldest go"
      (let [[hs2 dropped] (reduce (fn [[acc dr] i] (let [[a d] (h/record acc (s i) {:key [i] :at (* 10000 i)} 3)] [a (into dr d)]))
                                  [h/empty []] (range 5))]
        (is (= [2 3 4] (mapv :state (:undo hs2))))
        (is (= [0 1] (mapv :state dropped)))))
    (testing "nothing to undo"
      (is (nil? (h/undo h/empty (s :now)))))))
