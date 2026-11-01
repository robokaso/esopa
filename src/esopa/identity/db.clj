(ns esopa.identity.db
  "Datomic storage for accounts."
  (:require [datomic.client.api :as d]
            [esopa.db.transaction :as transaction])
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

(def ^:private listing-pattern
  (vec (remove #{:account/password-hash} pattern)))

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
  "Stores a new account, as its own action. Returns :created, or :email-taken when
  another account has its email address."
  [conn account]
  (try
    (transaction/transact! conn (:account/id account) [account])
    :created
    (catch ExceptionInfo e
      (if (= :db.error/unique-conflict (:db/error (ex-data e)))
        :email-taken
        (throw e)))))

(defn save!
  "Stores `changes`, a map of the attributes that change, with the :account/id, as
  the action of the account with the id `actor`."
  [conn actor changes]
  (transaction/transact! conn actor [changes]))

(defn accounts
  "Every account, ordered by email address, without its password hash."
  [db]
  (->> (d/q '[:find (pull ?e pattern)
              :in $ pattern
              :where [?e :account/id]]
            db listing-pattern)
       (map first)
       (sort-by :account/email)
       vec))
