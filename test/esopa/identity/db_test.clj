(ns esopa.identity.db-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [esopa.db :as db]
            [esopa.identity.db :as identity-db]))

(defn- fresh-conn
  []
  (db/connect {:storage-dir :mem :db-name (str (random-uuid))}))

(defn- account
  [email]
  {:account/id              (random-uuid)
   :account/email           email
   :account/password-hash   "hash-1"
   :account/registered-at   #inst "2026-01-01T10:00:00.000-00:00"
   :account/session-version 0})

(deftest create-and-find-test
  (let [conn (fresh-conn)
        ada  (account "ada@example.com")]
    (is (= :created (identity-db/create-account! conn ada)))
    (is (= ada (identity-db/account-by-email (d/db conn) "ada@example.com")))
    (is (= ada (identity-db/account-by-id (d/db conn) (:account/id ada))))
    (testing "unknown accounts are nil"
      (is (nil? (identity-db/account-by-email (d/db conn) "eve@example.com")))
      (is (nil? (identity-db/account-by-id (d/db conn) (random-uuid)))))))

(deftest email-taken-test
  (let [conn (fresh-conn)
        ada  (account "ada@example.com")]
    (identity-db/create-account! conn ada)
    (is (= :email-taken (identity-db/create-account! conn (account "ada@example.com"))))
    (testing "the existing account is unchanged"
      (is (= ada (identity-db/account-by-email (d/db conn) "ada@example.com"))))))

(deftest other-failures-propagate-test
  (is (thrown? clojure.lang.ExceptionInfo
               (identity-db/create-account! (fresh-conn) (assoc (account "ada@example.com")
                                                                :account/session-version "zero")))))

(deftest save-test
  (let [conn (fresh-conn)
        ada  (account "ada@example.com")
        at   #inst "2026-01-02T10:00:00.000-00:00"]
    (identity-db/create-account! conn ada)
    (identity-db/save! conn {:account/id (:account/id ada) :account/confirmed-at at})
    (is (= (assoc ada :account/confirmed-at at)
           (identity-db/account-by-id (d/db conn) (:account/id ada))))))
