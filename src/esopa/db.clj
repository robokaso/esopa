(ns esopa.db
  "Datomic access via the Client API, backed by Datomic Local."
  (:require [datomic.client.api :as d]
            [esopa.access.db :as access-db]
            [esopa.db.transaction :as transaction]
            [esopa.identity.db :as identity-db]))

(def schema
  "The schema of every bounded context, and of the transactions themselves."
  (concat transaction/schema identity-db/schema access-db/schema))

(defn connect
  "Returns a connection to the database named `db-name`, creating it and installing
  the schema when needed. `storage-dir` is an absolute path, or :mem for an
  in-memory database."
  [{:keys! [storage-dir db-name]}]
  (let [client (d/client {:server-type :datomic-local
                          :system      "esopa"
                          :storage-dir storage-dir})
        _      (d/create-database client {:db-name db-name})
        conn   (d/connect client {:db-name db-name})]
    (d/transact conn {:tx-data schema})
    conn))
