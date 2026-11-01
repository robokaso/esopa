(ns esopa.server
  "HTTP routes and the Pedestal connector."
  (:require [datomic.client.api :as d]
            [esopa.db :as db]
            [io.pedestal.connector :as connector]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.service.interceptors :as interceptors]))

(defn- note->json
  [{:note/keys [id text created-at]}]
  {:id         (str id)
   :text       text
   :created-at (str (.toInstant ^java.util.Date created-at))})

(defn health
  [_request]
  {:status 200
   :body   {:status "ok"}})

(defn list-notes
  [{:keys [db]}]
  {:status 200
   :body   (mapv note->json (db/notes db))})

(defn create-note
  [{:keys [conn json-params]}]
  (let [text (:text json-params)]
    (if (and (string? text) (seq text))
      {:status 201
       :body   {:id (str (db/add-note! conn text))}}
      {:status 400
       :body   {:error "text is required"}})))

(def routes
  #{["/health" :get health :route-name ::health]
    ["/api/notes" :get list-notes :route-name ::list-notes]
    ["/api/notes" :post create-note :route-name ::create-note]})

(defn- datomic-interceptor
  "Puts the connection and a current database value on every request."
  [conn]
  (interceptor/interceptor
    {:name  ::datomic
     :enter (fn [context]
              (update context :request assoc :conn conn :db (d/db conn)))}))

(defn create-connector
  "Returns an unstarted Jetty connector serving `routes`."
  [{:keys [host port conn]}]
  (-> (connector/default-connector-map host port)
      (connector/optionally-with-dev-mode-interceptors)
      (connector/with-default-interceptors)
      (connector/with-interceptors [interceptors/json-body
                                    (datomic-interceptor conn)])
      (connector/with-routes routes)
      (jetty/create-connector nil)))
