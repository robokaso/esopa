(ns esopa.system
  "Starts and stops the running application: a Datomic connection and an HTTP connector."
  (:require [clojure.java.io :as io]
            [esopa.access.service :as access-service]
            [esopa.db :as db]
            [esopa.email :as email]
            [esopa.identity.service :as service]
            [esopa.security.password :as password]
            [esopa.security.pwned :as pwned]
            [esopa.server :as server]
            [io.pedestal.connector :as connector]
            [malli.core :as m]
            [malli.error :as me])
  (:import (java.security SecureRandom)
           (java.util Base64)
           (java.util.concurrent ExecutorService Executors TimeUnit)))

(defn- decode-key
  "Decodes a base64 key, keeping the original value when it isn't valid base64,
  so that validation reports it."
  [s]
  (try
    (.decode (Base64/getDecoder) ^String s)
    (catch IllegalArgumentException _
      s)))

(defn random-key
  "A random key of `n` bytes, base64-encoded, for SESSION_KEY or TOKEN_KEY."
  [n]
  (let [bytes (byte-array n)]
    (.nextBytes (SecureRandom.) bytes)
    (.encodeToString (Base64/getEncoder) bytes)))

(defn config
  "Configuration from `env`, a map of environment variable names to values such
  as (System/getenv), with local development defaults. The keys have no
  defaults; see Config. Without SMTP_HOST, emails are only logged."
  [env]
  {:host           (get env "HOST" "localhost")
   :port           (parse-long (get env "PORT" "8080"))
   :storage-dir    (.getAbsolutePath (io/file (get env "DATOMIC_STORAGE_DIR" "data/datomic")))
   :db-name        (get env "DATOMIC_DB_NAME" "esopa")
   :base-url       (get env "BASE_URL" "http://localhost:8080")
   :cookie-secure? (not= "false" (get env "COOKIE_SECURE"))
   :trusted-proxies (parse-long (get env "TRUSTED_PROXIES" "0"))
   :smtp           (when-let [host (get env "SMTP_HOST")]
                     {:host      host
                      :port      (parse-long (get env "SMTP_PORT" "587"))
                      :from      (get env "SMTP_FROM")
                      :username  (get env "SMTP_USERNAME")
                      :password  (get env "SMTP_PASSWORD")
                      :starttls? (not= "false" (get env "SMTP_STARTTLS"))})
   :session-key    (some-> (get env "SESSION_KEY") decode-key)
   :token-key      (some-> (get env "TOKEN_KEY") decode-key)})

(defn- key-of
  [pred message]
  [:fn {:error/message message} #(and (bytes? %) (pred (alength ^bytes %)))])

(def Config
  [:map
   [:trusted-proxies [:int {:min 0 :error/message "TRUSTED_PROXIES must be the number of proxies in front of esopa"}]]
   [:session-key (key-of #(= 16 %) "SESSION_KEY must be 16 random bytes, base64-encoded")]
   [:token-key (key-of #(<= 32 %) "TOKEN_KEY must be at least 32 random bytes, base64-encoded")]
   [:smtp [:maybe [:map
                   [:port [:int {:min 1 :max 65535 :error/message "SMTP_PORT must be a port number"}]]
                   [:from [:string {:min 1 :error/message "SMTP_FROM must be set when SMTP_HOST is"}]]]]]])

(defn- validate!
  [config]
  (when-let [errors (me/humanize (m/explain Config config))]
    (throw (ex-info (str "Invalid configuration: " (pr-str (vals errors))) {:errors errors}))))

(defn- identity-deps
  [config conn email-executor]
  {:conn             conn
   :password-options password/default-options
   :token-key        (:token-key config)
   :send-email       (email/in-background (email/sender (:smtp config)) email-executor)
   :breached?        (pwned/checker (pwned/http-fetcher pwned/default-fetcher-options))
   :now              #(java.util.Date.)
   :base-url         (:base-url config)
   :counters         (service/new-counters)})

(defn context-deps
  "The deps of each context's use cases, as :identity-deps and :access-deps, connected:
  identity announces each new account to access, which gives it its first roles, and
  access lists accounts from identity. `identity-deps` is what esopa.identity.service
  needs apart from :account-registered."
  [identity-deps]
  (let [access-deps {:conn     (:conn identity-deps)
                     :accounts #(service/accounts identity-deps)}]
    {:identity-deps (assoc identity-deps :account-registered #(access-service/account-registered! access-deps %))
     :access-deps   access-deps}))

(defn start
  "Starts the application. Throws when `config` is invalid."
  [config]
  (validate! config)
  (let [conn           (db/connect config)
        email-executor (Executors/newSingleThreadExecutor)
        connector      (server/create-connector
                         (merge config (context-deps (identity-deps config conn email-executor))))]
    (connector/start! connector)
    {:config config :conn conn :connector connector :email-executor email-executor}))

(defn stop
  "Stops serving requests, then waits up to 15 seconds for queued emails to be sent."
  [{:keys [connector ^ExecutorService email-executor]}]
  (when connector
    (connector/stop! connector))
  (when email-executor
    (.shutdown email-executor)
    (.awaitTermination email-executor 15 TimeUnit/SECONDS))
  nil)
