(ns almaz.infer.tile
  "Tile planning for fixed-size models, backend-agnostic and pure.

   Restoration nets here take one fixed input size (CoreML compiles static
   shapes best), so an image is covered by `block`-sized windows overlapping by
   at least `overlap` px; the last window on an axis is pulled back to end at
   the image edge, and an axis shorter than a block gets one window that the
   extractor pads by mirroring.

   Tiles may finish in any order (the ones under the viewport go first). Each
   tile crossfades only towards neighbours that are already written: on such a
   side it fades in across the shared overlap, elsewhere it overwrites, and the
   neighbour that comes later fades in over it. Either way every overlap ends
   up as a linear crossfade, so there are no seams. This reproduces the
   concept of block tiling with overlap, independently of any vendor format.")

(defn block-bounds
  "Tile the integer range [0 n) into `block`-sized windows with `overlap` px of
   overlap. Returns a vector of [start end) windows whose union covers [0 n)
   with no gaps (the final end clamps to n). A single window when block >= n."
  [n block overlap]
  (if (>= block n)
    [[0 n]]
    (let [step (- block overlap)]
      (mapv (fn [s] [s (min n (+ s block))])
            (loop [s 0 acc []]
              (if (>= s n) acc (recur (+ s step) (conj acc s))))))))

(defn fixed-bounds
  "Windows of exactly `block` px covering [0 n), the fewest that keep every
   neighbour overlap >= `overlap`, spread evenly (the first starts at 0, the
   last ends at n). When n <= block, one window [0 block) (the part past n is
   padding)."
  [n block overlap]
  (if (<= n block)
    [[0 block]]
    (let [k (long (Math/ceil (/ (double (- n overlap)) (- block overlap))))
          span (- n block)]
      (mapv (fn [i] (let [s (Math/round (/ (* i span) (double (dec k))))]
                      [s (+ s block)]))
            (range k)))))

(defn grid
  "Every tile of a w x h image, row-major: {:i :col :row :x :y :w :h}."
  [w h block overlap]
  (let [xs (fixed-bounds w block overlap)
        ys (fixed-bounds h block overlap)]
    (vec (for [[row [y0 _]] (map-indexed vector ys)
               [col [x0 _]] (map-indexed vector xs)]
           {:i (+ (* row (count xs)) col) :col col :row row
            :x x0 :y y0 :w block :h block}))))

(defn- at [g col row]
  (some #(when (and (= col (:col %)) (= row (:row %))) %) g))

(defn ramps
  "How far `tile` fades in on each side: the overlap with that side's
   neighbour when the neighbour's index is in `done`, else 0."
  [g tile done]
  (let [{:keys [col row x y w h]} tile
        side (fn [n overlap] (if (and n (contains? done (:i n))) (max 0 (overlap n)) 0))]
    {:l (side (at g (dec col) row) #(- (+ (:x %) (:w %)) x))
     :t (side (at g col (dec row)) #(- (+ (:y %) (:h %)) y))
     :r (side (at g (inc col) row) #(- (+ x w) (:x %)))
     :b (side (at g col (inc row)) #(- (+ y h) (:y %)))}))

(defn by-focus
  "The tiles ordered by distance from their centre to the point [fx fy], so
   the part of the image being looked at is processed first."
  [g [fx fy]]
  (sort-by (fn [{:keys [x y w h]}]
             (let [dx (- (+ x (/ w 2.0)) fx) dy (- (+ y (/ h 2.0)) fy)]
               (+ (* dx dx) (* dy dy))))
           g))
