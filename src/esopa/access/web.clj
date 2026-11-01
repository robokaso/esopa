(ns esopa.access.web
  "Routes and handlers for changing which roles accounts have, and the interceptor that
  puts the logged-in account's roles on every request."
  (:require [esopa.access.service :as service]
            [esopa.access.views :as views]
            [esopa.views :as site]
            [esopa.web :as web]
            [io.pedestal.interceptor :as interceptor]))

(defn roles-interceptor
  "Puts the access use cases' `deps` on the request as :access, and the roles of the
  logged-in account as :roles: a set, empty for a visitor who isn't logged in. Comes
  after esopa.identity.web/identity-interceptor, which puts the :account there."
  [deps]
  (interceptor/interceptor
    {:name  ::roles
     :enter (fn [context]
              (update context :request
                      #(assoc % :access deps :roles (if-let [account (:account %)]
                                                      (service/roles-of deps (:account/id account))
                                                      #{}))))}))

(defn- deps
  [request]
  (:access request))

(defn- actor
  [request]
  {:account/id   (get-in request [:account :account/id])
   :access/roles (:roles request)})

(defn- accounts-page
  "The accounts page with `outcome`'s :problem, if any, or the forbidden page."
  [request status problem-key]
  (let [outcome (service/accounts (deps request) (actor request))]
    (if (= :listed (:outcome outcome))
      (web/html status (views/accounts request {:accounts (:accounts outcome) :problem-key problem-key}))
      (web/html 403 (site/forbidden request)))))

(defn accounts
  [request]
  (accounts-page request 200 nil))

(defn- form-roles
  "The roles checked in the form. A value that isn't a role's name becomes a role that
  doesn't exist, which roles/assign refuses."
  [request]
  (set (map #(keyword "role" %) (web/params request :roles))))

(defn assign-roles
  [request]
  (if-let [account-id (parse-uuid (web/param request :account))]
    (let [outcome (service/assign-roles! (deps request) (actor request) {:account-id account-id
                                                                        :roles      (form-roles request)})]
      (case (:outcome outcome)
        :roles-assigned (web/redirect "/admin/accounts" :flash "Roles saved.")
        :invalid        (accounts-page request 422 (:problem outcome))
        :forbidden      (web/html 403 (site/forbidden request))
        :not-found      (web/html 404 (site/not-found request))))
    (web/html 404 (site/not-found request))))

(def routes
  #{["/admin/accounts" :get [(web/permitted :access/assign-roles) accounts] :route-name ::accounts]
    ["/admin/accounts/roles" :post [(web/permitted :access/assign-roles) assign-roles] :route-name ::assign-roles]})
