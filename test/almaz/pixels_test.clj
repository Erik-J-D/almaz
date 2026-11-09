(ns almaz.pixels-test
  "The native pixel engine (native/img): images, masks, compositing, tiles,
   estimators and file I/O. Needs native/img/build.sh."
  (:require [clojure.test :refer [deftest is testing]]
            [almaz.infer.tile :as tile]
            [almaz.pixels :as px]
            [jolt.ffi :as ffi]))

(defn- close? [eps a b] (< (Math/abs (- (double a) (double b))) eps))

(defn- filled [w h f]
  (let [img (px/new-image w h)
        data (float-array (* w h 3))]
    (dotimes [y h]
      (dotimes [x w]
        (let [[r g b] (f x y)
              i (* 3 (+ (* y w) x))]
          (aset data i (float r))
          (aset data (inc i) (float g))
          (aset data (+ i 2) (float b)))))
    (px/set-pixels! img data)
    img))

(defn- gauss
  "A normal sample (Box-Muller; jolt's Random has no nextGaussian)."
  [^java.util.Random rng sigma]
  (let [u1 (max 1e-12 (.nextDouble rng)) u2 (.nextDouble rng)]
    (* sigma (Math/sqrt (* -2.0 (Math/log u1))) (Math/cos (* 2.0 Math/PI u2)))))

(deftest image-roundtrip
  (let [img (filled 4 3 (fn [x y] [(/ x 3.0) (/ y 2.0) 0.5]))]
    (is (= [4 3] [(px/width img) (px/height img)]))
    (is (every? true? (map (partial close? 1e-4) [1.0 0.5 0.5] (px/pixel img 3 1))))
    (px/free-image! img)))

(defn- render-1px
  "Render the stack scaled so the whole w x h image lands in one pixel."
  [base layers]
  (let [out (ffi/alloc 4)]
    (px/render! base layers {:ox 0 :oy 0 :scale (/ 1.0 (px/width base))
                             :out out :out-w 1 :out-h 1})
    (let [v (mapv #(bit-and 0xff %) (ffi/read-array out 4))]
      (ffi/free out)
      v)))

(deftest compositing
  (let [black (filled 8 8 (constantly [0 0 0]))
        white (filled 8 8 (constantly [1 1 1]))]
    (testing "opacity blends the layer over the base"
      (is (= [128 128 128 255] (render-1px black [{:img white :opacity 0.5 :enabled true}])))
      (is (= [0 0 0 255] (render-1px black [{:img white :opacity 1.0 :enabled false}]))))
    (testing "a mask limits where the layer applies"
      (let [m (px/new-mask 8 8 1 0.0)]
        (is (= [0 0 0 255] (render-1px black [{:img white :mask m :opacity 1.0 :enabled true}])))
        (px/mask-fill! m 1.0)
        (is (= [255 255 255 255] (render-1px black [{:img white :mask m :opacity 1.0 :enabled true}])))
        (px/free-mask! m)))
    (doseq [i [black white]] (px/free-image! i))))

(def neutral {:exposure 0.0 :contrast 0.0 :highlights 0.0 :shadows 0.0 :whites 0.0 :blacks 0.0 :saturation 0.0})

(defn- tone [params rgb] (px/apply-tone (merge neutral params) rgb))
(defn- grey [params v] (first (tone params [v v v])))

(deftest tone-curve
  (testing "neutral settings change nothing"
    (doseq [c [[0.0 0.0 0.0] [0.2 0.5 0.9] [1.0 1.0 1.0]]]
      (is (every? true? (map (partial close? 1e-3) c (tone {} c))) (pr-str c))))
  (testing "exposure scales linear light by 2^EV"
    ;; sRGB 0.5 is 0.214 linear; doubled, 0.428 linear is sRGB 0.686
    (is (close? 2e-3 0.686 (grey {:exposure 1.0} 0.5)))
    (is (close? 2e-3 0.5 (grey {:exposure -1.0} 0.686))))
  (testing "contrast steepens around the middle"
    (is (< (grey {:contrast 0.5} 0.25) 0.25))
    (is (> (grey {:contrast 0.5} 0.75) 0.75))
    (is (close? 1e-3 0.5 (grey {:contrast 0.5} 0.5)))
    (is (> (grey {:contrast -0.5} 0.25) 0.25)))
  (testing "shadows work on the dark end, highlights on the bright end"
    (let [up (- (grey {:shadows 1.0} 0.25) 0.25)]
      (is (pos? up))
      (is (< (- (grey {:shadows 1.0} 0.8) 0.8) (/ up 2))))
    (let [down (- 0.75 (grey {:highlights -1.0} 0.75))]
      (is (pos? down))
      (is (< (- 0.2 (grey {:highlights -1.0} 0.2)) (/ down 2)))))
  (testing "whites move the white point, blacks the black point"
    (is (> (grey {:whites 0.5} 0.9) 0.9))
    (is (< (grey {:blacks -0.5} 0.05) 0.05))
    (is (> (grey {:blacks 0.5} 0.0) 0.0)))
  (testing "every setting keeps the curve increasing and inside 0..1"
    (doseq [k (keys (dissoc neutral :saturation)) v [-1.0 1.0]]
      (let [ys (map #(grey {k v} (/ % 64.0)) (range 65))]
        (is (apply <= ys) (str k " " v))
        (is (every? #(<= 0.0 % 1.0) ys) (str k " " v)))))
  (testing "saturation scales colour away from (or towards) grey"
    (let [[r g b] (tone {:saturation -1.0} [0.8 0.4 0.2])]
      (is (close? 1e-3 r g))
      (is (close? 1e-3 g b)))
    (let [[r _ b] (tone {:saturation 0.5} [0.6 0.4 0.3])]
      (is (> (- r b) 0.3)))
    (is (every? true? (map (partial close? 1e-3) [0.5 0.5 0.5] (tone {:saturation 1.0} [0.5 0.5 0.5]))))))

(deftest light-layers
  (let [mid (filled 8 8 (constantly [0.5 0.5 0.5]))
        brighter (merge neutral {:exposure 1.0})
        v (fn [layers] (first (render-1px mid layers)))]
    (testing "an adjustment layer needs no pixels of its own"
      (is (= 175 (v [{:tone brighter :opacity 1.0 :enabled true}]))))
    (testing "it blends through its opacity and mask like any layer"
      (is (= 128 (v [{:tone brighter :opacity 1.0 :enabled false}])))
      (is (< 128 (v [{:tone brighter :opacity 0.5 :enabled true}]) 175))
      (let [m (px/new-mask 8 8 1 0.0)]
        (is (= 128 (v [{:tone brighter :mask m :opacity 1.0 :enabled true}])))
        (px/free-mask! m)))
    (px/free-image! mid)))

(deftest histograms
  (let [img (filled 64 32 (fn [x _] (if (< x 16) [0 0 0] [1 0.5 0])))
        {:keys [r g b l]} (px/histogram img [])]
    (testing "256 bins per channel, every pixel counted once"
      (is (every? #(= 256 (count %)) [r g b l]))
      (is (every? #(= (* 64 32) (reduce + %)) [r g b l])))
    (testing "each channel's tones land in their bins"
      (is (= (* 16 32) (nth r 0)))
      (is (= (* 48 32) (nth r 255)))
      (is (= (* 48 32) (nth g 128)))
      (is (= (* 64 32) (nth b 0))))
    (testing "the adjustment layers on top are included"
      (let [h (px/histogram img [{:tone (merge neutral {:saturation -1.0}) :opacity 1.0 :enabled true}])]
        (is (= (* 48 32) (reduce + (subvec (:b h) 1))))))
    (px/free-image! img)))

(deftest pyramid
  (testing "zoomed out, the render averages (no aliasing)"
    (let [checker (filled 64 64 (fn [x y] (if (even? (+ x y)) [1 1 1] [0 0 0])))
          v (render-1px checker [])]
      (is (close? 2 127.5 (first v)))
      (px/free-image! checker))))

(deftest masks
  (let [m (px/new-mask 400 400 4 0.0)]
    (px/stroke! m [200 200] [200 200] {:radius 80 :hardness 0.5 :flow 1.0})
    (testing "the brush core is solid and falls off smoothly to nothing"
      (is (close? 1e-3 1.0 (px/mask-at m 200 200)))
      (let [profile (mapv #(px/mask-at m (+ 200 %) 200) (range 0 120 8))]
        (is (apply >= profile) "monotonic falloff")
        (is (some #(< 0.1 % 0.9) profile) "a soft edge, not a step")
        (is (close? 1e-3 0.0 (last profile)))))
    (testing "feathering widens the transition"
      (let [edge #(count (filter (fn [v] (< 0.05 v 0.95))
                                 (map (fn [d] (px/mask-at m (+ 200 d) 200)) (range 0 200 2))))
            before (edge)]
        (px/mask-feather! m 40.0)
        (is (> (edge) before))))
    (testing "erasing removes, invert flips"
      (px/stroke! m [200 200] [200 200] {:radius 30 :hardness 0.9 :flow 1.0 :erase? true})
      (is (< (px/mask-at m 200 200) 0.6))
      (px/mask-fill! m 0.0)
      (px/mask-invert! m)
      (is (close? 1e-4 1.0 (px/mask-at m 10 10))))
    (px/free-mask! m)))

(deftest tiles-are-seamless
  (testing "writing every tile of an image back, in any order, reproduces it"
    (let [rng (java.util.Random. 7)
          w 300 h 220 block 128 overlap 32
          src (filled w h (fn [x y] [(+ 0.5 (* 0.4 (Math/sin (/ x 9.0)))) (/ y (double h))
                                     (+ 0.5 (gauss rng 0.1))]))
          dst (px/new-image w h)
          g (tile/grid w h block overlap)
          buf (ffi/alloc (* 4 3 block block))]
      (loop [order (shuffle g) done #{}]
        (when-let [t (first order)]
          (px/tile-extract! src [] t buf)
          (px/tile-write! dst buf t (tile/ramps g t done))
          (recur (rest order) (conj done (:i t)))))
      (let [diffs (for [y (range 0 h 7) x (range 0 w 7)]
                    (apply max (map #(Math/abs (- %1 %2)) (px/pixel src x y) (px/pixel dst x y))))]
        (is (< (apply max diffs) 1e-4)))
      (ffi/free buf)
      (px/free-image! src)
      (px/free-image! dst))))

(deftest tile-extract-composites
  (testing "a tile reads the composite of the layers below"
    (let [black (filled 16 16 (constantly [0 0 0]))
          white (filled 16 16 (constantly [1 1 1]))
          buf (ffi/alloc (* 4 3 16 16))]
      (px/tile-extract! black [{:img white :opacity 0.25 :enabled true}] {:x 0 :y 0 :w 16 :h 16} buf)
      (is (close? 1e-3 0.25 (first (ffi/read-array buf :float 1))))
      (ffi/free buf)
      (px/free-image! black)
      (px/free-image! white))))

(deftest estimator
  (testing "noise estimate tracks the added noise"
    (let [rng (java.util.Random. 1)
          clean (filled 256 256 (fn [x _] (repeat 3 (+ 0.3 (* 0.4 (/ x 256.0))))))
          noisy (filled 256 256 (fn [x _] (repeat 3 (+ 0.3 (* 0.4 (/ x 256.0)) (gauss rng 0.03)))))
          a (px/estimate clean) b (px/estimate noisy)]
      (is (< (:noise a) 0.005))
      (is (close? 0.01 0.03 (:noise b)))
      (px/free-image! clean)
      (px/free-image! noisy))))

(deftest file-io
  (let [img (filled 40 30 (fn [x y] [(/ x 40.0) (/ y 30.0) 0.25]))
        dir (str (or (System/getenv "TMPDIR") "/tmp/") "almaz-test-" (System/currentTimeMillis))]
    (.mkdirs (java.io.File. dir))
    (doseq [[fmt ext eps] [[:png16 "png" 1e-4] [:tiff16 "tif" 1e-4] [:png8 "png" 0.004] [:jpeg "jpg" 0.03]]]
      (testing (name fmt)
        (let [path (str dir "/out-" (name fmt) "." ext)]
          (px/save-image! img [] {:path path :format fmt :quality 95})
          (let [back (px/load-image path)]
            (is (= [40 30] [(px/width back) (px/height back)]))
            (is (every? true? (map (partial close? eps) (px/pixel img 20 15) (px/pixel back 20 15))))
            (px/free-image! back)))))
    (testing "the file appears only once it's complete: written aside, then renamed"
      (let [path (str dir "/atomic.png")]
        (spit path "previous export")
        ;; the side file can't be created, so the write fails before the rename
        (.mkdirs (java.io.File. (str path ".part")))
        (is (thrown? Exception (px/save-image! img [] {:path path :format :png16 :quality 95})))
        (is (= "previous export" (slurp path)) "a failed export leaves the old file alone")
        (.delete (java.io.File. (str path ".part")))
        (px/save-image! img [] {:path path :format :png16 :quality 95})
        (is (= [40 30] (let [b (px/load-image path)] (try [(px/width b) (px/height b)] (finally (px/free-image! b))))))
        (is (not (.exists (java.io.File. (str path ".part")))))))
    (testing "a missing file is an error, not a crash"
      (is (thrown? Exception (px/load-image (str dir "/nope.png")))))
    (px/free-image! img)))

(def sample-meta
  {:make "NIKON CORPORATION" :model "NIKON Z 9" :lens "NIKKOR Z 180-600mm f/5.6-6.3 VR"
   :taken "2026:10:10 11:30:31" :exposure 0.0005 :fnumber 8.0 :iso 5000
   :focal 480.0 :focal35 480 :orientation 1})

(defn- meta-close? [a b]
  (and (= (dissoc a :exposure :fnumber :focal) (dissoc b :exposure :fnumber :focal))
       (every? #(< (Math/abs (- (double (% a)) (double (% b)))) 1e-6) [:exposure :fnumber :focal])))

(deftest metadata-survives-export
  (let [img (filled 16 12 (constantly [0.5 0.5 0.5]))
        dir (str (or (System/getenv "TMPDIR") "/tmp/") "almaz-meta-" (System/currentTimeMillis))]
    (.mkdirs (java.io.File. dir))
    (doseq [[fmt ext] [[:jpeg "jpg"] [:png16 "png"] [:png8 "png"] [:tiff16 "tif"]]]
      (testing (name fmt)
        (let [path (str dir "/m-" (name fmt) "." ext)]
          (px/save-image! img [] {:path path :format fmt :quality 90 :meta sample-meta})
          (let [back (px/read-meta path)]
            (is (meta-close? sample-meta back) (pr-str back))))))
    (testing "no metadata in, none out (and reading it is not an error)"
      (let [path (str dir "/bare.jpg")]
        (px/save-image! img [] {:path path :format :jpeg :quality 90})
        (is (nil? (px/read-meta path)))))
    (testing "partial metadata writes what it has"
      (let [path (str dir "/partial.jpg")]
        (px/save-image! img [] {:path path :format :jpeg :quality 90 :meta {:make "X" :iso 100}})
        (is (= {:make "X" :iso 100} (select-keys (px/read-meta path) [:make :iso])))))
    (px/free-image! img)))

(deftest native-abi
  (testing "the library matches the struct layouts this code writes"
    (is (nil? (px/abi-problem))))
  (testing "a mismatch names both versions and the fix"
    (let [msg (px/abi-problem (inc px/abi))]
      (is (re-find #"rebuild" msg))
      (is (re-find (re-pattern (str (inc px/abi))) msg)))))
