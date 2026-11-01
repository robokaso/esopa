(ns esopa.access.views
  "The page for changing which roles accounts have, in the site's layout (see esopa.views)."
  (:require [esopa.domain.access.roles :as roles]
            [esopa.views :as views]))

(def problem-messages
  {:roles/unknown        "Choose only roles from the list."
   :roles/own-permission (str "You can't take away your own permission to change roles. "
                              "Another admin can, if needed.")})

(defn- role-checkbox
  [account-roles [role {:role/keys [name]}]]
  [:label [:input {:type "checkbox" :name "roles" :value (clojure.core/name role) :checked (contains? account-roles role)}]
   name])

(defn- account-form
  [request {:account/keys [id email confirmed-at] :access/keys [roles]}]
  (views/form request "/admin/accounts/roles"
              [:input {:type "hidden" :name "account" :value (str id)}]
              [:fieldset
               [:legend email (when-not confirmed-at " (not confirmed yet)")]
               (map #(role-checkbox roles %) (sort-by key roles/roles))]
              [:button {:aria-label (str "Save roles for " email)} "Save roles"]))

(defn accounts
  "Every account, each with a form to change its roles."
  [request {:keys [accounts problem-key]}]
  (views/layout request "Accounts"
                (views/problem (problem-messages problem-key))
                [:p "Each account's roles decide what it may do. An account can have several roles, or none."]
                (map #(account-form request %) accounts)))
