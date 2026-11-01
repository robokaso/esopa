(ns esopa.system
  "Starts and stops the running application: a Datomic connection and an HTTP connector."
  (:require [clojure.java.io :as io]
            [esopa.db :as db]
            [esopa.server :as server]
            [io.pedestal.connector :as connector]))

(defn config
  "Configuration from `env`, a map of environment variable names to values such
  as (System/getenv), with local development defaults."
  [env]
  {:host        (get env "HOST" "localhost")
   :port        (parse-long (get env "PORT" "8080"))
   :storage-dir (.getAbsolutePath (io/file (get env "DATOMIC_STORAGE_DIR" "data/datomic")))
   :db-name     (get env "DATOMIC_DB_NAME" "esopa")})

(defn start
  [config]
  (let [conn      (db/connect config)
        connector (server/create-connector (assoc config :conn conn))]
    (connector/start! connector)
    {:config config :conn conn :connector connector}))

(defn stop
  [{:keys [connector]}]
  (when connector
    (connector/stop! connector))
  nil)
