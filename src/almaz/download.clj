(ns almaz.download
  "Fetching files that must be exactly right (the models): download to a
   `.part` file next to the destination, verify its SHA-256, then rename it
   into place. A failed or corrupt download never lands at the destination,
   so whatever is there has been verified.

   Transfers go through curl (TLS, redirects, retries; present on macOS and
   Linux) and hashing through the native library, so neither depends on
   platform-specific tools."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [jolt.process :as proc]))

(ffi/defcfn sha256-file* "almz_sha256_file" [:pointer :pointer] :int32 :blocking)

(defn sha256
  "The SHA-256 of the file at `path` as lowercase hex, or nil when it can't be
   read."
  [path]
  (with-open [a (ffi/confined-arena)]
    (let [out (ffi/alloc a 65)]
      (when (pos? (sha256-file* (ffi/string->ptr a path) out))
        (ffi/ptr->string out)))))

(defn- file [p] (java.io.File. p))
(defn- size [p] (let [f (file p)] (if (.exists f) (.length f) 0)))

(defn- transfer!
  "Run curl into `part`, calling (progress bytes-so-far total) while it runs.
   Throws with curl's message when it fails."
  [url part total progress]
  (let [p (proc/process {:out :string :err :string}
                        "curl" "-fsSL" "--retry" "3" "--connect-timeout" "20" "-o" part url)
        ;; wait in a future and poll it: jolt's Process shim doesn't report
        ;; liveness, but a realized future does
        done (future @p)]
    (loop []
      (progress (size part) total)
      (when-not (realized? done)
        (Thread/sleep 250)
        (recur)))
    (let [{:keys [exit err]} @done]
      (when-not (zero? exit)
        (throw (ex-info (str "download failed: " (str/trim (str err)))
                        {:reason :transfer-failed :url url :exit exit}))))))

(defn fetch!
  "Download `url` to `dest` and verify it against `sha256` (hex). Returns
   `dest`. On any failure nothing is left at `dest` (or in its `.part`) and an
   ex-info is thrown: {:reason :transfer-failed} or {:reason
   :checksum-mismatch}. `progress` gets (bytes-so-far total-or-nil)."
  [{:keys [url sha256 dest size progress] :or {progress (fn [_ _])}}]
  (let [part (str dest ".part")]
    (.mkdirs (.getParentFile (.getAbsoluteFile (file dest))))
    (.delete (file part))
    (try
      (transfer! url part size progress)
      (let [got (almaz.download/sha256 part)]
        (when (not= got sha256)
          (throw (ex-info (str "checksum mismatch for " url)
                          {:reason :checksum-mismatch :url url :expected sha256 :actual got}))))
      (when-not (.renameTo (file part) (file dest))
        (throw (ex-info (str "cannot move the download into " dest) {:reason :install-failed :dest dest})))
      dest
      (finally (.delete (file part))))))

(defn ensure!
  "`dest` when it already holds the file `sha256` names, else fetch! it.
   A file that is there but doesn't verify is replaced."
  [{:keys [dest] :as spec}]
  (if (= (:sha256 spec) (sha256 dest))
    dest
    (fetch! spec)))
