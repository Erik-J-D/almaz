(ns almaz.engine
  "Runs one filter layer over the composite below it, tile by tile.

   The tiles nearest the focus point (the centre of the viewport) go first,
   each blended into the layer's output with almaz.infer.tile's crossfades, so
   the result shows up where the user is looking and grows outwards. All pixel
   work is native: tile extraction (which composites the layers below on the
   fly), inference straight from native buffers, and the crossfaded write."
  (:require [almaz.infer.protocol :as p]
            [almaz.infer.tile :as tile]
            [almaz.pixels :as px]
            [jolt.ffi :as ffi]))

(defn layer-refs
  "A doc's layers as the pixel engine's stack entries."
  [layers]
  (mapv (fn [{:keys [img mask opacity enabled]}]
          {:img img :mask mask :opacity opacity :enabled enabled})
        layers))

(defn- model-op [{:keys [backend session spec]} detail]
  (let [{:keys [block overlap inputs outputs]} spec
        shape [1 3 block block]
        n (* 3 block block)]
    {:block block :overlap overlap
     :run (fn [in out _]
            (p/run-native backend session
                          [{:name (first inputs) :shape shape :ptr in :count n}]
                          [{:name (first outputs) :ptr out :count n}])
            (when (pos? detail)
              (px/detail! out in block {:sigma 1.5 :amount detail})))}))

(defn op-for
  "The tile operation for a layer row: {:block :overlap :run (fn [in out n])},
   where `run` turns the planar float tile at `in` into `out` (n floats).
   `session` is the loaded model session (almaz.models/session) for AI filters."
  [{:keys [kind model detail amount radius]} session]
  (case [kind model]
    [:denoise :ai] (model-op session (or detail 0.0))
    [:sharpen :ai] (model-op session 0.0)
    [:sharpen :classic]
    (let [block 256
          radius (double (or radius 1.5))]
      {:block block :overlap (max 16 (long (* 8 radius)))
       :run (fn [in out n]
              (ffi/copy in out (* 4 n))
              (px/unsharp! out block {:sigma radius :amount (or amount 1.0)}))})))

(defn model-id
  "Which model a layer needs, or nil for a classic filter."
  [{:keys [kind model]}]
  (when (= :ai model) kind))

(def ^:private empty-mask 1e-3)

(defn- tile-step!
  "Run one tile into `out`; false when the mask leaves it untouched (skipped).
   `ctx` is the process! opts plus the grid and the two tile buffers."
  [{:keys [base below out mask g in res n] {:keys [run]} :op} t done]
  (if (and mask (< (px/mask-max mask t) empty-mask))
    false
    (do (px/tile-extract! base below t in)
        (run in res n)
        (px/tile-write! out res t (tile/ramps g t done))
        true)))

(defn process!
  "Filter the composite of `base` + `below` into the image `out`, tile by tile.
   opts: :op (op-for), :focus [x y] (image coords to start from), :mask (the
   layer's mask: tiles it leaves untouched are skipped), :progress
   (fn [done total]) after each tile, :cancelled? (fn []) checked between
   tiles. Returns :done, :partial (some tiles skipped) or :cancelled."
  [{:keys [base op focus progress cancelled?]
    :or {progress (fn [_ _]) cancelled? (constantly false)} :as opts}]
  (let [{:keys [block overlap]} op
        w (px/width base) h (px/height base)
        g (tile/grid w h block overlap)
        n (* 3 block block)
        in (ffi/alloc (* 4 n))
        res (ffi/alloc (* 4 n))]
    (try
      (loop [[t & more] (tile/by-focus g (or focus [(/ w 2) (/ h 2)])) done #{} seen 0 skipped? false]
        (cond
          (nil? t) (if skipped? :partial :done)
          (cancelled?) :cancelled
          :else (let [ran? (tile-step! (assoc opts :g g :in in :res res :n n) t done)]
                  (progress (inc seen) (count g))
                  (recur more (if ran? (conj done (:i t)) done) (inc seen) (or skipped? (not ran?))))))
      (finally
        (ffi/free in)
        (ffi/free res)))))
