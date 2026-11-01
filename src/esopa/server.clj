(ns esopa.server
  "HTTP routes and the Pedestal connector."
  (:require [clojure.string :as str]
            [esopa.domain.identity.account :as account]
            [esopa.identity.web :as identity-web]
            [io.pedestal.connector :as connector]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.http.jetty :as jetty]
            [io.pedestal.http.ring-middlewares :as ring-middlewares]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.service.interceptors :as interceptors]
            [io.pedestal.service.resources :as resources]
            [ring.middleware.session.cookie :as cookie]))

(defn health
  [_request]
  {:status 200
   :body   {:status "ok"}})

(def routes
  #{["/health" :get [interceptors/json-body health] :route-name ::health]})

(defn client-ip
  "The client's IP address. Behind `trusted-proxies` proxies, each appending the
  address it saw to X-Forwarded-For, it's the address the outermost one saw.
  Entries further left could have been forged by the client."
  [request trusted-proxies]
  (let [forwarded (some-> (get-in request [:headers "x-forwarded-for"]) (str/split #","))
        chain     (conj (mapv str/trim forwarded) (:remote-addr request))]
    (nth chain (max 0 (- (count chain) 1 trusted-proxies)))))

(defn- client-ip-interceptor
  [trusted-proxies]
  (interceptor/interceptor
    {:name  ::client-ip
     :enter (fn [context]
              (assoc-in context [:request :client-ip] (client-ip (:request context) trusted-proxies)))}))

(def ^:private content-security-policy
  "Only scripts, styles and form targets from esopa itself."
  {:default-src     "'self'"
   :object-src      "'none'"
   :base-uri        "'none'"
   :form-action     "'self'"
   :frame-ancestors "'none'"})

(defn- session-options
  "The session lives in a cookie, encrypted and signed with `session-key`, that
  scripts can't read and that is only sent with requests from esopa's own pages."
  [{:keys! [session-key cookie-secure?]}]
  {:store        (cookie/cookie-store {:key session-key})
   :cookie-name  "esopa-session"
   :cookie-attrs {:http-only true
                  :secure    cookie-secure?
                  :same-site :lax
                  :path      "/"
                  :max-age   (.toSeconds account/session-lifetime)}})

(defn create-connector
  "Returns an unstarted Jetty connector. `identity-deps` are the dependencies of
  the identity use cases (see esopa.identity.service); `trusted-proxies` is the
  number of proxies in front of esopa (see client-ip)."
  [{:keys! [host port trusted-proxies identity-deps] :as config}]
  (-> (connector/default-connector-map host port)
      (connector/optionally-with-dev-mode-interceptors)
      (connector/with-default-interceptors
        :session-options (session-options config)
        :secure-headers {:content-security-policy-settings content-security-policy})
      (connector/with-interceptors [(client-ip-interceptor trusted-proxies)
                                    (ring-middlewares/flash)
                                    (csrf/anti-forgery)
                                    (identity-web/identity-interceptor identity-deps)])
      (connector/with-routes routes
                             identity-web/routes
                             (resources/resource-routes {:resource-root "public/assets"
                                                         :prefix        "/assets"}))
      (jetty/create-connector nil)))
