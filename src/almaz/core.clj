(ns almaz.core
  "Almaz: AI denoise and sharpen for photos (RAW, JPEG, PNG, TIFF).

   Layout: a top bar (file, view mode, export), the image in the centre with a
   status bar and filmstrip below, and a sidebar on the right with the
   navigator, autopilot and the filter layers. Filters are layers: each runs
   over the composite of the layers below it and blends back through its
   own feathered mask and strength."
  (:require [glimmer.core :as ui]
            [glimmer-bui.core]           ; installs the BUI backend
            [almaz.actions :as act]
            [almaz.batch :as batch]
            [almaz.cli :as cli]
            [almaz.ui.canvas :as canvas]
            [almaz.ui.chrome :as chrome]
            [almaz.ui.sidebar :as sidebar]
            [almaz.worker :as worker]))

(defn app []
  [:vbox {:stretch true}
   [chrome/top-bar]
   [:separator]
   [:hbox {:stretch true}
    [:vbox {:weight 1}
     [canvas/image-view]
     [chrome/status-bar]
     [chrome/filmstrip]]
    [sidebar/sidebar]]])

(defn- dev-script!
  "ALMAZ_SCRIPT=file.clj: load a file a moment after startup, on its own
   thread (it uses glimmer.core/on-gui to touch the UI). For driving the app
   in development and smoke tests."
  []
  (when-let [f (System/getenv "ALMAZ_SCRIPT")]
    (.start (Thread. (fn [] (Thread/sleep 1500) (load-file f))))))

(defn- open-app! [files]
  (worker/start!)
  (dev-script!)
  (act/add-images! (if (seq files) files (filter #(.exists (java.io.File. %)) ["DSC_9852.NEF"])))
  (ui/run app :title "Almaz" :width 1500 :height 960))

(defn -main
  "With options, batch-process the files (almaz --help); otherwise open them
   in the app."
  [& args]
  (if (cli/batch? args)
    (System/exit (batch/main args))
    (open-app! args)))
