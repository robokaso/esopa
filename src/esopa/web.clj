(ns esopa.web
  "What the handlers of every context share: HTML responses, redirects, form
  parameters, and the interceptors that say who may use a route. Every route starts
  with public, logged-in or (permitted permission); esopa.server-test checks that.
  They read the request's :account, put there by esopa.identity.web, and its :roles,
  put there by esopa.access.web."
  (:require [esopa.domain.access.roles :as roles]
            [esopa.views :as views]
            [io.pedestal.interceptor :as interceptor]))

(defn html
  "A response with the HTML page `body`."
  [status body]
  {:status  status
   :headers {"Content-Type" "text/html;charset=UTF-8"}
   :body    body})

(defn redirect
  "A response that sends the browser to `location` with a GET, merged with `response`,
  such as its :flash or :session."
  [location & {:as response}]
  (merge {:status 303 :headers {"Location" location}} response))

(defn param
  "The form or query parameter `k` as a string. Missing or repeated parameters
  become the empty string, so they fail validation like any other wrong value."
  [request k]
  (let [value (or (get-in request [:form-params k]) (get-in request [:query-params k]))]
    (if (string? value) value "")))

(defn params
  "The values of the form parameter `k`, which may be repeated, as checkboxes are."
  [request k]
  (let [value (get-in request [:form-params k])]
    (if (string? value) [value] (vec value))))

(def public
  "Starts a route anyone may use."
  (interceptor/interceptor
    {:name  ::public
     :enter identity}))

(def ^:private log-in-first
  (redirect "/login" :flash "Log in first."))

(def logged-in
  "Starts a route for logged-in visitors; others are sent to the login page."
  (interceptor/interceptor
    {:name  ::logged-in
     :enter (fn [{:keys [request] :as context}]
              (cond-> context
                (not (:account request)) (assoc :response log-in-first)))}))

(defn permitted
  "Starts a route for accounts whose roles grant `permission` (see
  esopa.domain.access.roles). Others get the page that says they don't have access,
  and visitors who aren't logged in are sent to the login page."
  [permission]
  (interceptor/interceptor
    {:name  ::permitted
     :enter (fn [{:keys [request] :as context}]
              (cond
                (not (:account request)) (assoc context :response log-in-first)
                (roles/permitted? (:roles request) permission) context
                :else (assoc context :response (html 403 (views/forbidden request)))))}))
