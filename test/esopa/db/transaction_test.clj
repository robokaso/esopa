(ns esopa.db.transaction-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [esopa.db :as db]
            [esopa.db.transaction :as transaction]))

(defn actor-of
  "The :transaction/actor of the transaction that stored the current value of `attribute`
  of `entity`, an entity id or lookup ref, or nil. For an attribute with many values,
  name the `value`."
  ([db entity attribute]
   (ffirst (d/q '[:find ?actor
                  :in $ ?e ?attribute
                  :where [?e ?attribute _ ?tx] [?tx :transaction/actor ?actor]]
                db entity attribute)))
  ([db entity attribute value]
   (ffirst (d/q '[:find ?actor
                  :in $ ?e ?attribute ?value
                  :where [?e ?attribute ?value ?tx] [?tx :transaction/actor ?actor]]
                db entity attribute value))))

(defn- account
  [id email]
  {:account/id id :account/email email})

(deftest transact-test
  (let [conn (db/connect {:storage-dir :mem :db-name (str (random-uuid))})
        ada  (random-uuid)]
    (testing "records the account whose action the transaction is"
      (transaction/transact! conn ada [(account ada "ada@example.com")])
      (is (= ada (actor-of (d/db conn) [:account/id ada] :account/email))))
    (testing "records no account for a change made outside esopa's pages"
      (let [eve (random-uuid)]
        (transaction/transact! conn nil [(account eve "eve@example.com")])
        (is (some? (d/pull (d/db conn) [:account/id] [:account/id eve])))
        (is (nil? (actor-of (d/db conn) [:account/id eve] :account/email)))))))
