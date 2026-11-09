(ns almaz.worker-test
  "The background worker's thread."
  (:require [clojure.test :refer [deftest is]]
            [almaz.worker :as worker]))

(deftest the-worker-does-not-keep-the-app-alive
  ;; jolt, like the JVM, ends a program only once its non-daemon threads have:
  ;; a non-daemon worker loop kept the process running after its window closed
  (let [t (worker/start!)]
    (is (.isAlive t))
    (is (.isDaemon t))))
