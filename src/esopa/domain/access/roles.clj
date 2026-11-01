(ns esopa.domain.access.roles
  "Who may do what. A permission names what a feature needs, and changes only when
  features do. A role groups permissions into what people do, like admin and member,
  and changes as the business does. An account's permissions are those of all its
  roles together; no role takes a permission away. Roles apply everywhere in esopa.
  Whether an account may act on one particular thing is up to that thing's own rules."
  (:require [malli.core :as m]))

(def permissions
  "Every permission, with what it allows. Each context declares those its features need."
  {:access/assign-roles "Change which roles accounts have"})

(def roles
  "Every role: its name and the permissions it grants. Change these freely; features
  only ever ask for permissions."
  {:role/admin  {:role/name        "Admin"
                 :role/permissions #{:access/assign-roles}}
   :role/member {:role/name        "Member"
                 :role/permissions #{}}})

(def Permission
  (into [:enum] (keys permissions)))

(def Role
  (into [:enum] (keys roles)))

(def Roles
  [:set Role])

(def new-account-roles
  "The roles an account gets when it registers."
  #{:role/member})

(defn permissions-of
  "The permissions the `account-roles` grant together."
  [account-roles]
  (into #{} (mapcat #(get-in roles [% :role/permissions])) account-roles))

(defn permitted?
  "Whether the `account-roles` grant `permission`."
  [account-roles permission]
  (contains? (permissions-of account-roles) permission))

(defn assign
  "Gives the account `target` the roles `new-roles`, on behalf of the account `actor`.
  Both accounts are maps of :account/id and :access/roles. The outcome is
  :roles-assigned, with the new :access/roles; :forbidden when the actor may not
  assign roles; or :invalid with a :problem, either :roles/unknown for a role that
  doesn't exist or :roles/own-permission when actors would take away their own
  permission to assign roles, which could leave nobody able to."
  [{:req! {actor-id :account/id actor-roles :access/roles}}
   {:req! {target-id :account/id}}
   new-roles]
  (cond
    (not (permitted? actor-roles :access/assign-roles))
    {:outcome :forbidden}

    (not (m/validate Roles new-roles))
    {:outcome :invalid :problem :roles/unknown}

    (and (= actor-id target-id) (not (permitted? new-roles :access/assign-roles)))
    {:outcome :invalid :problem :roles/own-permission}

    :else
    {:outcome :roles-assigned :access/roles new-roles}))

(defn grant
  "Adds `role` to the `account-roles`, for someone with access to esopa's server rather
  than its pages. The outcome is :granted, with the new :access/roles, or :invalid
  with the :problem :roles/unknown."
  [account-roles role]
  (if (m/validate Role role)
    {:outcome :granted :access/roles (conj (set account-roles) role)}
    {:outcome :invalid :problem :roles/unknown}))
