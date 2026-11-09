(ns almaz.batch
  "Batch processing from the command line: for each input, decode, estimate,
   plan the filters (almaz.cli/plan), run them over the whole image with the
   same engine the app uses, and export with the input's metadata. No window,
   no worker thread.

   A file that fails is reported and skipped; the run carries on and the exit
   status says whether everything went through."
  (:require [almaz.cli :as cli]
            [almaz.engine :as eng]
            [almaz.models :as models]
            [almaz.pixels :as px]
            [almaz.raw :as raw]))

(defn- err [& xs] (binding [*out* *err*] (apply println xs) (flush)))

(defn- model-session
  "The filter's model session (downloaded first if missing), or nil for a
   classic filter."
  [f]
  (when-let [m (eng/model-id f)]
    (models/ensure-model! m (let [last-mb (atom -1)]
                              (fn [got total]
                                (let [mb (quot got 10000000)]
                                  (when (not= mb @last-mb)
                                    (reset! last-mb mb)
                                    (err (str "  downloading " (name m) " model: "
                                              (quot got 1000000) "/" (quot (or total 0) 1000000) " MB")))))))
    (models/session m)))

(defn- run-filter!
  "Run filter `f` over base + `below`; returns its output image."
  [base below f]
  (let [w (px/width base) h (px/height base)
        out (px/new-image w h)]
    (px/composite-into! out base below)
    (eng/process! {:base base :below below :out out :focus [(/ w 2) (/ h 2)]
                   :op (eng/op-for f (model-session f))})
    out))

(defn- run-filters!
  "Run `filters` in order over `base`; returns the layer stack (each output
   is also recorded in the `outputs` atom, so it is freed whatever happens)."
  [base filters outputs]
  (reduce (fn [below f]
            (let [img (run-filter! base below f)]
              (swap! outputs conj img)
              (conj below {:img img :opacity (:opacity f) :enabled true})))
          [] filters))

(defn- export! [opts path base layers]
  (let [dest (cli/output-path opts path)]
    (when-let [dir (.getParentFile (java.io.File. dest))] (.mkdirs dir))
    (px/save-image! base layers {:path dest :format (:format opts) :quality (:quality opts)
                                 :meta (raw/source-meta path)})
    dest))

(defn- process-file!
  "Process one input; returns the output path."
  [opts path]
  (let [base (raw/load-any path)
        outputs (atom [])]
    (try
      (let [est (px/estimate base)
            filters (cli/plan opts est)]
        (when (:show opts) (println (cli/describe path est filters)))
        (export! opts path base (run-filters! base filters outputs)))
      (finally
        (run! px/free-image! @outputs)
        (px/free-image! base)))))

(defn run-batch!
  "Run parsed options (almaz.cli/parse). Returns {:ok n :failed n}."
  [opts]
  (reduce (fn [acc path]
            (try
              (let [t0 (System/currentTimeMillis)
                    dest (process-file! opts path)]
                (println (format "%s -> %s (%.1fs)" path dest (/ (- (System/currentTimeMillis) t0) 1000.0)))
                (update acc :ok inc))
              (catch Exception e
                (err (str path ": " (ex-message e)))
                (update acc :failed inc))))
          {:ok 0 :failed 0}
          (:files opts)))

(defn main
  "The command-line entry: parse, report mistakes or help, run. Returns the
   process exit status."
  [args]
  (let [opts (cli/parse args)]
    (cond
      (:help opts) (do (println cli/usage) 0)
      (seq (:errors opts)) (do (run! err (map #(str "almaz: " %) (:errors opts)))
                               (err "Run almaz --help for usage.")
                               2)
      :else (let [{:keys [failed]} (run-batch! opts)] (if (pos? failed) 1 0)))))
