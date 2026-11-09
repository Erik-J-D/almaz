(ns almaz.ui.chrome
  "Everything around the image: the top bar (file, view modes, export), the
   status bar (progress, size, zoom) and the filmstrip."
  (:require [almaz.actions :as act]
            [almaz.doc :as d]
            [almaz.geom :as g]
            [almaz.state :as s]
            [almaz.loading :as load]
            [almaz.ui.units :refer [px]]
            [almaz.view :as v]))

(def version "0.2.0")

(defonce ^:private export-format (atom 0))

(defn top-bar []
  (let [doc (s/current-doc)
        {:keys [mode]} @s/view*]
    [:hbox {:padding 6 :spacing 8}
     [:label {:text "Almaz" :bold 1 :weight 0 :font-scale 1.4}]
     [:separator {:weight 0}]
     (when doc [:label {:text (if (:raw doc) "RAW" "RGB") :weight 0 :bold 1}])
     [:label {:text (or (:name doc) "") :weight 0}]
     [:label {:text (or (when doc (d/shot-info (d/exif @s/db* (:id doc)))) "") :weight 1}]
     [:enum {:items ["Single" "Split" "Side by side"] :weight 0
             :value (case mode :split 1 :side 2 0)
             :on-change #(act/set-mode! ([:single :split :side] (long %)))}]
     [:label {:text "" :weight 1}]
     [:label {:text (str "v" version) :weight 0}]
     [:dropdown {:items (mapv first act/export-formats) :weight 0 :value @export-format
                 :on-change #(reset! export-format (long %))}]
     [:button {:text "Export image" :weight 0 :enabled (if (:base doc) 1 0)
               :on-click #(act/export! @export-format 95)}]]))

(def ^:private zoom-steps [["Fit" nil] ["25%" 0.25] ["50%" 0.5] ["100%" 1.0] ["200%" 2.0] ["400%" 4.0]])

(defn- zoom-index [{:keys [scale zoom]}]
  (if (= zoom :fit)
    0
    (or (first (keep-indexed (fn [i [_ z]] (when (and z (< (Math/abs (- z scale)) 1e-6)) i)) zoom-steps))
        0)))

(defn- zoom-menu [view]
  [:dropdown {:items (mapv first zoom-steps) :weight 0 :value (zoom-index view)
              :on-change #(if-let [z (second (nth zoom-steps (long %)))]
                            (act/zoom-to! z)
                            (act/zoom-fit!))}])

(defn- job-progress [{:keys [label done total]}]
  [:hbox {:weight 0 :spacing (px 6)}
   [:label {:text label :weight 0}]
   [:progress {:weight 0 :reqwidth (px 180)
               :factor (if total (/ (double done) total) -1)}]])

(defn status-bar []
  (let [doc (s/current-doc)
        job @s/job*
        view @s/view*]
    [:hbox {:padding 4 :spacing 8}
     [:button {:text "Add images" :weight 0 :on-click act/open-dialog!}]
     [:button {:text "Remove" :weight 0 :enabled (if doc 1 0)
               :on-click #(some-> @s/current* act/remove-doc!)}]
     [:label {:text @s/message* :weight 1}]
     (when job (job-progress job))
     (when-let [[w h] (when (:w doc) (g/display-size doc false))]
       [:label {:text (str "W " w "  H " h) :weight 0}])
     [:label {:text (v/zoom-label view) :weight 0}]
     (zoom-menu view)]))

(defn- thumb [id selected?]
  (let [{ptr :thumb rev :thumb-rev :keys [name status]} (s/doc id)
        [w h] load/thumb-size
        [tw th] [(px 88) (px 59)]]
    [:vbox {:key id :weight 0 :spacing 2}
     [:canvas {:reqwidth tw :reqheight th :events 1
               :on-press (fn [_ _] (act/select-doc! id))
               :canvas-items
               (cond-> []
                 ptr (conj {:id 1 :type :image :coords [0 0 tw th] :rgba ptr :iw w :ih h :fit 1 :version rev})
                 (not ptr) (conj {:id 3 :type :text :coords [(/ tw 2.0) (/ th 2.0)] :anchor 0
                                  :text (if (= :error status) "error" "…") :fill [1 1 1 0.5]})
                 selected? (conj {:id 2 :type :rect :coords [1 1 (dec tw) (dec th)]
                                  :outline [0.3 0.6 1 1] :width 3}))}]
     [:label {:text (if (> (count name) 22) (str (subs name 0 20) "…") name)}]
     ]))

(defn filmstrip []
  (let [ids (mapv :id (d/docs @s/db*))
        cur @s/current*]
    [:scroll {:height-units 8}
     [:hbox {:padding 6 :spacing 8}
      (if (seq ids)
        (for [id ids] (thumb id (= id cur)))
        [:label {:text "No images yet: use Add images."}])
      [:label {:text "" :weight 1}]]]))
