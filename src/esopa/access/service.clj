(ns esopa.access.service
  "The access use cases. Each takes `deps` and the input, performs the effects and
  returns an outcome map; the rules come from esopa.domain.access.roles. An actor is
  the account acting, as a map of :account/id and :access/roles.

  `deps` holds:

  :conn      Datomic connection
  :accounts  function returning every account, ordered by email address, from the
             identity context (see esopa.identity.service/accounts)"
  (:require [datomic.client.api :as d]
            [esopa.access.db :as access-db]
            [esopa.domain.access.roles :as roles]))

(defn roles-of
  "The roles of the account with `account-id`, as a set."
  [deps account-id]
  (access-db/roles-of (d/db (:conn deps)) account-id))

(defn account-registered!
  "Gives the newly registered account with `account-id` the roles new accounts get,
  as its own action."
  [deps account-id]
  (access-db/save-roles! (:conn deps) account-id account-id #{} roles/new-account-roles))

(defn grant-role!
  "Adds `role` to the roles of the account with `account-id`, as nobody's action in
  particular: this is for the grant-role command. See roles/grant for the outcomes."
  [deps {:keys! [account-id role]}]
  (let [old-roles (roles-of deps account-id)
        outcome   (roles/grant old-roles role)]
    (when (= :granted (:outcome outcome))
      (access-db/save-roles! (:conn deps) nil account-id old-roles (:access/roles outcome)))
    outcome))

(defn accounts
  "Every account with its :access/roles, for an `actor` who may assign roles. The
  outcome is :listed, with the :accounts, or :forbidden."
  [deps actor]
  (if (roles/permitted? (:access/roles actor) :access/assign-roles)
    (let [roles-by-account (access-db/roles-by-account (d/db (:conn deps)))]
      {:outcome  :listed
       :accounts (mapv #(assoc % :access/roles (get roles-by-account (:account/id %) #{}))
                       ((:accounts deps)))})
    {:outcome :forbidden}))

(defn assign-roles!
  "Gives the account with `account-id` the `roles`, as the `actor`'s action. See
  roles/assign for the outcomes; the outcome is :not-found when there's no such account."
  [deps actor {:keys! [account-id roles]}]
  (let [db        (d/db (:conn deps))
        old-roles (access-db/roles-of db account-id)
        outcome   (roles/assign actor {:account/id account-id :access/roles old-roles} roles)]
    (cond
      (not= :roles-assigned (:outcome outcome)) outcome
      (not (access-db/account? db account-id)) {:outcome :not-found}
      :else (do (access-db/save-roles! (:conn deps) (:account/id actor) account-id old-roles (:access/roles outcome))
                outcome))))
