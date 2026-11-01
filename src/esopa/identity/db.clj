(ns esopa.identity.db
  "Datomic storage for accounts."
  (:require [datomic.client.api :as d])
  (:import (clojure.lang ExceptionInfo)))

(def schema
  [{:db/ident       :account/id
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/identity}
   ;; A unique value, not a unique identity: registering an address that is taken
   ;; must fail rather than update the existing account.
   {:db/ident       :account/email
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique      :db.unique/value
    :db/doc         "Normalized: trimmed and lower-cased."}
   {:db/ident       :account/password-hash
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/doc         "argon2id hash with its salt and settings."}
   {:db/ident       :account/registered-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident       :account/confirmed-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/doc         "When the email address was confirmed; absent until then."}
   {:db/ident       :account/session-version
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one
    :db/doc         "Login sessions issued for an older version have ended."}])

(def ^:private pattern
  [:account/id
   :account/email
   :account/password-hash
   :account/registered-at
   :account/confirmed-at
   :account/session-version])

(defn- account-by
  [db attribute value]
  (ffirst (d/q '[:find (pull ?e pattern)
                 :in $ pattern ?attribute ?value
                 :where [?e ?attribute ?value]]
               db pattern attribute value)))

(defn account-by-email
  "The account with the normalized `email`, or nil."
  [db email]
  (account-by db :account/email email))

(defn account-by-id
  "The account with `id`, or nil."
  [db id]
  (account-by db :account/id id))

(defn create-account!
  "Stores a new account. Returns :created, or :email-taken when another account
  has its email address."
  [conn account]
  (try
    (d/transact conn {:tx-data [account]})
    :created
    (catch ExceptionInfo e
      (if (= :db.error/unique-conflict (:db/error (ex-data e)))
        :email-taken
        (throw e)))))

(defn save!
  "Stores `changes`, a map of the attributes that change, with the :account/id."
  [conn changes]
  (d/transact conn {:tx-data [changes]}))
