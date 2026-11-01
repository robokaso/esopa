(ns esopa.access.db
  "Datomic storage for the roles of accounts. Roles are an attribute of the account's
  entity, beside identity's own attributes; access only reads identity's :account/id."
  (:require [clojure.set :as set]
            [datomic.client.api :as d]
            [esopa.db.transaction :as transaction]))

(def schema
  [{:db/ident       :access/roles
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/many
    :db/doc         "The account's roles (see esopa.domain.access.roles)."}])

(defn roles-of
  "The roles of the account with `account-id`, as a set: empty when it has none or
  doesn't exist."
  [db account-id]
  (set (map first (d/q '[:find ?role
                         :in $ ?id
                         :where [?e :account/id ?id] [?e :access/roles ?role]]
                       db account-id))))

(defn roles-by-account
  "The roles of every account that has some, as a map from :account/id to a set."
  [db]
  (update-vals (group-by first (d/q '[:find ?id ?role
                                      :where [?e :access/roles ?role] [?e :account/id ?id]]
                                    db))
               #(set (map second %))))

(defn account?
  "Whether there's an account with `account-id`."
  [db account-id]
  (some? (ffirst (d/q '[:find ?e :in $ ?id :where [?e :account/id ?id]] db account-id))))

(defn save-roles!
  "Changes the roles of the account with `account-id` from `old-roles` to `new-roles`,
  as the action of the account with the id `actor`, or of nobody in particular when
  it's nil. Transacts nothing when nothing changes."
  [conn actor account-id old-roles new-roles]
  (let [account [:account/id account-id]
        tx-data (concat (for [role (set/difference old-roles new-roles)]
                          [:db/retract account :access/roles role])
                        (for [role (set/difference new-roles old-roles)]
                          [:db/add account :access/roles role]))]
    (when (seq tx-data)
      (transaction/transact! conn actor tx-data))))
