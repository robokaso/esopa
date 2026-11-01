(ns esopa.db.transaction
  "Transactions that record whose action they are. Every write goes through transact!,
  so Datomic's history tells who changed what, and when."
  (:require [datomic.client.api :as d]))

(def schema
  [{:db/ident       :transaction/actor
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/doc         (str "The :account/id of the account whose action the transaction records. Absent for changes "
                         "made outside esopa's pages, such as with the grant-role command.")}])

(defn transact!
  "Transacts `tx-data` as the action of `actor`, an :account/id, or of nobody in
  particular when it's nil."
  [conn actor tx-data]
  (d/transact conn {:tx-data (cond-> (vec tx-data)
                               actor (conj {:db/id "datomic.tx" :transaction/actor actor}))}))
