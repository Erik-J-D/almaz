(ns almaz.doc
  "The document model, held in a relatum database (passed in, returned new).

   A document is one image; its filter layers reference it (deleting the
   document deletes them). Each layer runs its filter over the composite of
   the layers below it and blends its result back over that composite through
   its mask and opacity, the way photo editors stack adjustment layers:

     composite_0 = base
     composite_i = composite_{i-1} + (filter_i(composite_{i-1}) - composite_{i-1})
                                     * opacity_i * mask_i

   Native buffers (the decoded image, the preview, the thumbnail, each layer's
   filter output and mask) are stored as pointer columns; almaz.lifecycle owns
   them.

   Which layers need (re)processing is derived, not tracked: a layer's
   signature is its kind and params plus the signature of the composite it
   reads, and a layer is stale when its :done-sig differs. Editing a layer's
   params invalidates it and everything above; its opacity, mask or feather
   only change its contribution, so they invalidate the layers above but not
   the layer itself."
  (:require [clojure.string :as str]
            [relatum.core :as rc]
            [relatum.schema :as s]))

(def statuses #{:pending :loading :thumb :ready :error})

(s/create-table :doc
  [[:id          :int     :primary :key :auto]
   [:path        :text    :unique]
   [:name        :text]
   [:raw         :bool]
   [:position    :int]                      ; filmstrip order
   [:status      :keyword :check [contains? #{:pending :loading :thumb :ready :error} :status]]
   [:error       :text    :nullable]
   [:w           :int     :nullable]
   [:h           :int     :nullable]
   [:edited-at   :int     :default 0]       ; ms; processing waits for edits to settle
   [:autopiloted :bool    :default false]
   [:noise       :real    :nullable]        ; estimates (almaz.autopilot)
   [:sharpness   :real    :nullable]
   [:base        :int     :nullable]        ; native handles
   [:preview     :int     :nullable]
   [:thumb       :int     :nullable]
   [:thumb-rev   :int     :default 0]])

(s/create-table :layer
  [[:id          :int     :primary :key :auto]
   [:doc-id      :int     :references :doc :on-delete :cascade]
   [:position    :int]                      ; stack order, bottom first
   [:kind        :keyword :check [contains? #{:denoise :sharpen} :kind]]
   [:enabled     :bool    :default true]
   [:opacity     :real    :default 1.0 :check [:and [>= :opacity 0.0] [<= :opacity 1.0]]]
   [:model       :keyword :check [contains? #{:ai :classic} :model]]
   [:detail      :real    :default 0.0]
   [:amount      :real    :default 1.0]
   [:radius      :real    :default 1.5]
   [:feather     :real    :default 24.0]
   [:mask-rev    :int     :default 0]
   [:done-sig    :text    :nullable]        ; the signature its output matches
   [:partial-rev :int     :nullable]        ; mask-rev of a run that skipped tiles
   [:img         :int     :nullable]        ; native handles
   [:mask        :int     :nullable]]
  ;; one layer per position in a document's stack
  :unique [[:doc-id :position]])

;; the source photo's shooting metadata, carried into exports
(s/create-table :exif
  [[:doc-id      :int     :primary :key :references :doc :on-delete :cascade]
   [:make        :text    :nullable]
   [:model       :text    :nullable]
   [:lens        :text    :nullable]
   [:taken       :text    :nullable]       ; EXIF "YYYY:MM:DD HH:MM:SS"
   [:exposure    :real    :nullable]       ; seconds
   [:fnumber     :real    :nullable]
   [:focal       :real    :nullable]       ; mm
   [:iso         :int     :nullable]
   [:focal35     :int     :nullable]
   [:orientation :int     :nullable]])

;; a document's layers in stack order (rc/children)
(s/create-index :layer [:doc-id :position] :type :btree)

(def kinds
  "Filter kinds, in pipeline order (denoise before sharpen: sharpening noise
   amplifies it). :params are the defaults."
  {:denoise {:label "Denoise" :order 0 :params {:model :ai :detail 0.0 :amount 1.0 :radius 1.5}}
   :sharpen {:label "Sharpen" :order 1 :params {:model :ai :detail 0.0 :amount 1.0 :radius 1.5}}})

(def param-keys [:model :detail :amount :radius])

(defn params [layer] (select-keys layer param-keys))

(defn empty-db [] (rc/create-db))

;; ---- documents ---------------------------------------------------------------
(defn doc [db id] (rc/row db :doc id))

(defn docs
  "Every document, in filmstrip order."
  [db]
  (rc/q db [[:from :doc]] {:sort [:position]}))

(defn add-doc
  "Add an image file; its id is assigned (rc/next-id tells which)."
  [db {:keys [path raw?]}]
  (rc/insert db :doc {:path path :name (last (str/split path #"/"))
                      :raw (boolean raw?) :status :pending
                      :position (count (rc/rows db :doc))}))

(defn update-doc [db id f & args]
  (apply rc/update db :doc [= :id id] f args))

(defn remove-doc
  "Remove a document; its layers go with it."
  [db id]
  (rc/delete db :doc [= :id id]))

;; ---- layers ------------------------------------------------------------------
(defn layer [db id] (rc/row db :layer id))

(defn layers
  "A document's layers, bottom of the stack first."
  [db doc-id]
  (rc/children db :layer/by-doc-id+position doc-id))

(defn layer-index [db id]
  (when-let [l (layer db id)]
    (first (keep-indexed (fn [i x] (when (= id (:id x)) i)) (layers db (:doc-id l))))))

(defn add-layer
  "Add a `kind` layer just above the topmost layer that comes before it in
   the pipeline (denoise before sharpen), or at the bottom. Returns
   [db layer-id]."
  [db doc-id kind]
  (let [id (rc/next-id db :layer)
        o (get-in kinds [kind :order])
        pos (inc (reduce max -1 (keep-indexed (fn [i l] (when (<= (get-in kinds [(:kind l) :order]) o) i))
                                              (layers db doc-id))))
        db (rc/update db :layer [:and [= :doc-id doc-id] [>= :position pos]] update :position inc)]
    [(rc/insert db :layer (merge (get-in kinds [kind :params])
                                 {:doc-id doc-id :kind kind :position pos}))
     id]))

(defn update-layer [db id f & args]
  (apply rc/update db :layer [= :id id] f args))

(defn set-param [db id k v] (update-layer db id assoc k v))

(defn remove-layer [db id]
  (rc/delete db :layer [= :id id]))

(defn- move-target
  "The layer `id` would swap with moving `dir` places, or nil: moves stay
   among layers of the same kind, so the pipeline order (denoise below
   sharpen) holds. Sharpening a noisy image amplifies the noise into
   artifacts that no later denoise removes."
  [db id dir]
  (when-let [l (layer db id)]
    (let [ls (layers db (:doc-id l))
          j (+ (layer-index db id) dir)]
      (when (< -1 j (count ls))
        (let [other (nth ls j)]
          (when (= (:kind other) (:kind l)) other))))))

(defn can-move? [db id dir] (some? (move-target db id dir)))

(defn move-layer
  "Move a layer one place up (+1) or down (-1) the stack, swapping with the
   neighbour it passes; a no-op when that would cross the pipeline order or
   leave the stack."
  [db id dir]
  (if-let [other (move-target db id dir)]
    (let [swap {id (:position other) (:id other) (:position (layer db id))}]
      (rc/update db :layer [contains? (set (keys swap)) :id]
                 #(assoc % :position (swap (:id %)))))
    db))

(defn denoised-below?
  "Is there an enabled denoise layer under layer `id`?"
  [db id]
  (let [l (layer db id)]
    (boolean (some #(and (= :denoise (:kind %)) (:enabled %) (< (:position %) (:position l)))
                   (layers db (:doc-id l))))))

(defn bump-mask
  "Record that a layer's mask pixels changed."
  [db id]
  (update-layer db id update :mask-rev inc))

;; ---- metadata ----------------------------------------------------------------
(def exif-keys [:make :model :lens :taken :exposure :fnumber :focal :iso :focal35 :orientation])

(defn exif
  "Doc `doc-id`'s shooting metadata as a map of the known fields, or nil."
  [db doc-id]
  (some->> (rc/row db :exif doc-id) (filter (fn [[k v]] (and v (not= k :doc-id)))) (into {}) not-empty))

(defn set-exif
  "Record (or replace) doc `doc-id`'s shooting metadata."
  [db doc-id m]
  (-> (rc/delete db :exif [= :doc-id doc-id])
      (rc/insert :exif (assoc (select-keys m exif-keys) :doc-id doc-id))))

(defn- shutter [s]
  (if (< s 1.0)
    (str "1/" (Math/round (/ 1.0 s)) " s")
    (str (if (== s (Math/floor s)) (long s) s) " s")))

(defn shot-info
  "\"ISO 5000  1/2000 s  f/8  480 mm\" from what the metadata has."
  [{:keys [iso exposure fnumber focal]}]
  (not-empty
   (str/join "  " (cond-> []
                    iso (conj (str "ISO " iso))
                    exposure (conj (shutter exposure))
                    fnumber (conj (str "f/" (if (== fnumber (Math/floor fnumber)) (long fnumber) fnumber)))
                    focal (conj (str (Math/round (double focal)) " mm"))))))

;; ---- staleness ---------------------------------------------------------------
(defn signatures
  "Each layer's signature (as stored in :done-sig): its kind and params and
   the signature of the composite it reads."
  [db doc-id]
  (loop [ls (layers db doc-id) below [doc-id] acc []]
    (if-let [l (first ls)]
      (let [sig [(:kind l) (params l) below]]
        (recur (rest ls)
               ;; the layer's id stands for its mask: two layers' masks can
               ;; differ at equal revisions, so equal settings aren't equal input
               (if (:enabled l)
                 (conj below [(:id l) sig (:opacity l) (:mask-rev l) (:feather l)])
                 below)
               (conj acc (pr-str sig))))
      acc)))

(defn- stale-layer?
  "Its signature changed, or it was processed only where its mask was set
   (:partial-rev, the mask revision then) and the mask changed since."
  [l sig]
  (and (:enabled l)
       (or (not= sig (:done-sig l))
           (and (:partial-rev l) (not= (:partial-rev l) (:mask-rev l))))))

(defn stale-indices
  "Indices of the doc's enabled layers whose output no longer matches."
  [db doc-id]
  (vec (keep-indexed (fn [i [l sig]] (when (stale-layer? l sig) i))
                     (map vector (layers db doc-id) (signatures db doc-id)))))

(defn next-stale [db doc-id] (first (stale-indices db doc-id)))

(defn processed?
  "Every enabled layer of the doc is up to date."
  [db doc-id]
  (empty? (stale-indices db doc-id)))

(defn estimate
  "The doc's image estimates {:noise :sharpness}, once measured."
  [doc]
  (when (:noise doc) (select-keys doc [:noise :sharpness])))
