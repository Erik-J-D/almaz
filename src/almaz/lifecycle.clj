(ns almaz.lifecycle
  "Native memory ownership for documents and layers.

   Images and masks are freed on the UI thread (renders read them there) and
   never while the worker is reading or writing them: the worker declares the
   handles a job uses, and anything retired meanwhile waits until it lets go.
   Allocation happens outside swap! (a retried swap would leak what it
   allocated); the handle is then installed conditionally."
  (:require [glimmer.core :as ui]
            [almaz.autopilot :as ap]
            [almaz.doc :as d]
            [almaz.pixels :as px]
            [almaz.state :as s]
            [jolt.ffi :as ffi]))

(defonce ^:private in-use (atom #{}))
(defonce ^:private deferred (atom []))

(defn- free! [[kind p]]
  (case kind
    :img (px/free-image! p)
    :mask (px/free-mask! p)
    :raw (ffi/free p)))

(defn- flush! []
  (let [busy @in-use
        [keep go] ((juxt remove filter) #(busy (second %)) @deferred)]
    (reset! deferred (vec keep))
    (run! free! go)))

(defn retire!
  "Free native handles once nothing uses them: `items` are [kind ptr] with
   kind :img, :mask or :raw (a plain malloc'd buffer); nil ptrs are skipped."
  [& items]
  (let [items (filter second items)]
    (when (seq items)
      (ui/on-gui (fn [] (swap! deferred into items) (flush!))))))

(defn using!
  "The worker starts a job on these handles."
  [ptrs] (reset! in-use (set (remove nil? ptrs))))

(defn released!
  "The worker's job is over; free whatever was retired meanwhile."
  []
  (reset! in-use #{})
  (ui/on-gui flush!))

(def mask-div "Masks are stored at 1/4 of the image resolution." 4)

(defn new-mask
  "A mask for a w x h image with the given feather, filled with `fill`
   (1: everywhere, the default; 0: nowhere, for repairs)."
  ([w h feather] (new-mask w h feather 1.0))
  ([w h feather fill]
   (doto (px/new-mask w h mask-div fill) (px/mask-feather! feather))))

(defn- install-mask!
  "Give layer `lid` the mask `m` unless it already has one (or is gone);
   free `m` when it wasn't installed."
  [lid m]
  (s/swap-db! (fn [db]
                (let [cur (d/layer db lid)]
                  (if (and cur (nil? (:mask cur))) (d/update-layer db lid assoc :mask m) db))))
  (when-not (= m (:mask (s/layer lid))) (px/free-mask! m)))

(defn ensure-masks!
  "Give every layer of doc `doc-id` a mask, once the image size is known."
  [doc-id]
  (let [{:keys [w h]} (s/doc doc-id)]
    (when w
      (doseq [l (s/layers doc-id) :when (nil? (:mask l))]
        (install-mask! (:id l) (new-mask w h (:feather l) (d/mask-fill l)))))))

(defn with-autopilot
  "Add the layers autopilot suggests for the doc's estimate, at the suggested
   strengths, and remember it ran."
  [db doc-id]
  (reduce (fn [acc {:keys [kind opacity]}]
            (let [[acc' lid] (d/ensure-layer acc doc-id kind)]
              (d/update-layer acc' lid assoc :opacity opacity :enabled true)))
          (d/update-doc db doc-id assoc :autopiloted true)
          (ap/suggest (d/estimate (d/doc db doc-id)))))

(defn layer-natives [layer]
  [[:img (:img layer)] [:mask (:mask layer)]])

(defn evict
  "Drop doc `id`'s full-resolution pixels (kept: masks, settings, thumbnail).
   Returns [db items-to-retire]; its layers are recomputed on reload."
  [db id]
  (let [dc (d/doc db id)
        ls (d/layers db id)]
    [(reduce (fn [acc l] (d/update-layer acc (:id l) assoc :img nil :done-sig nil :partial-rev nil))
             (d/update-doc db id assoc :base nil :preview nil
                           :status (if (:thumb dc) :thumb :pending))
             ls)
     (concat [[:img (:base dc)] [:img (:preview dc)]] (map (fn [l] [:img (:img l)]) ls))]))

(defn doc-natives
  "Everything doc `id` owns."
  [db id]
  (let [dc (d/doc db id)]
    (concat [[:img (:base dc)] [:img (:preview dc)] [:raw (:thumb dc)]]
            (mapcat layer-natives (d/layers db id)))))
