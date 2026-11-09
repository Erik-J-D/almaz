(ns almaz.raw
  "RAW decoding via the LibRaw shim (native/raw/lib/libalmazinfer_raw). The
   shim uses the 202611 LibRaw snapshot, which includes the Nikon HE decoder
   (Z9/Z8/Zf/Z6 III).

   Decodes land straight in almaz.pixels images (16-bit, never as Clojure
   data). Restoration models need the full-resolution demosaiced image: the
   half-size decode bins each Bayer quad, which leaves every channel's noise
   independent per pixel, and the denoise net (trained on demosaiced sensor
   noise) falls apart on it."
  (:require [clojure.string :as str]
            [almaz.pixels :as px]
            [jolt.ffi :as ffi]))

(ffi/defcfn decode16* "almz_raw_decode16"
  [:pointer :int32 :pointer :pointer :pointer :pointer] :int32 :blocking)
(ffi/defcfn thumb* "almz_raw_thumb"
  [:pointer :pointer :pointer :pointer :pointer :pointer :pointer] :int32 :blocking)
(ffi/defcfn raw-free* "almz_raw_free" [:pointer] :void)
(ffi/defcfn raw-meta* "almz_raw_meta" [:pointer :pointer] :int32 :blocking)
(ffi/defcfn free-string* "almz_raw_free_string" [:pointer] :void)

(def extensions
  "Camera RAW file extensions (lower case)."
  #{"nef" "nrw" "arw" "srf" "sr2" "cr2" "cr3" "crw" "raf" "orf" "rw2" "dng"
    "pef" "srw" "3fr" "iiq" "erf" "kdc" "mos" "mrw" "x3f"})

(defn raw-file? [path]
  (contains? extensions (str/lower-case (or (second (re-find #"\.([^./]+)$" path)) ""))))

(defn- err-msg [errslot]
  (let [ep (ffi/read errslot :pointer 0)]
    (when-not (ffi/null? ep)
      (let [m (ffi/ptr->string ep)] (free-string* ep) m))))

(defn decode
  "Decode a RAW file to an image handle (16-bit sRGB, AHD demosaic, camera
   white balance). `half-size?` bins 2x2 instead (fast; previews only)."
  [path & {:keys [half-size?]}]
  (with-open [a (ffi/confined-arena)]
    (let [cpath (ffi/string->ptr a path)
          wp (ffi/alloc a :int32) hp (ffi/alloc a :int32)
          dp (ffi/alloc a :pointer) errslot (ffi/alloc a :pointer)]
      (ffi/write errslot :pointer ffi/null 0)
      (if (zero? (decode16* cpath (if half-size? 1 0) wp hp dp errslot))
        (px/wrap-image (ffi/read wp :int32 0) (ffi/read hp :int32 0) (ffi/read dp :pointer 0))
        (throw (ex-info (or (err-msg errslot) "raw decode failed") {:path path}))))))

(defn thumbnail
  "The camera's embedded preview as an image handle, or nil when there is none."
  [path]
  (with-open [a (ffi/confined-arena)]
    (let [cpath (ffi/string->ptr a path)
          fp (ffi/alloc a :int32) wp (ffi/alloc a :int32) hp (ffi/alloc a :int32)
          dp (ffi/alloc a :pointer) lp (ffi/alloc a :size_t) errslot (ffi/alloc a :pointer)]
      (ffi/write errslot :pointer ffi/null 0)
      (if (zero? (thumb* cpath fp wp hp dp lp errslot))
        (let [data (ffi/read dp :pointer 0)]
          (try
            (case (ffi/read fp :int32 0)
              1 (px/jpeg-bytes->image data (ffi/read lp :size_t 0))
              2 (px/rgb8->image data (ffi/read wp :int32 0) (ffi/read hp :int32 0)))
            (catch Exception _ nil)
            (finally (raw-free* data))))
        (do (err-msg errslot) nil)))))

(defn metadata
  "LibRaw's decoded shooting metadata for a RAW file (the fallback for formats
   whose EXIF almaz.pixels/read-meta can't reach), or nil."
  [path]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a px/meta-layout)]
      (when (pos? (raw-meta* (ffi/string->ptr a path) p))
        (px/read-meta-struct p)))))

(defn source-meta
  "An input file's shooting metadata: its own EXIF when readable (JPEG, PNG,
   TIFF-structured RAW), else LibRaw's decoded fields for a RAW file. A RAW
   decode is already rotated upright, so its orientation becomes 1."
  [path]
  (let [r (raw-file? path)
        m (or (px/read-meta path) (when r (metadata path)))]
    (cond-> m (and m r) (assoc :orientation 1))))

(defn load-any
  "Decode a RAW, JPEG, PNG or TIFF file to an image handle."
  [path]
  (if (raw-file? path) (decode path) (px/load-image path)))
