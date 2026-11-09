(ns almaz.pixels
  "The native pixel engine (native/img/libalmazimg): 16-bit images with a mip
   pyramid, low-resolution feathered masks, layer-stack compositing and
   viewport rendering, tiled-inference helpers, estimators and file I/O.

   Images and masks are native handles (pointers). One defcfn per C function,
   then thin wrappers that marshal Clojure values: a layer stack is a seq of
   {:img :mask :opacity :enabled} maps, written into a C AlmzLayer array.
   Long calls are :blocking so a worker thread in C doesn't pin the GC."
  (:require [jolt.ffi :as ffi]))

;; ---- bindings ----------------------------------------------------------------
(ffi/defcfn img-new*        "almz_img_new"        [:int32 :int32] :pointer :blocking)
(ffi/defcfn img-wrap*       "almz_img_wrap"       [:int32 :int32 :pointer] :pointer :blocking)
(ffi/defcfn img-free*       "almz_img_free"       [:pointer] :void)
(ffi/defcfn img-width*      "almz_img_width"      [:pointer] :int32)
(ffi/defcfn img-height*     "almz_img_height"     [:pointer] :int32)
(ffi/defcfn img-get*        "almz_img_get"        [:pointer :int32 :int32 :pointer] :void)
(ffi/defcfn img-set-f32*    "almz_img_set_f32"    [:pointer :pointer] :void :blocking)

(ffi/defcfn mask-new*       "almz_mask_new"       [:int32 :int32 :int32 :float] :pointer)
(ffi/defcfn mask-free*      "almz_mask_free"      [:pointer] :void)
(ffi/defcfn mask-fill*      "almz_mask_fill"      [:pointer :float] :void)
(ffi/defcfn mask-invert*    "almz_mask_invert"    [:pointer] :void :blocking)
(ffi/defcfn mask-feather*   "almz_mask_set_feather" [:pointer :float] :void :blocking)
(ffi/defcfn mask-stroke*    "almz_mask_stroke"
  [:pointer :double :double :double :double :double :double :double :int32] :void)
(ffi/defcfn mask-max*       "almz_mask_max"       [:pointer :int32 :int32 :int32 :int32] :float :blocking)
(ffi/defcfn mask-sample*    "almz_mask_sample"    [:pointer :double :double] :float)

(ffi/defcfn render*         "almz_render"
  [:pointer :pointer :int32 :double :double :double :pointer :pointer
   :pointer :int32 :int32 :int32 :int32 :int32 :int32 :pointer] :void)

(ffi/defcfn composite-into* "almz_composite_into" [:pointer :pointer :pointer :int32] :void :blocking)
(ffi/defcfn tile-extract*   "almz_tile_extract"
  [:pointer :pointer :int32 :int32 :int32 :int32 :int32 :pointer] :void :blocking)
(ffi/defcfn tile-write*     "almz_tile_write"
  [:pointer :pointer :int32 :int32 :int32 :int32 :int32 :int32 :int32 :int32] :void :blocking)
(ffi/defcfn tile-unsharp*   "almz_tile_unsharp"   [:pointer :int32 :int32 :float :float] :void :blocking)
(ffi/defcfn tile-detail*    "almz_tile_detail"
  [:pointer :pointer :int32 :int32 :float :float] :void :blocking)

(ffi/defcfn estimate*       "almz_estimate"       [:pointer :pointer :pointer] :void :blocking)

(ffi/defcfn load*           "almz_load"           [:pointer :pointer] :pointer :blocking)
(ffi/defcfn load-jpeg-mem*  "almz_load_jpeg_mem"  [:pointer :size_t :pointer] :pointer :blocking)
(ffi/defcfn from-rgb8*      "almz_from_rgb8"      [:pointer :int32 :int32] :pointer :blocking)
(ffi/defcfn save*           "almz_save"
  [:pointer :pointer :int32 :pointer :int32 :int32 :pointer :pointer] :int32 :blocking)
(ffi/defcfn meta-read*      "almz_meta_read"      [:pointer :pointer] :int32 :blocking)
(ffi/defcfn free-string*    "almz_free_string"    [:pointer] :void)

;; ---- errors ------------------------------------------------------------------
(defn- call-with-err
  "Call (f errslot); when it answers NULL / non-zero, throw the shim's message."
  [what f ok?]
  (with-open [a (ffi/confined-arena)]
    (let [errslot (ffi/alloc a :pointer)]
      (ffi/write errslot :pointer ffi/null 0)
      (let [r (f errslot)]
        (if (ok? r)
          r
          (let [ep (ffi/read errslot :pointer 0)
                msg (when-not (ffi/null? ep)
                      (let [m (ffi/ptr->string ep)] (free-string* ep) m))]
            (throw (ex-info (str what ": " (or msg "failed")) {}))))))))

(defn- non-null? [p] (and p (not (ffi/null? p))))

;; ---- images ------------------------------------------------------------------
(defn new-image [w h] (img-new* (int w) (int h)))
(defn wrap-image
  "Adopt a malloc'd w*h*3 uint16 buffer as an image (takes ownership)."
  [w h data] (img-wrap* (int w) (int h) data))
(defn free-image! [img] (when (non-null? img) (img-free* img)))
(defn width [img] (img-width* img))
(defn height [img] (img-height* img))

(defn pixel
  "Level-0 pixel as [r g b] floats 0..1."
  [img x y]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a (* 4 3))]
      (img-get* img (int x) (int y) p)
      (mapv double (ffi/read-array p :float 3)))))

(defn set-pixels!
  "Overwrite the image from interleaved RGB floats 0..1 (a float-array)."
  [img data]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a (* 4 (alength data)))]
      (ffi/write-array p :float data)
      (img-set-f32* img p))))

;; ---- masks -------------------------------------------------------------------
(defn new-mask
  "A mask for a w x h image stored at 1/div resolution, filled with `v`."
  [w h div v] (mask-new* (int w) (int h) (int div) (float v)))
(defn free-mask! [m] (when (non-null? m) (mask-free* m)))
(defn mask-fill! [m v] (mask-fill* m (float v)))
(defn mask-invert! [m] (mask-invert* m))
(defn mask-feather!
  "Set the feather (gaussian sigma, image px) and rebuild the soft mask."
  [m sigma] (mask-feather* m (float sigma)))
(defn mask-max
  "The largest feathered mask value over the image rectangle."
  [m {:keys [x y w h]}]
  (double (mask-max* m (int x) (int y) (int w) (int h))))

(defn mask-at [m x y] (double (mask-sample* m (double x) (double y))))

(defn stroke!
  "A soft round brush segment from [x0 y0] to [x1 y1], image coordinates."
  [m [x0 y0] [x1 y1] {:keys [radius hardness flow erase?] :or {hardness 0.5 flow 1.0}}]
  (mask-stroke* m (double x0) (double y0) (double x1) (double y1)
                (double radius) (double hardness) (double flow) (if erase? 1 0)))

;; ---- layer stacks ------------------------------------------------------------
(def ^:private layer-layout
  (ffi/layout [:struct [[:img :pointer] [:mask :pointer] [:opacity :float] [:enabled :int32]]]))
(def ^:private layer-size (ffi/sizeof layer-layout))

(defn- write-layers
  "An AlmzLayer array for `layers` in `arena` (NULL when empty)."
  [arena layers]
  (if (empty? layers)
    ffi/null
    (let [arr (ffi/alloc arena (* layer-size (count layers)))]
      (doseq [[i {:keys [img mask opacity enabled]}] (map-indexed vector layers)]
        (ffi/write arr layer-layout
                   {:img (or img ffi/null) :mask (or mask ffi/null)
                    :opacity (float (or opacity 1.0))
                    :enabled (if (and enabled img) 1 0)}
                   (* i layer-size)))
      arr)))

;; ---- rendering ---------------------------------------------------------------
(defn render!
  "Render base + layers into the RGBA8 buffer `out` (out-w x out-h), within
   :rect [x y w h] (default: all of it). Screen = image * scale + (ox, oy).
   :overlay is a mask drawn as :tint [r g b strength]; :bg the RGBA outside
   the image."
  [base layers {:keys [ox oy scale overlay tint out out-w out-h rect bg]}]
  (with-open [a (ffi/confined-arena)]
    (let [[rx ry rw rh] (or rect [0 0 out-w out-h])
          larr (write-layers a layers)
          tp (when tint (let [p (ffi/alloc a (* 4 4))]
                          (ffi/write-array p :float (float-array (map float tint))) p))
          bgp (let [p (ffi/alloc a 4)
                    [r g b al] (or bg [0 0 0 255])]
                (ffi/write-array p (byte-array (map unchecked-byte [r g b al]))) p)]
      (render* base larr (int (count layers)) (double ox) (double oy) (double scale)
               (or overlay ffi/null) (or tp ffi/null)
               out (int out-w) (int out-h) (int rx) (int ry) (int rw) (int rh) bgp))))

;; ---- full-image composite ---------------------------------------------------
(defn composite-into!
  "Write the composite of base + layers into `dst` (an image the same size)."
  [dst base layers]
  (with-open [a (ffi/confined-arena)]
    (composite-into* dst base (write-layers a layers) (int (count layers)))))

;; ---- tiles -------------------------------------------------------------------
(defn tile-extract!
  "The composite of base + layers over `tile` {:x :y :w :h}, as planar floats
   into `dst`."
  [base layers {:keys [x y w h]} dst]
  (with-open [a (ffi/confined-arena)]
    (tile-extract* base (write-layers a layers) (int (count layers))
                   (int x) (int y) (int w) (int h) dst)))

(defn tile-write!
  "Crossfade the planar float tile `src` into `dst` at `tile` {:x :y :w :h}
   with ramps {:l :t :r :b}."
  [dst src {:keys [x y w h]} {:keys [l t r b]}]
  (tile-write* dst src (int w) (int h) (int x) (int y)
               (int (or l 0)) (int (or t 0)) (int (or r 0)) (int (or b 0))))

(defn unsharp!
  "Unsharp mask, in place, on a planar tile of `size` px."
  [t size {:keys [sigma amount]}]
  (tile-unsharp* t (int size) (int size) (float sigma) (float amount)))

(defn detail!
  "Put back `amount` of the input's fine detail into the output tile."
  [out in size {:keys [sigma amount]}]
  (tile-detail* out in (int size) (int size) (float sigma) (float amount)))

;; ---- estimators --------------------------------------------------------------
(defn estimate
  "{:noise sigma :sharpness score} — see almaz.autopilot."
  [img]
  (with-open [a (ffi/confined-arena)]
    (let [n (ffi/alloc a :float) s (ffi/alloc a :float)]
      (estimate* img n s)
      {:noise (double (ffi/read n :float 0)) :sharpness (double (ffi/read s :float 0))})))

;; ---- metadata ----------------------------------------------------------------
;; AlmzMeta (almazimg.h): the shooting metadata carried from a source file to
;; its exports, as a map {:make :model :lens :taken :exposure :fnumber :focal
;; :iso :focal35 :orientation} with absent fields left out.
(def meta-layout
  (ffi/layout [:struct [[:make [:array :uint8 64]] [:model [:array :uint8 64]]
                        [:lens [:array :uint8 128]] [:taken [:array :uint8 20]]
                        [:exposure :double] [:fnumber :double] [:focal :double]
                        [:iso :int32] [:focal35 :int32] [:orientation :int32]]]))

(def ^:private meta-strings {:make 64 :model 64 :lens 128 :taken 20})
(def ^:private meta-numbers {:exposure :double :fnumber :double :focal :double
                             :iso :int32 :focal35 :int32 :orientation :int32})

(defn read-meta-struct
  "An AlmzMeta struct at `p` as a map (unknown fields left out)."
  [p]
  (let [strs (keep (fn [[k _]]
                     (let [v (ffi/ptr->string (+ p (ffi/field-offset meta-layout k)))]
                       (when (seq v) [k v])))
                   meta-strings)
        nums (keep (fn [[k t]]
                     (let [v (ffi/read p t (ffi/field-offset meta-layout k))]
                       (when (pos? v) [k (if (= t :double) (double v) (long v))])))
                   meta-numbers)]
    (not-empty (into {} (concat strs nums)))))

(defn- write-meta-struct
  "A zeroed AlmzMeta in `arena` filled from map `m`."
  [arena m]
  (let [p (ffi/alloc arena meta-layout)]
    (doseq [[k cap] meta-strings
            :let [v (get m k)]
            :when (seq v)]
      (ffi/write-bytes (+ p (ffi/field-offset meta-layout k)) (subs v 0 (min (count v) (dec cap)))))
    (doseq [[k t] meta-numbers
            :let [v (get m k)]
            :when v]
      (ffi/write p t (if (= t :double) (double v) (int v)) (ffi/field-offset meta-layout k)))
    p))

(defn read-meta
  "The shooting metadata of a JPEG, PNG or TIFF-structured file (TIFF, NEF,
   ARW, DNG, CR2, ...), or nil when it has none."
  [path]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a meta-layout)]
      (when (pos? (meta-read* (ffi/string->ptr a path) p))
        (read-meta-struct p)))))

;; ---- files -------------------------------------------------------------------
(def formats {:png16 1 :png8 2 :jpeg 3 :tiff16 4})

(defn load-image
  "Decode a JPEG / PNG / TIFF into an image handle. Throws on failure."
  [path]
  (with-open [a (ffi/confined-arena)]
    (let [cpath (ffi/string->ptr a path)]
      (call-with-err (str "cannot load " path) #(load* cpath %) non-null?))))

(defn jpeg-bytes->image
  "Decode JPEG bytes at native pointer `p` (`n` bytes)."
  [p n]
  (call-with-err "bad JPEG" #(load-jpeg-mem* p n %) non-null?))

(defn rgb8->image [p w h] (from-rgb8* p (int w) (int h)))

(defn save-image!
  "Write the full-resolution composite to `path` as `format` (see `formats`),
   with `meta` (read-meta's map) embedded as EXIF. The file is written to
   `path`.part and renamed when complete: a large export takes seconds, and
   anything opening `path` meanwhile would read a truncated image."
  [base layers {:keys [path quality meta] fmt :format}]
  (let [part (str path ".part")]
    (with-open [a (ffi/confined-arena)]
      (let [larr (write-layers a layers)
            cpath (ffi/string->ptr a part)
            mp (if meta (write-meta-struct a meta) ffi/null)]
        (try
          (call-with-err (str "cannot write " path)
                         #(save* base larr (int (count layers)) cpath (int (formats fmt)) (int quality) mp %)
                         zero?)
          (catch Exception e
            (let [f (java.io.File. part)] (when (.isFile f) (.delete f)))
            (throw e)))))
    (when-not (.renameTo (java.io.File. part) (java.io.File. path))
      (throw (ex-info (str "cannot write " path) {:path path})))
    path))
