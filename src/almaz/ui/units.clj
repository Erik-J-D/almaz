(ns almaz.ui.units
  "Sizes in BUI widget units, so fixed sizes follow the UI scale (HiDPI):
   the kit lays out in raster pixels, and one unit is 20 px at scale 1."
  (:require [glimmer-bui.ffi :as bk]))

(defn px
  "`n` pixels at scale 1, in raster pixels now."
  [n]
  (long (Math/round (* n (/ (bk/unit) 20.0)))))
