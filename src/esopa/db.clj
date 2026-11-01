(ns esopa.db
  "Datomic access via the Client API, backed by Datomic Local."
  (:require [datomic.client.api :as d]))

(def schema
  [{:db/ident       :note/id
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   {:db/ident       :note/text
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :note/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

(defn connect
  "Returns a connection to the database named `db-name`, creating it and installing
  the schema when needed. `storage-dir` is an absolute path, or :mem for an
  in-memory database."
  [{:keys [storage-dir db-name]}]
  (let [client (d/client {:server-type :datomic-local
                          :system      "esopa"
                          :storage-dir storage-dir})
        _      (d/create-database client {:db-name db-name})
        conn   (d/connect client {:db-name db-name})]
    (d/transact conn {:tx-data schema})
    conn))

(defn add-note!
  "Stores a new note and returns its id."
  [conn text]
  (let [id (random-uuid)]
    (d/transact conn {:tx-data [{:note/id         id
                                 :note/text       text
                                 :note/created-at (java.util.Date.)}]})
    id))

(defn notes
  "All notes, oldest first."
  [db]
  (->> (d/q '[:find (pull ?e [:note/id :note/text :note/created-at])
              :where [?e :note/id]]
            db)
       (map first)
       (sort-by :note/created-at)))
