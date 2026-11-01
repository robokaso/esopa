(ns esopa.system-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [esopa.system :as system]))

(def ^:private keys-env
  {"SESSION_KEY" "AAECAwQFBgcICQoLDA0ODw=="
   "TOKEN_KEY"   "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="})

(deftest config-test
  (testing "defaults suit local development"
    (is (= {:host           "localhost"
            :port           8080
            :storage-dir    (.getAbsolutePath (io/file "data/datomic"))
            :db-name        "esopa"
            :base-url       "http://localhost:8080"
            :cookie-secure? true
            :trusted-proxies 0
            :smtp           nil
            :session-key    nil
            :token-key      nil}
           (system/config {}))))

  (testing "environment variables override the defaults"
    (let [config (system/config (merge keys-env
                                       {"HOST"                "0.0.0.0"
                                        "PORT"                "9090"
                                        "DATOMIC_STORAGE_DIR" "/var/lib/esopa"
                                        "DATOMIC_DB_NAME"     "prod"
                                        "BASE_URL"            "https://esopa.example"
                                        "COOKIE_SECURE"       "false"
                                        "TRUSTED_PROXIES"     "1"
                                        "SMTP_HOST"           "smtp.example.com"
                                        "SMTP_PORT"           "2525"
                                        "SMTP_FROM"           "esopa <noreply@esopa.example>"
                                        "SMTP_USERNAME"       "esopa"
                                        "SMTP_PASSWORD"       "secret"
                                        "SMTP_STARTTLS"       "false"}))]
      (is (= {:host           "0.0.0.0"
              :port           9090
              :storage-dir    "/var/lib/esopa"
              :db-name        "prod"
              :base-url       "https://esopa.example"
              :cookie-secure? false
              :trusted-proxies 1
              :smtp           {:host      "smtp.example.com"
                               :port      2525
                               :from      "esopa <noreply@esopa.example>"
                               :username  "esopa"
                               :password  "secret"
                               :starttls? false}}
             (dissoc config :session-key :token-key)))
      (is (= (range 16) (seq (:session-key config))))
      (is (= (range 32) (seq (:token-key config)))))))

(deftest smtp-defaults-test
  (testing "SMTP uses the submission port and requires STARTTLS unless told otherwise"
    (is (= {:host "smtp.example.com" :port 587 :from "noreply@esopa.example"
            :username nil :password nil :starttls? true}
           (:smtp (system/config {"SMTP_HOST" "smtp.example.com" "SMTP_FROM" "noreply@esopa.example"}))))))

(deftest start-requires-keys-test
  (testing "the server refuses to start without valid keys and SMTP settings"
    (doseq [env [{}
                 (assoc keys-env "SESSION_KEY" (system/random-key 32))
                 (assoc keys-env "TOKEN_KEY" (system/random-key 16))
                 (assoc keys-env "SESSION_KEY" "not base64!")
                 (assoc keys-env "SMTP_HOST" "smtp.example.com")
                 (assoc keys-env "SMTP_HOST" "smtp.example.com" "SMTP_FROM" "noreply@esopa.example" "SMTP_PORT" "smtp")]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid configuration"
                            (system/start (system/config env)))))))

(deftest stop-test
  (testing "stopping a system that has no connector does nothing"
    (is (nil? (system/stop {}))))
  (testing "stopping finishes the queued emails and shuts the email executor down"
    (let [executor (java.util.concurrent.Executors/newSingleThreadExecutor)
          sent     (promise)]
      (.execute executor ^Runnable #(deliver sent :sent))
      (system/stop {:email-executor executor})
      (is (.isTerminated executor))
      (is (= :sent (deref sent 0 :not-sent))))))

(deftest random-key-test
  (is (= 16 (count (.decode (java.util.Base64/getDecoder) ^String (system/random-key 16)))))
  (is (not= (system/random-key 16) (system/random-key 16))))
