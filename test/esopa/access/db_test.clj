(ns esopa.access.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [esopa.access.db :as access-db]
            [esopa.db :as db]
            [esopa.db.transaction-test :as transaction-test]))

(defn- conn-with-accounts
  "A fresh in-memory database with an account for each of `ids`."
  [& ids]
  (doto (db/connect {:storage-dir :mem :db-name (str (random-uuid))})
    (d/transact {:tx-data (for [id ids] {:account/id id})})))

(deftest roles-test
  (let [ada  (random-uuid)
        eve  (random-uuid)
        conn (conn-with-accounts ada eve)]
    (testing "an account starts without roles"
      (is (= #{} (access-db/roles-of (d/db conn) ada))))
    (access-db/save-roles! conn ada ada #{} #{:role/member})
    (access-db/save-roles! conn nil eve #{} #{:role/member :role/admin})
    (is (= #{:role/member} (access-db/roles-of (d/db conn) ada)))
    (is (= {ada #{:role/member} eve #{:role/member :role/admin}} (access-db/roles-by-account (d/db conn))))
    (testing "records whose action the change is"
      (is (= ada (transaction-test/actor-of (d/db conn) [:account/id ada] :access/roles :role/member)))
      (is (nil? (transaction-test/actor-of (d/db conn) [:account/id eve] :access/roles :role/admin))))
    (testing "changing roles adds and removes only what differs"
      (access-db/save-roles! conn ada eve #{:role/member :role/admin} #{:role/admin})
      (is (= #{:role/admin} (access-db/roles-of (d/db conn) eve)))
      (let [before (d/db conn)]
        (access-db/save-roles! conn ada eve #{:role/admin} #{:role/admin})
        (is (= (:t before) (:t (d/db conn))) "no change, no transaction")))
    (testing "an account that doesn't exist has no roles"
      (is (= #{} (access-db/roles-of (d/db conn) (random-uuid)))))))

(deftest account-test
  (let [ada  (random-uuid)
        conn (conn-with-accounts ada)]
    (is (access-db/account? (d/db conn) ada))
    (is (not (access-db/account? (d/db conn) (random-uuid))))))
