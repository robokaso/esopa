(ns user
  (:require [esopa.system :as system]))

(defonce system nil)

(defn- dev-env
  "The environment, with random keys when none are set (so restarting the REPL
  logs you out) and a session cookie that also works over plain HTTP."
  []
  (merge {"SESSION_KEY"   (system/random-key 16)
          "TOKEN_KEY"     (system/random-key 32)
          "COOKIE_SECURE" "false"}
         (System/getenv)))

(defn start
  []
  (alter-var-root #'system (fn [s] (or s (system/start (system/config (dev-env))))))
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
  (restart))
