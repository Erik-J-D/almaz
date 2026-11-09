(ns almaz.autopilot
  "Autopilot: turn image estimates into a filter stack. Pure.

   The estimates come from almaz.pixels/estimate: `noise` is the luminance
   noise sigma (0..1 units) of the flattest parts of the image, `sharpness`
   the edge gradient energy relative to contrast. The thresholds are
   calibrated on camera files: ~0.002 is a clean base-ISO image, ~0.03 a
   high-ISO one; sharp focus scores above ~0.3.")

(def ^:private noise-floor 0.006)
(def ^:private soft-below 0.3)

(defn- clamp [lo hi v] (min hi (max lo v)))

(defn noisy?
  "Noisy enough to need denoising (estimates may be nil before analysis)."
  [estimate]
  (> (or (:noise estimate) 0.0) noise-floor))

(defn suggest
  "The layers to add, in pipeline order: [{:kind :opacity} ...]."
  [{:keys [noise sharpness]}]
  (cond-> []
    (> noise noise-floor)
    (conj {:kind :denoise
           :opacity (clamp 0.2 1.0 (+ 0.3 (* 25.0 (- noise noise-floor))))})

    (< sharpness soft-below)
    (conj {:kind :sharpen
           :opacity (clamp 0.2 1.0 (+ 0.3 (/ (- soft-below sharpness) soft-below)))})))

(defn describe [{:keys [noise sharpness]}]
  (format "noise %.1f%%  sharpness %.2f" (* 100.0 noise) (double sharpness)))
