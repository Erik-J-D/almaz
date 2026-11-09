(ns almaz.worker
  "The background worker: one thread that loads images, runs filter layers,
   makes thumbnails and exports, reading what to do from the database in
   almaz.state.

   Priorities: a pending export of the current image, then loading the
   current image, then (re)processing its first stale layer once edits have
   settled, then filmstrip thumbnails for images not opened yet. A layer job
   is cancelled as soon as the layer's signature changes (an edit underneath
   it, or the layer itself), it is disabled, or another image is selected."
  (:require [glimmer.backend :as b]
            [almaz.autopilot :as ap]
            [almaz.doc :as d]
            [almaz.engine :as eng]
            [almaz.lifecycle :as life]
            [almaz.models :as models]
            [almaz.pixels :as px]
            [almaz.raw :as raw]
            [almaz.state :as s]
            [almaz.view :as v]
            [jolt.ffi :as ffi]))

(def settle-ms "Processing waits this long after the last edit." 400)
(def thumb-size "Filmstrip thumbnails, raster px (2x a 88x59 slot)." [176 118])

;; ---- thumbnails ----------------------------------------------------------------
(defn render-fit!
  "Render base + layers to fit inside the w x h RGBA buffer `out` (the rest bg)."
  [{:keys [out w h bg]} base layers]
  (let [iw (px/width base) ih (px/height base)
        s (min (/ w (double iw)) (/ h (double ih)))]
    (px/render! base layers {:ox (/ (- w (* s iw)) 2.0) :oy (/ (- h (* s ih)) 2.0) :scale s
                             :out out :out-w w :out-h h :bg bg})))

(defn- set-thumb!
  "Render `img` (+ layers) as doc `id`'s filmstrip thumbnail."
  [id img layers]
  (let [[w h] thumb-size
        old (:thumb (s/doc id))
        p (ffi/alloc (* 4 w h))]
    (render-fit! {:out p :w w :h h :bg [40 40 40 255]} img layers)
    (s/swap-doc! id #(-> % (assoc :thumb p) (update :thumb-rev inc)))
    (if (= p (:thumb (s/doc id)))
      (when old (life/retire! [:raw old]))
      (ffi/free p))))

(defn- refs [doc-id] (eng/layer-refs (s/layers doc-id)))

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

(defn- load-doc! [{:keys [id name raw] :as dc}]
  (s/swap-doc! id assoc :status :loading)
  (reset! s/job* {:label (str "Opening " name)})
  (when raw (show-preview! dc))
  (reset! s/job* {:label (str "Decoding " name)})
  (let [base (decode dc)]
    (if (= id @s/current*) (install! dc base) (shelve! id base))))

(defn- thumb-doc! [{:keys [id name raw path] :as dc}]
  (reset! s/job* {:label (str "Reading " name)})
  (let [img (or (when raw (raw/thumbnail path)) (decode dc))]
    (set-thumb! id img [])
    (px/free-image! img)
    (s/swap-doc! id assoc :status :thumb)))

;; ---- processing ----------------------------------------------------------------
(defn- live-sig
  "The current signature of layer `lid` of doc `id`, or nil when it is gone,
   disabled, or the doc is no longer the one being edited."
  [id lid]
  (let [db @s/db*]
    (when (= id @s/current*)
      (when-let [i (d/layer-index db lid)]
        (when (:enabled (d/layer db lid))
          (nth (d/signatures db id) i))))))

(defn- layer-output!
  "The layer's output image, created from its input composite the first time
   (so unprocessed tiles look unchanged while processing streams in)."
  [{:keys [base w h]} {lid :id :as layer} below]
  (or (:img layer)
      (let [img (px/new-image w h)]
        (px/composite-into! img base below)
        (s/swap-layer! lid assoc :img img)
        ;; deleted meanwhile: nothing owns it (freed once the job lets go)
        (when-not (= img (:img (s/layer lid))) (life/retire! [:img img]))
        img)))

(defn- mb [n] (quot n 1000000))

(defn- session-for
  "The layer's model session, downloading the model first if it is missing."
  [layer label]
  (when-let [m (eng/model-id layer)]
    (models/ensure-model! m (fn [got total]
                              (reset! s/job* {:label (str "Downloading " label " model ("
                                                          (mb got) "/" (mb (or total 0)) " MB)")
                                              :done got :total total})))
    (reset! s/job* {:label (str "Loading " label " model")})
    (models/session m)))

(defn- report-progress [label]
  (fn [done total]
    (reset! s/job* {:label (str label "…") :done done :total total})
    (if (= done total) (s/bump-pixels!) (s/bump-pixels-throttled! 250))))

(defn- finished!
  "Record a finished run: valid for `sig`; a :partial run (masked-out tiles
   skipped) only for the mask revision it ran with."
  [{:keys [id base]} {:keys [lid sig result mask-rev]}]
  (s/swap-layer! lid assoc :done-sig sig :partial-rev (when (= :partial result) mask-rev))
  (when (d/processed? @s/db* id)
    (set-thumb! id base (refs id))))

(defn- run-layer!
  "Run the job's tiles; record the result unless it was cancelled."
  [{:keys [dc layer sig below out label]}]
  (let [{:keys [id base w h]} dc
        lid (:id layer)
        t0 (s/now)
        result (eng/process! {:base base :below below :out out :mask (:mask layer)
                              :op (eng/op-for layer (session-for layer label))
                              :focus (v/focus @s/view* w h)
                              :progress (report-progress label)
                              :cancelled? #(not= sig (live-sig id lid))})]
    (when (#{:done :partial} result)
      (finished! dc {:lid lid :sig sig :result result :mask-rev (:mask-rev layer)})
      (s/message! (format "%s done in %.1fs" label (/ (- (s/now) t0) 1000.0))))))

(defn- process-layer! [{:keys [id base] :as dc} idx]
  (let [ls (s/layers id)
        layer (nth ls idx)
        below (eng/layer-refs (take idx ls))
        out (layer-output! dc layer below)]
    ;; from the first tile written, the old output is no longer valid for any
    ;; signature: a cancelled run must leave the layer stale
    (s/swap-layer! (:id layer) assoc :done-sig nil :partial-rev nil)
    (life/using! (concat [base out (:mask layer)] (map :img below) (map :mask below)))
    (try
      (run-layer! {:dc dc :layer layer :below below :out out
                   :sig (nth (d/signatures @s/db* id) idx)
                   :label (get-in d/kinds [(:kind layer) :label])})
      (finally (life/released!)))))

;; ---- export --------------------------------------------------------------------
(defn- export! [{:keys [path quality] fmt :format} {:keys [id base name]}]
  (reset! s/job* {:label (str "Exporting " name)})
  (let [ls (s/layers id)]
    (life/using! (concat [base] (map :img ls) (map :mask ls)))
    (try
      (let [t0 (s/now)]
        (px/save-image! base (eng/layer-refs ls)
                        {:path path :format fmt :quality quality :meta (d/exif @s/db* id)})
        (s/message! (format "Exported %s (%.1fs)" path (/ (- (s/now) t0) 1000.0))))
      (finally
        (reset! s/export-req* nil)
        (life/released!)))))

;; ---- the loop ------------------------------------------------------------------
(defn- export-task [cur]
  (let [req @s/export-req*]
    (when (and req (= (:doc req) (:id cur)) (= :ready (:status cur)) (d/processed? @s/db* (:id cur)))
      [:export req cur])))

(defn- load-task [cur]
  (when (#{:pending :thumb} (:status cur)) [:load cur]))

(defn- process-task [cur]
  (when-let [i (and (= :ready (:status cur)) (d/next-stale @s/db* (:id cur)))]
    (when (> (- (s/now) (:edited-at cur)) settle-ms)
      [:process cur i])))

(defn- thumb-task []
  (when-let [p (first (filter #(= :pending (:status %)) (d/docs @s/db*)))]
    [:thumb p]))

(defn- pick-task
  "The next job, by priority (see the namespace doc)."
  []
  (let [cur (s/current-doc)]
    (or (when cur (or (export-task cur) (load-task cur) (process-task cur)))
        (thumb-task))))

(defn- run-task! [[kind a b]]
  (case kind
    :export  (export! a b)
    :load    (load-doc! a)
    :process (process-layer! a b)
    :thumb   (thumb-doc! a)))

(defn- failed! [[kind doc-or-req] e]
  (s/message! (str "Error: " (ex-message e)))
  (case kind
    (:load :thumb) (s/swap-doc! (:id doc-or-req) assoc :status :error :error (str (ex-message e)))
    :export (reset! s/export-req* nil)
    nil))

(defn- step! []
  (if-let [task (when @b/loop-running? (pick-task))]
    (try (run-task! task)
         (catch Throwable e (failed! task e)))
    (do (reset! s/job* nil)
        (s/await-wake 150))))

(defonce ^:private thread (atom nil))

(defn start!
  "Start the worker thread (once). It takes no work until the UI loop runs:
   before that, a change to a reactive cell re-renders synchronously on the
   changing thread, and a render racing the window's mount duplicates nodes."
  []
  (when-not @thread
    (reset! thread (doto (Thread. (fn [] (loop [] (step!) (recur)))) (.start)))))
