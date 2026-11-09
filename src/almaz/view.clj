(ns almaz.view
  "Viewport math for the image pane. Pure.

   A view is {:mode :single|:split|:side, :cw :ch (canvas size, raster px),
   :scale (screen px per image px), :ox :oy (screen position of the image
   origin inside a pane), :zoom :fit|:manual, :split (divider position 0..1)}.
   In :side mode the canvas holds two panes (before | after), each half the
   width, showing the same image region.")

(def min-scale 0.01)
(def max-scale 32.0)
(def margin "Gap kept around a fitted image, px." 12)

(defn pane-width [{:keys [mode cw]}]
  (if (= mode :side) (quot cw 2) cw))

(defn ->image
  "Pane coordinates -> image coordinates."
  [{:keys [scale ox oy]} sx sy]
  [(/ (- sx ox) scale) (/ (- sy oy) scale)])

(defn ->screen
  "Image coordinates -> pane coordinates."
  [{:keys [scale ox oy]} ix iy]
  [(+ ox (* ix scale)) (+ oy (* iy scale))])

(defn fit
  "Scale and centre the whole image inside the pane."
  [view iw ih]
  (let [pw (pane-width view) ph (:ch view)
        s (max min-scale (min (/ (- pw (* 2 margin)) (double iw))
                              (/ (- ph (* 2 margin)) (double ih))))]
    (assoc view :scale s :zoom :fit
           :ox (/ (- pw (* s iw)) 2.0)
           :oy (/ (- ph (* s ih)) 2.0))))

(defn zoom-at
  "Multiply the scale by `factor`, keeping the image point under pane
   position (sx, sy) fixed."
  [view factor sx sy]
  (let [[ix iy] (->image view sx sy)
        s (min max-scale (max min-scale (* (:scale view) factor)))]
    (assoc view :scale s :zoom :manual
           :ox (- sx (* ix s))
           :oy (- sy (* iy s)))))

;; ---- smooth zoom -----------------------------------------------------------------
;; The window kit reports scrolling as whole wheel steps (a trackpad's precise
;; deltas arrive as one step per 12 points), so applying each step directly
;; zooms in visible jumps. Steps move a target scale instead, and the view
;; eases towards it a frame at a time.

(def wheel-factor "Zoom per wheel step." 1.1)

(defn wheel-target
  "The target scale after `steps` wheel steps from `scale`."
  [scale steps]
  (min max-scale (max min-scale (* scale (Math/pow wheel-factor steps)))))

(defn settled?
  "Is the view's scale within half a percent of `target`?"
  [{:keys [scale]} target]
  (< (Math/abs (Math/log (/ target scale))) 0.005))

(defn zoom-toward
  "One animation frame: cover fraction `k` of the way from the view's scale
   to `target` (geometrically), keeping pane point `anchor` [sx sy] fixed;
   snap to the target once close."
  [view target [sx sy] k]
  (let [r (/ target (:scale view))
        f (Math/pow r k)
        f (if (settled? (update view :scale * f) target) r f)]
    (zoom-at view f sx sy)))

(defn set-scale
  "Set an absolute scale about the pane centre."
  [view s]
  (zoom-at view (/ s (:scale view)) (/ (pane-width view) 2.0) (/ (:ch view) 2.0)))

(defn pan [view dx dy]
  (-> view
      (update :ox + dx)
      (update :oy + dy)
      (assoc :zoom :manual)))

(defn visible-rect
  "The image rectangle [x y w h] the pane shows, clipped to the image."
  [view iw ih]
  (let [[x0 y0] (->image view 0 0)
        [x1 y1] (->image view (pane-width view) (:ch view))
        cx0 (max 0.0 (double x0)) cy0 (max 0.0 (double y0))
        cx1 (min (double iw) (double x1)) cy1 (min (double ih) (double y1))]
    [cx0 cy0 (max 0.0 (- cx1 cx0)) (max 0.0 (- cy1 cy0))]))

(defn focus
  "The image point at the centre of the pane, clamped to the image."
  [view iw ih]
  (let [[x y] (->image view (/ (pane-width view) 2.0) (/ (:ch view) 2.0))]
    [(min (max 0 x) iw) (min (max 0 y) ih)]))

(defn split-x [{:keys [cw split]}] (* cw split))

(defn on-split?
  "Is canvas x on the split divider (split mode only)?"
  [view x]
  (and (= :split (:mode view)) (< (Math/abs (- x (split-x view))) 6)))

(defn zoom-label
  "The scale as a percentage string."
  [{:keys [scale]}]
  (str (Math/round (* 100.0 scale)) "%"))
