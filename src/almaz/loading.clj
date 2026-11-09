(ns almaz.loading
  "Opening images for the worker (almaz.worker): decoding, the camera preview
   shown meanwhile, installing the result as the document's base, and the
   filmstrip thumbnails."
  (:require [almaz.autopilot :as ap]
            [almaz.doc :as d]
            [almaz.engine :as eng]
            [almaz.lifecycle :as life]
            [almaz.pixels :as px]
            [almaz.raw :as raw]
            [almaz.state :as s]
            [almaz.view :as v]
            [jolt.ffi :as ffi]))

(def thumb-size "Filmstrip thumbnails, raster px (2x a 88x59 slot)." [176 118])

;; ---- thumbnails ----------------------------------------------------------------
(defn render-fit!
  "Render base + layers to fit inside the w x h RGBA buffer `out` (the rest bg)."
  [{:keys [out w h bg]} base layers]
  (let [iw (px/width base) ih (px/height base)
        s (min (/ w (double iw)) (/ h (double ih)))]
    (px/render! base layers {:ox (/ (- w (* s iw)) 2.0) :oy (/ (- h (* s ih)) 2.0) :scale s
                             :out out :out-w w :out-h h :bg bg})))

(defn set-thumb!
  "Render `img` (+ layers) as doc `id`'s filmstrip thumbnail; `sig` is the
   doc's output-signature when that's the whole stack (nil: a stand-in)."
  [id img layers & [sig]]
  (let [[w h] thumb-size
        old (:thumb (s/doc id))
        p (ffi/alloc (* 4 w h))]
    (render-fit! {:out p :w w :h h :bg [40 40 40 255]} img layers)
    (s/swap-doc! id #(-> % (assoc :thumb p :thumb-sig sig) (update :thumb-rev inc)))
    (if (= p (:thumb (s/doc id)))
      (when old (life/retire! [:raw old]))
      (ffi/free p))))

;; ---- loading -------------------------------------------------------------------
(defn- decode [{:keys [path]}] (raw/load-any path))

(defn- autopilot! [id]
  (let [dc (s/doc id)]
    (when (and @s/autopilot* (empty? (s/layers id)) (not (:autopiloted dc)) (d/estimate dc))
      (s/edit! id life/with-autopilot id)
      (life/ensure-masks! id))))

(defn- show-preview!
  "Show the camera's embedded preview while the full image decodes."
  [{:keys [id path thumb]}]
  (when-let [t (raw/thumbnail path)]
    (if (= id @s/current*)
      (do (s/swap-doc! id assoc :preview t)
          (when-not thumb (set-thumb! id t []))
          (s/bump-pixels!))
      (px/free-image! t))))

(defn- drop-preview!
  "Retire the preview and apply `f` to the doc."
  [id f & args]
  (let [preview (:preview (s/doc id))]
    (apply s/swap-doc! id f args)
    (s/swap-doc! id assoc :preview nil)
    (life/retire! [:img preview])))

(defn- shelve!
  "The user switched away mid-decode: keep only a thumbnail."
  [id base]
  (when-not (:thumb (s/doc id)) (set-thumb! id base []))
  (px/free-image! base)
  (drop-preview! id assoc :status :thumb))

(defn- install!
  "The decoded image becomes the doc's base; analyze it and run autopilot."
  [{:keys [id name]} base]
  (let [w (px/width base) h (px/height base)]
    (drop-preview! id assoc :base base :w w :h h :status :ready)
    (when-let [m (raw/source-meta (:path (s/doc id)))] (s/swap-db! d/set-exif id m))
    (life/ensure-masks! id)
    (when (= :fit (:zoom @s/view*)) (swap! s/view* v/fit w h))
    (s/bump-pixels!)
    (when-not (:thumb (s/doc id)) (set-thumb! id base []))
    (reset! s/job* {:label "Analyzing"})
    (let [est (px/estimate base)]
      (s/swap-doc! id merge est)
      (s/message! (str name ": " w " x " h ", " (ap/describe est))))
    (autopilot! id)))

(defn load-doc! [{:keys [id name raw] :as dc}]
  (s/swap-doc! id assoc :status :loading)
  (reset! s/job* {:label (str "Opening " name)})
  (when raw (show-preview! dc))
  (reset! s/job* {:label (str "Decoding " name)})
  (let [base (decode dc)]
    (if (= id @s/current*) (install! dc base) (shelve! id base))))

(defn thumb-doc! [{:keys [id name raw path] :as dc}]
  (reset! s/job* {:label (str "Reading " name)})
  (let [img (or (when raw (raw/thumbnail path)) (decode dc))]
    (set-thumb! id img [])
    (px/free-image! img)
    (s/swap-doc! id assoc :status :thumb)))

(defn rethumb!
  "Redraw the current doc's thumbnail from its whole stack (an edit that
   needs no processing, like a light adjustment, changed the picture)."
  [{:keys [id base]}]
  (let [db @s/db*
        ls (d/layers db id)]
    (life/using! (concat [base] (map :img ls) (map :mask ls)))
    (try (set-thumb! id base (eng/layer-refs ls) (d/output-signature db id))
         (finally (life/released!)))))
