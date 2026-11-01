(ns user
  (:require [esopa.system :as system]))

(defonce system nil)

(defn start
  []
  (alter-var-root #'system (fn [s] (or s (system/start (system/config (System/getenv))))))
  :started)

(defn stop
  []
  (alter-var-root #'system system/stop)
  :stopped)

(defn restart
  []
  (stop)
  (start))

(comment
  (start)
  (stop)
  (restart)
  (esopa.db/notes (datomic.client.api/db (:conn system))))
