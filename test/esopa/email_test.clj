(ns esopa.email-test
  "Sends real email over SMTP to GreenMail, an SMTP server running inside the test
  JVM on a random local port."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [esopa.email :as email])
  (:import (com.icegreen.greenmail.configuration GreenMailConfiguration)
           (com.icegreen.greenmail.util GreenMail ServerSetupTest)
           (jakarta.mail AuthenticationFailedException MessagingException)
           (jakarta.mail.internet MimeMessage)
           (java.util.concurrent Executor Executors TimeUnit)))

(def ^:private ^:dynamic *greenmail* nil)

(defn- with-greenmail
  [f]
  (let [greenmail (doto (GreenMail. (.dynamicPort ServerSetupTest/SMTP))
                    (.withConfiguration (-> (GreenMailConfiguration/aConfig)
                                            (.withUser "esopa" "secret"))))]
    (.start greenmail)
    (try
      (binding [*greenmail* greenmail]
        (f))
      (finally
        (.stop greenmail)))))

(use-fixtures :each with-greenmail)

(defn- settings
  "SMTP settings for GreenMail, which offers neither STARTTLS nor TLS."
  []
  {:host      "127.0.0.1"
   :port      (.getPort (.getSmtp *greenmail*))
   :from      "esopa <noreply@esopa.example>"
   :starttls? false})

(def ^:private confirmation
  {:to      "ada@example.com"
   :subject "Confirm your email address for esopa ✓"
   :body    "Grüße, Ada.\n\nhttp://esopa.test/confirm?token=eyJhbGciOiJIUzI1NiJ9.e30.abc-_\n"})

(defn- received
  []
  (vec (.getReceivedMessages ^GreenMail *greenmail*)))

(deftest smtp-sender-test
  ((email/smtp-sender (settings)) confirmation)
  (let [[^MimeMessage message :as messages] (received)]
    (is (= 1 (count messages)))
    (is (= ["esopa <noreply@esopa.example>"] (map str (.getFrom message))))
    (is (= ["ada@example.com"] (map str (.getAllRecipients message))))
    (testing "subject and body keep non-ASCII text and the link intact; SMTP carries CRLF line endings"
      (is (= (:subject confirmation) (.getSubject message)))
      (is (= (str/trimr (:body confirmation))
             (str/trimr (str/replace (.getContent message) "\r\n" "\n"))))
      (is (= "text/plain; charset=UTF-8" (.getContentType message))))))

(deftest smtp-authentication-test
  (testing "logs in with the configured credentials"
    ((email/smtp-sender (assoc (settings) :username "esopa" :password "secret")) confirmation)
    (is (= 1 (count (received)))))
  (testing "fails, without sending, when the credentials are wrong"
    (is (thrown? AuthenticationFailedException
                 ((email/smtp-sender (assoc (settings) :username "esopa" :password "wrong")) confirmation)))
    (is (= 1 (count (received))))))

(deftest smtp-starttls-required-test
  (testing "refuses to send unencrypted when STARTTLS is required but not offered"
    (is (thrown-with-msg? MessagingException #"STARTTLS is required"
                          ((email/smtp-sender (assoc (settings) :starttls? true)) confirmation)))
    (is (empty? (received)))))

(deftest sender-test
  (testing "without SMTP settings, emails are only logged"
    (is (identical? email/log-email! (email/sender nil))))
  (testing "with SMTP settings, emails are sent"
    ((email/sender (settings)) confirmation)
    (is (= 1 (count (received))))))

(deftest in-background-test
  (testing "hands the email to the executor and returns at once"
    (let [tasks (atom [])
          sent  (atom [])
          send  (email/in-background #(swap! sent conj %)
                                     (reify Executor
                                       (execute [_ task] (swap! tasks conj task))))]
      (is (nil? (send confirmation)))
      (is (empty? @sent))
      (.run ^Runnable (first @tasks))
      (is (= [confirmation] @sent))))

  (testing "a failure is logged, not thrown into the executor"
    (let [send (email/in-background (fn [_] (throw (ex-info "SMTP server unreachable" {})))
                                    (reify Executor
                                      (execute [_ task] (.run ^Runnable task))))]
      (is (nil? (send confirmation)))))

  (testing "sends over SMTP from another thread"
    (let [executor (Executors/newSingleThreadExecutor)]
      ((email/in-background (email/smtp-sender (settings)) executor) confirmation)
      (.shutdown executor)
      (is (.awaitTermination executor 10 TimeUnit/SECONDS))
      (is (= 1 (count (received)))))))
