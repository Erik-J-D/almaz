(ns almaz.ui.sidebar
  "The right-hand panel: navigator, autopilot, and one panel per filter layer
   (top of the stack first) with its settings and mask tools."
  (:require [almaz.actions :as act]
            [almaz.autopilot :as ap]
            [almaz.doc :as d]
            [almaz.engine :as eng]
            [almaz.state :as s]
            [almaz.ui.units :refer [px]]
            [almaz.view :as v]
            [almaz.worker :as worker]
            [jolt.ffi :as ffi]))

(def width 340)

;; ---- navigator -----------------------------------------------------------------
(defonce ^:private nav (atom {:w 300 :h 180 :ptr nil :key nil :version 0}))

(defn- nav-frame [doc rev]
  (let [{:keys [w h]} @nav
        refs (when doc (eng/layer-refs (s/layers (:id doc))))
        k [(:id doc) (:base doc) refs rev w h]]
    (when (and (:base doc) (not= k (:key @nav)))
      (let [{:keys [ptr] pw :pw ph :ph} @nav
            p (if (and ptr (= [pw ph] [w h])) ptr (do (when ptr (ffi/free ptr)) (ffi/alloc (* 4 w h))))]
        (worker/render-fit! {:out p :w w :h h :bg [30 30 30 255]} (:base doc) refs)
        (swap! nav #(-> % (assoc :ptr p :pw w :ph h :key k) (update :version inc)))))
    @nav))

(defn- nav-geometry
  "Scale and offset of the image inside the navigator."
  [{:keys [w h]} doc]
  (let [s (min (/ w (double (:w doc))) (/ h (double (:h doc))))]
    {:s s :ox (/ (- w (* s (:w doc))) 2.0) :oy (/ (- h (* s (:h doc))) 2.0)}))

(defn- nav-jump! [x y]
  (when-let [doc (s/current-doc)]
    (when (:w doc)
      (let [{:keys [s ox oy]} (nav-geometry @nav doc)
            ix (/ (- x ox) s) iy (/ (- y oy) s)
            view @s/view*
            [cx cy] (v/->image view (/ (v/pane-width view) 2.0) (/ (:ch view) 2.0))]
        (swap! s/view* v/pan (* (- cx ix) (:scale view)) (* (- cy iy) (:scale view)))))))

(defonce ^:private nav-drag (atom false))

(defn navigator []
  (let [doc (s/current-doc)
        view @s/view*
        {:keys [ptr w h version] :as f} (nav-frame doc @s/pixels-rev*)]
    [:canvas {:height-units 9 :events 1
              :on-configure (fn [cw ch] (swap! nav assoc :w (long cw) :h (long ch) :key nil))
              :on-press (fn [x y] (reset! nav-drag true) (nav-jump! x y))
              :on-motion (fn [x y] (when @nav-drag (nav-jump! x y)))
              :on-release (fn [_ _] (reset! nav-drag false))
              :canvas-items
              (if (and ptr (:base doc))
                (let [{:keys [s ox oy]} (nav-geometry f doc)
                      [x y rw rh] (v/visible-rect view (:w doc) (:h doc))]
                  [{:id 1 :type :image :coords [0 0 w h] :rgba ptr :iw w :ih h :fit 0 :version version}
                   {:id 2 :type :rect :coords [(+ ox (* s x)) (+ oy (* s y))
                                               (+ ox (* s (+ x rw))) (+ oy (* s (+ y rh)))]
                    :outline [1 1 1 0.9] :width 1.5}])
                [{:id 3 :type :text :coords [(/ w 2.0) (/ h 2.0)] :anchor 0
                  :text "No image" :fill [1 1 1 0.4]}])}]))

;; ---- layer panels --------------------------------------------------------------
(defn- pct [x] (Math/round (* 100.0 (double x))))

(defn- layer-status [doc layer sig job]
  (cond
    (not (:enabled layer)) "Disabled"
    (nil? (:base doc)) "Waiting for the image"
    (= sig (:done-sig layer)) "Up to date"
    job (str "Processing " (or (:done job) 0) "/" (or (:total job) "…"))
    :else "Queued"))

(defn- mask-tools [layer tool]
  (let [lid (:id layer)
        active (when (= lid (:layer tool)) (:mode tool))]
    [:vbox {:spacing 4}
     [:label {:text "Mask" :bold 1}]
     [:enum {:items ["Off" "Paint" "Erase"]
             :value (case active :paint 1 :erase 2 0)
             :on-change #(act/set-tool! ([:pan :paint :erase] (long %)) lid)}]
     [:slider {:label "Brush size" :min 4 :max 1500 :integer 1 :value (long (:radius tool))
               :on-change #(swap! s/tool* assoc :radius (double %))}]
     [:slider {:label "Hardness" :min 0 :max 100 :integer 1 :value (pct (:hardness tool))
               :on-change #(swap! s/tool* assoc :hardness (/ (double %) 100.0))}]
     [:slider {:label "Feather" :min 0 :max 200 :integer 1 :value (long (:feather layer))
               :on-change #(act/set-feather! lid (double %))}]
     [:hbox {:align 1}
      [:button {:text "Fill" :on-click #(act/mask-fill! lid 1.0)}]
      [:button {:text "Clear" :on-click #(act/mask-fill! lid 0.0)}]
      [:button {:text "Invert" :on-click #(act/mask-invert! lid)}]]
     [:checkbox {:label "Show mask while painting" :value (if (:show-mask tool) 1 0)
                 :on-change #(swap! s/tool* assoc :show-mask (pos? %))}]]))

(defn- denoise-settings [layer]
  (let [lid (:id layer)]
    [:vbox {:spacing 4}
     [:slider {:label "Recover detail" :min 0 :max 100 :integer 1
               :value (pct (:detail layer))
               :on-change #(act/set-param! lid :detail (/ (double %) 100.0))}]]))

(defn- sharpen-settings [db doc layer]
  (let [lid (:id layer)
        {:keys [model amount radius]} layer]
    [:vbox {:spacing 4}
     (when (and (= model :ai) (ap/noisy? (d/estimate doc)) (not (d/denoised-below? db lid)))
       [:label {:text "Noisy image: AI deblur amplifies noise. Add Denoise first." :text-color [1 0.75 0.3 1]}])
     [:label {:text "Model"}]
     [:enum {:items ["AI deblur" "Unsharp mask"] :value (if (= model :classic) 1 0)
             :on-change #(act/set-param! lid :model (if (= 1 (long %)) :classic :ai))}]
     (when (= model :classic)
       [:vbox {:spacing 4}
        [:slider {:label "Radius" :min 0.5 :max 8 :precision 1 :value radius
                  :on-change #(act/set-param! lid :radius (double %))}]
        [:slider {:label "Amount" :min 0 :max 300 :integer 1 :value (pct amount)
                  :on-change #(act/set-param! lid :amount (/ (double %) 100.0))}]])]))

(defn- layer-panel [{:keys [db doc layer sig job tool]}]
  (let [lid (:id layer)
        label (get-in d/kinds [(:kind layer) :label])]
    [:panel {:key lid :text label}
     [:checkbox {:header true :label "" :value (if (:enabled layer) 1 0)
                 :on-change #(act/set-layer! lid :enabled (pos? %))}]
     [:label {:text (layer-status doc layer sig job)}]
     [:slider {:label "Strength" :min 0 :max 100 :integer 1 :value (pct (:opacity layer))
               :on-change #(act/set-layer! lid :opacity (/ (double %) 100.0))}]
     (case (:kind layer)
       :denoise (denoise-settings layer)
       :sharpen (sharpen-settings db doc layer))
     [:separator]
     (mask-tools layer tool)
     [:hbox {:align 1}
      [:button {:text "Move up" :enabled (if (d/can-move? db lid 1) 1 0)
                :on-click #(act/move-layer! lid 1)}]
      [:button {:text "Move down" :enabled (if (d/can-move? db lid -1) 1 0)
                :on-click #(act/move-layer! lid -1)}]
      [:button {:text "Remove" :on-click #(act/remove-layer! lid)}]]]))

(defn layers-panel []
  (let [db @s/db*
        doc (s/current-doc)
        layers (when doc (d/layers db (:id doc)))
        tool @s/tool*
        job @s/job*
        sigs (when doc (d/signatures db (:id doc)))
        stale (when doc (d/next-stale db (:id doc)))]
    [:vbox {:spacing 6}
     [:hbox {:align 1}
      [:button {:text "+ Denoise" :on-click #(act/add-layer! :denoise) :enabled (if doc 1 0)}]
      [:button {:text "+ Sharpen" :on-click #(act/add-layer! :sharpen) :enabled (if doc 1 0)}]]
     (if (seq layers)
       [:vbox {:spacing 4}
        (for [[i layer] (reverse (map-indexed vector layers))]
          (layer-panel {:db db :doc doc :layer layer :sig (nth sigs i) :job (when (= i stale) job) :tool tool}))]
       [:label {:text (if doc "No filters. Add one, or turn on Autopilot." "") :weight 1}])]))

(defn autopilot-row []
  (let [doc (s/current-doc)]
    [:vbox {:spacing 4}
     [:hbox {}
      [:toggle {:label "Autopilot" :value (if @s/autopilot* 1 0)
                :on-change #(act/set-autopilot! (pos? %))}]]
     (when-let [est (d/estimate doc)]
       [:label {:text (ap/describe est)}])]))

(defn sidebar []
  [:vbox {:weight 0 :reqwidth (px width) :padding (px 8) :spacing (px 6)}
   [navigator]
   [autopilot-row]
   [:separator]
   [:scroll {:stretch true}
    [layers-panel]]])
