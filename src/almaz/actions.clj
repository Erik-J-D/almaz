(ns almaz.actions
  "User commands: what buttons, sliders and the canvas do to the database.
   They run on the UI thread; slow work is left to the worker (almaz.worker)."
  (:require [clojure.string :as str]
            [glimmer-bui.ffi :as bk]
            [almaz.autopilot :as ap]
            [almaz.color :as c]
            [almaz.doc :as d]
            [almaz.engine :as eng]
            [almaz.geom :as g]
            [almaz.lifecycle :as life]
            [almaz.pixels :as px]
            [almaz.raw :as raw]
            [almaz.state :as s]
            [almaz.view :as v]))

;; ---- images ----------------------------------------------------------------
(def image-extensions #{"jpg" "jpeg" "png" "tif" "tiff"})

(defn supported? [path]
  (or (raw/raw-file? path)
      (contains? image-extensions (str/lower-case (or (second (re-find #"\.([^./]+)$" path)) "")))))

(defn select-doc!
  "Make doc `id` the one being edited. The previous one gives back its
   full-resolution pixels (they are re-decoded when it is selected again)."
  [id]
  (let [prev @s/current*]
    (when (not= id prev)
      (when (s/doc prev)
        (apply life/retire! (s/swap-db-with! life/evict prev)))
      (reset! s/current* id)
      (swap! s/tool* assoc :mode :pan :layer nil)
      (swap! s/view* assoc :zoom :fit)
      (when-let [[w h] (s/display-size (s/doc id))]
        (swap! s/view* v/fit w h))
      (s/wake!))))

(defn add-images!
  "Add image files to the filmstrip; the first one added opens when nothing is."
  [paths]
  (let [known (set (map :path (d/docs @s/db*)))
        fresh (->> paths (filter supported?) (remove known) distinct)]
    (doseq [p fresh]
      (s/swap-db! d/add-doc {:path p :raw? (raw/raw-file? p)}))
    (when (and (nil? @s/current*) (seq (d/docs @s/db*)))
      (select-doc! (:id (first (d/docs @s/db*)))))
    (when (and (seq paths) (empty? fresh))
      (s/message! "No new supported images"))
    (s/wake!)))

(def ^:private open-filters
  [["Images" "*.nef *.nrw *.arw *.cr2 *.cr3 *.raf *.orf *.rw2 *.dng *.pef *.srw *.jpg *.jpeg *.png *.tif *.tiff"]
   ["All files" "*"]])

(defn open-dialog! []
  (when-let [paths (bk/file-dialog {:title "Add images" :mode :open :multiple true
                                    :filters open-filters})]
    (add-images! paths)))

(defn remove-doc! [id]
  (when (s/doc id)
    (apply life/retire! (s/swap-db-with! (fn [db] [(d/remove-doc db id) (life/doc-natives db id)])))
    (when (= id @s/current*)
      (reset! s/current* nil)
      (when-let [n (first (d/docs @s/db*))] (select-doc! (:id n))))))

;; ---- layers ----------------------------------------------------------------
;; One layer per kind, made the first time it is switched on or edited.
(defn- cur-id [] @s/current*)

(defn- kind-layer!
  "The current doc's layer of `kind`, created (with its mask) if it has none."
  [kind]
  (when-let [id (cur-id)]
    (or (:id (d/layer-of @s/db* id kind))
        (do (s/edit! id (fn [db] (first (d/ensure-layer db id kind))))
            (life/ensure-masks! id)
            (:id (d/layer-of @s/db* id kind))))))

(defn set-enabled!
  "Switch the current doc's `kind` filter on or off."
  [kind on?]
  (when-let [id (cur-id)]
    (if on?
      (let [lid (kind-layer! kind)] (s/edit! id d/update-layer lid assoc :enabled true))
      (when-let [l (d/layer-of @s/db* id kind)]
        (s/edit! id d/update-layer (:id l) assoc :enabled false)
        (when (= (:id l) (:layer @s/tool*)) (swap! s/tool* assoc :mode :pan :layer nil))))))

(defn- edit-kind!
  "Apply `f` (db lid -> db) to the `kind` layer, switching it on: touching a
   filter's settings means wanting to see it."
  [kind f]
  (when-let [lid (kind-layer! kind)]
    (s/edit! (cur-id) (fn [db] (f (d/update-layer db lid assoc :enabled true) lid)))))

(defn set-param! [kind k val] (edit-kind! kind (fn [db lid] (d/set-param db lid k val))))
(defn set-layer! [kind k val] (edit-kind! kind (fn [db lid] (d/update-layer db lid assoc k val))))
(defn reset-kind!
  "Put a filter's settings back to their defaults."
  [kind]
  (when-let [l (some-> (cur-id) (#(d/layer-of @s/db* % kind)))]
    (s/edit! (cur-id) d/reset-params (:id l))))

(defn auto-exposure!
  "Set the light layer's exposure so the image under it reaches white (its
   brightest 1%), the brightening raw converters otherwise apply unasked."
  []
  (when-let [{:keys [id base] :as dc} (s/current-doc)]
    (when base
      (let [below (take-while #(not= :light (:kind %)) (s/layers id))
            {:keys [l]} (px/histogram base (eng/layer-refs dc below))]
        (set-param! :light :exposure (ap/auto-exposure l))))))

(defn set-level!
  "Move a levels point (almaz.color/set-level)."
  [ch which v]
  (edit-kind! :levels (fn [db lid]
                        (let [l (d/layer db lid)
                              p (c/set-level (second (d/colour l)) ch which v)]
                          (d/update-layer db lid merge p)))))

(defn start-pick!
  "The next click on the image sets levels' `which` point (:black :grey :white)."
  [which]
  (swap! s/tool* assoc :mode :pick :pick which :layer nil))

(defn pick!
  "Set the levels point being picked from the image at source pixel p: the
   colour levels receives there (the stack below it)."
  [p]
  (when-let [{:keys [id base] :as dc} (s/current-doc)]
    (let [which (:pick @s/tool*)]
      (swap! s/tool* assoc :mode :pan :pick nil)
      (when (and base which)
        (let [below (take-while #(not= :levels (:kind %)) (s/layers id))
              rgb (px/sample base (eng/layer-refs dc below) p 2)]
          (edit-kind! :levels (fn [db lid]
                                (d/update-layer db lid merge
                                                (c/pick-level (second (d/colour (d/layer db lid))) which rgb)))))))))

(defn- with-mask! [lid f]
  (when-let [id (cur-id)]
    (when-let [m (:mask (s/layer lid))]
      (f m)
      (s/edit! id d/bump-mask lid)
      (s/bump-pixels!))))

(defn mask-fill! [lid value] (with-mask! lid #(px/mask-fill! % value)))
(defn mask-invert! [lid] (with-mask! lid px/mask-invert!))

(defn set-feather! [lid sigma]
  (with-mask! lid #(px/mask-feather! % sigma))
  (when-let [id (cur-id)] (s/edit! id d/update-layer lid assoc :feather (double sigma))))

(defn set-tool! [mode lid]
  (swap! s/tool* assoc :mode mode :layer (when (not= mode :pan) lid) :pick nil))

;; ---- autopilot -------------------------------------------------------------
(defn set-autopilot! [on?]
  (reset! s/autopilot* on?)
  (when-let [dc (s/current-doc)]
    (when (and on? (empty? (s/layers (:id dc))) (d/estimate dc))
      (s/edit! (:id dc) life/with-autopilot (:id dc))
      (life/ensure-masks! (:id dc)))))

;; ---- view ------------------------------------------------------------------
(defn- doc-size [] (s/display-size (s/current-doc)))

(defn- refit!
  "Fit the image again if the view is fitted (what it shows changed size)."
  []
  (when (= :fit (:zoom @s/view*))
    (when-let [[w h] (doc-size)] (swap! s/view* v/fit w h))))

(defn set-tab!
  "Show sidebar tab `tab`; the Dimensions tab shows the whole image to crop."
  [tab]
  (when (not= tab @s/tab*)
    (reset! s/tab* tab)
    (swap! s/tool* assoc :mode :pan :layer nil :pick nil)
    (swap! s/view* assoc :zoom :fit)
    (refit!)))

;; ---- geometry --------------------------------------------------------------
(defn- edit-geom!
  "Apply `f` (doc -> doc) to the current doc's orientation and crop."
  [f & args]
  (when-let [id (cur-id)]
    (when (:w (s/doc id))
      (s/edit! id (fn [db] (d/update-doc db id #(apply f % args))))
      (refit!))))

(defn rotate! [dir] (edit-geom! (fn [dc] (update dc :orient #(g/rotate (or % 0) dir)))))
(defn flip-h! [] (edit-geom! (fn [dc] (update dc :orient #(g/flip-h (or % 0))))))
(defn flip-v! [] (edit-geom! (fn [dc] (update dc :orient #(g/flip-v (or % 0))))))
(defn set-constrain! [k] (edit-geom! g/constrain k))
(defn set-crop-size! [w h] (edit-geom! g/with-crop-size [(max g/min-crop (long w)) (max g/min-crop (long h))]))
(defn set-ratio!
  "The custom aspect ratio's w and h."
  [rw rh]
  (edit-geom! (fn [dc] (g/constrain (assoc dc :ratio-w (max 0.01 (double rw)) :ratio-h (max 0.01 (double rh)))
                                    :custom-ratio))))
(defn reset-crop! []
  (edit-geom! (fn [dc] (assoc dc :crop-x nil :crop-y nil :crop-w nil :crop-h nil :constrain :free))))
(defn set-display-crop!
  "Set the crop from the crop editor's rect (display coordinates)."
  [rect]
  (when-let [id (cur-id)]
    (s/edit! id (fn [db] (d/update-doc db id #(g/with-display-crop % rect))))))

(defn set-mode! [mode]
  (swap! s/view* assoc :mode mode)
  (refit!))

(defn zoom-fit! []
  (when-let [[w h] (doc-size)] (swap! s/view* v/fit w h)))

(defn zoom-to! [scale] (swap! s/view* v/set-scale scale))

;; ---- export ----------------------------------------------------------------
(def export-formats
  [["JPEG" :jpeg "jpg"] ["TIFF 16-bit" :tiff16 "tif"] ["PNG 16-bit" :png16 "png"] ["PNG 8-bit" :png8 "png"]])

(defn export! [fmt-index quality]
  (when-let [dc (s/current-doc)]
    (let [[label fmt ext] (nth export-formats fmt-index)
          stem (str/replace (:name dc) #"\.[^.]+$" "")
          dir (str/replace (:path dc) #"/[^/]*$" "")]
      (when-let [path (first (bk/file-dialog {:title (str "Export " label) :mode :save
                                              :dir dir :file (str stem "-almaz." ext)
                                              :filters [[label (str "*." ext)]]}))]
        (let [path (if (str/ends-with? (str/lower-case path) (str "." ext)) path (str path "." ext))]
          (reset! s/export-req* {:doc (:id dc) :path path :format fmt :quality quality})
          (s/message! (if (d/processed? @s/db* (:id dc))
                        (str "Exporting to " path)
                        "Export queued: finishing filters first"))
          (s/wake!))))))
