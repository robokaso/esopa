(ns esopa.system
  "Starts and stops the running application: a Datomic connection and an HTTP connector."
  (:require [clojure.java.io :as io]
            [esopa.db :as db]
            [esopa.server :as server]
            [io.pedestal.connector :as connector]))

(defn config
  "Configuration read from the environment, with local development defaults."
  []
  {:host        (or (System/getenv "HOST") "localhost")
   :port        (parse-long (or (System/getenv "PORT") "8080"))
   :storage-dir (.getAbsolutePath (io/file (or (System/getenv "DATOMIC_STORAGE_DIR") "data/datomic")))
   :db-name     (or (System/getenv "DATOMIC_DB_NAME") "esopa")})

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
