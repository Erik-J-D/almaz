(ns almaz.ui.canvas
  "The image pane: a BUI canvas showing the current image's layer stack.

   The pixels come from the native renderer (almaz.pixels/render!) drawing
   into one reusable RGBA buffer at the canvas's raster size, handed to the
   canvas as an image item by pointer. A frame is re-rendered only when what
   it shows changes (view, layers, pixels); the brush cursor and the split
   divider are separate canvas items, so moving the pointer costs nothing.

   Modes: single (after), split (before | after at a draggable divider) and
   side by side (before and after panes in sync). Pointer: drag pans, wheel
   zooms about the pointer, double click toggles fit / 100%; with a mask tool
   active, drag paints or erases the layer's mask."
  (:require [almaz.doc :as d]
            [almaz.engine :as eng]
            [almaz.pixels :as px]
            [almaz.state :as s]
            [almaz.view :as v]
            [jolt.ffi :as ffi]))

(def bg [36 36 36 255])
(def mask-tint [1.0 0.25 0.2 0.45])

;; ---- the frame buffer ----------------------------------------------------------
(defonce ^:private frame (atom {:ptr nil :w 0 :h 0 :key nil :version 0}))

(defn- buffer! [w h]
  (let [{:keys [ptr] fw :w fh :h} @frame]
    (if (and ptr (= [w h] [fw fh]))
      ptr
      (let [p (ffi/alloc (* 4 (max 1 w) (max 1 h)))]
        ;; the canvas copied the old pixels when they were set, so it can go
        (when ptr (ffi/free ptr))
        (swap! frame assoc :ptr p :w w :h h :key nil)
        p))))

(defn- display
  "What to draw: the decoded image and its layers, or the embedded preview
   while the full image decodes."
  [doc layers]
  (cond
    (:base doc) {:img (:base doc) :layers (eng/layer-refs layers) :w (:w doc) :h (:h doc)}
    (:preview doc) {:img (:preview doc) :layers [] :preview? true
                    :w (px/width (:preview doc)) :h (px/height (:preview doc))}))

(defn- tool-mask
  "The mask the brush edits, when a mask tool is active."
  [layers tool]
  (when (#{:paint :erase} (:mode tool))
    (some #(when (= (:id %) (:layer tool)) (:mask %)) layers)))

(defn- overlay-mask [layers tool]
  (when (:show-mask tool) (tool-mask layers tool)))

(defn- draw!
  [{:keys [img layers w h preview?]} view overlay]
  (let [{:keys [cw ch mode]} view
        out (buffer! cw ch)
        view (if preview? (v/fit view w h) view)
        pane (v/pane-width view)
        go (fn [ls rect dx ov]
             (px/render! img ls {:ox (+ dx (:ox view)) :oy (:oy view) :scale (:scale view)
                                 :overlay ov :tint mask-tint
                                 :out out :out-w cw :out-h ch :rect rect :bg bg}))]
    (case mode
      :single (go layers [0 0 cw ch] 0 overlay)
      :split (let [sx (long (v/split-x view))]
               (go [] [0 0 sx ch] 0 nil)
               (go layers [sx 0 (- cw sx) ch] 0 overlay))
      :side (do (go [] [0 0 pane ch] 0 nil)
                (go layers [pane 0 (- cw pane) ch] pane overlay)))))

(defonce ^:private blank (delay (px/new-image 1 1)))

(defn- clear!
  "Fill the frame with the background colour: render a 1x1 image placed
   off-screen, so every pixel is outside it."
  [{:keys [cw ch]}]
  (px/render! @blank [] {:ox -10 :oy -10 :scale 1.0 :out (buffer! cw ch)
                         :out-w cw :out-h ch :bg bg}))

(defn frame-for
  "The rendered frame for the current state: {:ptr :w :h :version}.
   Re-renders only when the inputs changed."
  [{:keys [doc layers view tool pixels-rev]}]
  (let [d (display doc layers)
        overlay (overlay-mask layers tool)
        k [(:id doc) (some-> d :img) (:layers d) (dissoc view :zoom) overlay pixels-rev]]
    (when (not= k (:key @frame))
      (if d (draw! d view overlay) (clear! view))
      (swap! frame #(-> % (assoc :key k) (update :version inc))))
    @frame))

;; ---- overlay items -------------------------------------------------------------
(defn- label-item [id x y text]
  {:id id :type :text :coords [x y] :text text :fill [1 1 1 0.85] :anchor 4})

(defn- compare-items
  "Divider and Before/After labels for the split and side-by-side modes."
  [{:keys [ch mode] :as view}]
  (case mode
    :split (let [x (v/split-x view)]
             [{:id 2 :type :line :coords [x 0 x ch] :fill [1 1 1 0.9] :width 2}
              (label-item 3 12 18 "Before")
              (label-item 4 (+ 12 x) 18 "After")])
    :side (let [x (v/pane-width view)]
            [{:id 2 :type :line :coords [x 0 x ch] :fill [0.1 0.1 0.1 1] :width 2}
             (label-item 3 12 18 "Before")
             (label-item 4 (+ x 12) 18 "After")])
    []))

(defn- brush-item [view tool [x y]]
  (let [r (* (:radius tool) (:scale view))]
    {:id 5 :type :oval :coords [(- x r) (- y r) (+ x r) (+ y r)] :width 1.5
     :outline (if (= :erase (:mode tool)) [0.4 0.8 1 0.9] [1 1 1 0.9])}))

(defn- status-text [{:keys [cw ch]} doc]
  (let [msg (cond (nil? doc) "Add images to start  (RAW, JPEG, PNG, TIFF)"
                  (= :error (:status doc)) (str "Cannot open " (:name doc) ": " (:error doc))
                  (nil? (:base doc)) (str "Decoding " (:name doc) "…"))]
    (when msg
      {:id 6 :type :text :anchor 0 :text msg :fill [1 1 1 0.7]
       :coords [(/ cw 2.0) (if doc (- ch 24.0) (/ ch 2.0))]})))

(defn- overlay-items [doc view tool cursor]
  (cond-> (if doc (compare-items view) [])
    (and cursor (:base doc) (#{:paint :erase} (:mode tool))) (conj (brush-item view tool cursor))
    (status-text view doc) (conj (status-text view doc))))

;; ---- input ---------------------------------------------------------------------
(defonce ^:private drag (atom nil))

(defn- pane-xy
  "Canvas -> pane coordinates (the right pane in side-by-side mode)."
  [view x y]
  (let [pw (v/pane-width view)]
    (if (and (= :side (:mode view)) (>= x pw)) [(- x pw) y] [x y])))

(defn- stroke! [mask tool [x0 y0] [x1 y1]]
  (px/stroke! mask [x0 y0] [x1 y1] {:radius (:radius tool) :hardness (:hardness tool)
                                :flow (:flow tool) :erase? (= :erase (:mode tool))})
  (s/bump-pixels!))

(defn on-press [x y]
  (let [view @s/view* tool @s/tool* doc (s/current-doc)
        [px* py*] (pane-xy view x y)
        mask (when (:base doc) (tool-mask (s/layers (:id doc)) tool))]
    (cond
      (v/on-split? view x) (reset! drag {:kind :split})
      mask (let [p (v/->image view px* py*)]
             (stroke! mask tool p p)
             (reset! drag {:kind :paint :mask mask :last p}))
      :else (reset! drag {:kind :pan :x x :y y}))))

(defn on-motion [x y]
  (reset! s/cursor* [x y])
  (when-let [{:keys [kind] :as dr} @drag]
    (let [view @s/view*]
      (case kind
        :pan (do (swap! s/view* v/pan (- x (:x dr)) (- y (:y dr)))
                 (swap! drag assoc :x x :y y))
        :split (swap! s/view* assoc :split (min 0.95 (max 0.05 (/ x (double (:cw view))))))
        :paint (let [[px* py*] (pane-xy view x y)
                     p (v/->image view px* py*)]
                 (stroke! (:mask dr) @s/tool* (:last dr) p)
                 (swap! drag assoc :last p))))))

(defn on-release [_ _]
  (when (= :paint (:kind @drag))
    (let [tool @s/tool*]
      (when-let [id @s/current*]
        (s/edit! id d/bump-mask (:layer tool)))))
  (reset! drag nil))

(defn on-wheel [delta]
  (let [view @s/view*
        [x y] (or @s/cursor* [(/ (:cw view) 2.0) (/ (:ch view) 2.0)])
        [px* py*] (pane-xy view x y)]
    (swap! s/view* v/zoom-at (Math/pow 1.15 (double delta)) px* py*)))

(defn on-double [_ _]
  (when-let [{:keys [w h]} (s/current-doc)]
    (when w
      (if (= :fit (:zoom @s/view*))
        (swap! s/view* v/set-scale 1.0)
        (swap! s/view* v/fit w h)))))

(defn on-configure [w h]
  (let [w (long w) h (long h)]
    (when (and (pos? w) (pos? h))
      (swap! s/view* assoc :cw w :ch h)
      (when-let [{dw :w dh :h} (s/current-doc)]
        (when (and dw (= :fit (:zoom @s/view*)))
          (swap! s/view* v/fit dw dh))))))

;; ---- component -----------------------------------------------------------------
(defn image-view []
  (let [doc (s/current-doc)
        layers (when doc (s/layers (:id doc)))
        view @s/view*
        tool @s/tool*
        rev @s/pixels-rev*
        cursor @s/cursor*
        {:keys [ptr w h version]} (frame-for {:doc doc :layers layers :view view :tool tool :pixels-rev rev})]
    [:canvas {:stretch true :events 1
              :on-configure on-configure :on-press on-press :on-motion on-motion
              :on-release on-release :on-wheel on-wheel :on-double on-double
              :on-leave #(reset! s/cursor* nil)
              :canvas-items (into [{:id 1 :type :image :coords [0 0 w h] :rgba ptr
                                    :iw w :ih h :fit 0 :version version}]
                                  (overlay-items doc view tool cursor))}]))
