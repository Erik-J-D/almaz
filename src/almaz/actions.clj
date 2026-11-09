(ns almaz.actions
  "User commands: what buttons, sliders and the canvas do to the database.
   They run on the UI thread; slow work is left to the worker (almaz.worker)."
  (:require [clojure.string :as str]
            [glimmer-bui.ffi :as bk]
            [almaz.doc :as d]
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
      (when-let [{:keys [w h]} (s/doc id)]
        (when w (swap! s/view* v/fit w h)))
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
    (when (empty? fresh)
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
(defn- cur-id [] @s/current*)

(defn add-layer! [kind]
  (when-let [id (cur-id)]
    (s/edit! id (fn [db] (first (d/add-layer db id kind))))
    (life/ensure-masks! id)))

(defn remove-layer! [lid]
  (when-let [id (cur-id)]
    (when-let [l (s/layer lid)]
      (s/edit! id d/remove-layer lid)
      (apply life/retire! (life/layer-natives l))
      (when (= lid (:layer @s/tool*)) (swap! s/tool* assoc :mode :pan :layer nil)))))

(defn move-layer! [lid dir] (when-let [id (cur-id)] (s/edit! id d/move-layer lid dir)))
(defn set-param! [lid k val] (when-let [id (cur-id)] (s/edit! id d/set-param lid k val)))
(defn set-layer! [lid k val] (when-let [id (cur-id)] (s/edit! id d/update-layer lid assoc k val)))

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
  (set-layer! lid :feather (double sigma)))

(defn set-tool! [mode lid]
  (swap! s/tool* assoc :mode mode :layer (when (not= mode :pan) lid)))

;; ---- autopilot -------------------------------------------------------------
(defn set-autopilot! [on?]
  (reset! s/autopilot* on?)
  (when-let [dc (s/current-doc)]
    (when (and on? (empty? (s/layers (:id dc))) (d/estimate dc))
      (s/edit! (:id dc) life/with-autopilot (:id dc))
      (life/ensure-masks! (:id dc)))))

;; ---- view ------------------------------------------------------------------
(defn- doc-size []
  (when-let [{:keys [w h]} (s/current-doc)] (when w [w h])))

(defn set-mode! [mode]
  (swap! s/view* assoc :mode mode)
  (when (= :fit (:zoom @s/view*))
    (when-let [[w h] (doc-size)] (swap! s/view* v/fit w h))))

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
