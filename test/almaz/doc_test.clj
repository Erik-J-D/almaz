(ns almaz.doc-test
  "The document model over relatum: documents, their filter layers, the
   layers' order, and which ones need (re)processing after an edit."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.doc :as d]))

(def db0 (d/add-doc (d/empty-db) {:path "/x/DSC_1.NEF" :raw? true}))

(defn- kinds [db] (mapv :kind (d/layers db 1)))
(defn- ids [db] (mapv :id (d/layers db 1)))

(deftest documents
  (let [db (d/add-doc db0 {:path "/x/b.jpg" :raw? false})]
    (testing "rows carry their name, status and filmstrip order"
      (is (= "DSC_1.NEF" (:name (d/doc db 1))))
      (is (= :pending (:status (d/doc db 1))))
      (is (true? (:raw (d/doc db 1))))
      (is (= [1 2] (mapv :id (d/docs db)))))
    (testing "the schema rejects bad writes"
      (is (thrown? Exception (d/add-doc db {:path "/x/b.jpg" :raw? false})) "duplicate path")
      (is (thrown? Exception (d/update-doc db 1 assoc :status :bogus)) "unknown status"))
    (testing "removing a document removes its layers"
      (let [[db' _] (d/add-layer db 1 :denoise)
            db' (d/remove-doc db' 1)]
        (is (nil? (d/doc db' 1)))
        (is (= [] (d/layers db' 1)))
        (is (= [2] (mapv :id (d/docs db'))))))))

(deftest add-layer
  (testing "layers keep pipeline order: denoise below sharpen"
    (let [[db _] (d/add-layer db0 1 :sharpen)
          [db _] (d/add-layer db 1 :denoise)]
      (is (= [:denoise :sharpen] (kinds db)))
      (is (apply distinct? (ids db)))))
  (testing "a new layer is enabled, opaque, unmasked, needs processing, has its kind's defaults"
    (let [[db id] (d/add-layer db0 1 :denoise)
          l (d/layer db id)]
      (is (:enabled l))
      (is (= 1.0 (:opacity l)))
      (is (nil? (:done-sig l)))
      (is (= (get-in d/kinds [:denoise :params]) (d/params l)))))
  (testing "a layer must belong to an existing document"
    (is (thrown? Exception (d/add-layer db0 99 :denoise))))
  (testing "opacity stays within [0, 1]"
    (let [[db id] (d/add-layer db0 1 :denoise)]
      (is (thrown? Exception (d/update-layer db id assoc :opacity 1.5))))))

(deftest remove-and-move
  (let [[db a] (d/add-layer db0 1 :denoise)
        [db b] (d/add-layer db 1 :denoise)
        [db c] (d/add-layer db 1 :sharpen)]
    (is (= [b c] (ids (d/remove-layer db a))))
    (testing "layers reorder among their own kind"
      (is (= [b a c] (ids (d/move-layer db a 1))))
      (is (d/can-move? db a 1))
      (is (not (d/can-move? db a -1)) "already at the bottom"))
    (testing "but never across the pipeline order: sharpening noise ruins the result"
      (is (= [a b c] (ids (d/move-layer db b 1))))
      (is (= [a b c] (ids (d/move-layer db c -1))))
      (is (not (d/can-move? db b 1)))
      (is (not (d/can-move? db c -1))))))

(defn- processed
  "Mark every layer of doc 1 as processed against its current signature."
  [db]
  (reduce (fn [acc [l sig]] (d/update-layer acc (:id l) assoc :done-sig sig))
          db (map vector (d/layers db 1) (d/signatures db 1))))

(deftest staleness
  (let [[db a] (d/add-layer db0 1 :denoise)
        [db b] (d/add-layer db 1 :sharpen)
        db (processed db)]
    (testing "fresh after processing"
      (is (nil? (d/next-stale db 1)))
      (is (d/processed? db 1)))
    (testing "a param change re-runs that layer and everything above it"
      (let [y (d/set-param db a :detail 0.3)]
        (is (= 0 (d/next-stale y 1)))
        (is (= [0 1] (d/stale-indices y 1)))))
    (testing "opacity or a mask edit leaves the layer itself valid but re-runs the ones above"
      (is (= [1] (d/stale-indices (d/update-layer db a assoc :opacity 0.5) 1)))
      (is (= [1] (d/stale-indices (d/bump-mask db a) 1))))
    (testing "the top layer's opacity invalidates nothing"
      (is (= [] (d/stale-indices (d/update-layer db b assoc :opacity 0.2) 1))))
    (testing "disabled layers are skipped and don't count as input"
      (let [y (d/update-layer db a assoc :enabled false)]
        (is (= [1] (d/stale-indices y 1)))
        (is (= [1] (d/stale-indices (d/set-param y a :detail 0.9) 1)))))
    (testing "reordering changes every moved layer's input, even between identical layers (their masks differ)"
      (let [[db2 c] (d/add-layer db 1 :denoise)
            db2 (processed db2)]
        (is (= [0 1 2] (d/stale-indices (d/move-layer db2 a 1) 1)))))))

(deftest partial-layers
  (testing "a layer processed only where its mask was set goes stale when the mask changes"
    (let [[db id] (d/add-layer db0 1 :denoise)
          db (processed db)
          partial (d/update-layer db id assoc :partial-rev 0)]
      (is (= [] (d/stale-indices partial 1)) "same mask: still valid")
      (is (= [0] (d/stale-indices (d/bump-mask partial id) 1)) "mask edited: re-run the skipped tiles")
      (is (= [] (d/stale-indices (d/bump-mask db id) 1)) "a fully processed layer stays valid"))))

(deftest layer-lookup
  (let [[db id] (d/add-layer db0 1 :denoise)]
    (is (= :denoise (:kind (d/layer db id))))
    (is (= 0 (d/layer-index db id)))
    (is (nil? (d/layer db 999)))))

(deftest denoised-below
  (let [[db s] (d/add-layer db0 1 :sharpen)
        [db2 n] (d/add-layer db 1 :denoise)]
    (is (not (d/denoised-below? db s)) "nothing under the sharpen layer")
    (is (d/denoised-below? db2 s))
    (is (not (d/denoised-below? (d/update-layer db2 n assoc :enabled false) s)) "a disabled denoise doesn't count")))

(deftest exif-rows
  (let [db (d/set-exif db0 1 {:iso 5000 :fnumber 8.0 :exposure 0.0005})]
    (is (= {:iso 5000 :fnumber 8.0 :exposure 0.0005} (select-keys (d/exif db 1) [:iso :fnumber :exposure])))
    (testing "setting again replaces"
      (is (= 100 (:iso (d/exif (d/set-exif db 1 {:iso 100}) 1)))))
    (testing "goes with its document"
      (is (nil? (d/exif (d/remove-doc db 1) 1))))
    (testing "shot summary"
      (is (= "ISO 5000  1/2000 s  f/8" (d/shot-info (d/exif db 1)))))))

(deftest positions-are-unique-per-document
  (testing "the schema refuses two layers at one position (the bug a silent no-op shift once caused)"
    (let [[db a] (d/add-layer db0 1 :denoise)
          [db b] (d/add-layer db 1 :sharpen)]
      (is (thrown? Exception (d/update-layer db b assoc :position 0)))
      (testing "while adding, moving and removing keep positions distinct"
        (let [[db c] (d/add-layer db 1 :denoise)
              db (d/move-layer db a 1)]
          (is (= 3 (count (set (map :position (d/layers db 1))))))
          (is (= [b] (mapv :id (d/layers (-> db (d/remove-layer a) (d/remove-layer c)) 1)))))))))
