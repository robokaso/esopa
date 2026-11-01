(ns user
  (:require [clojure.string :as str]
            [esopa.commands :as commands]
            [esopa.system :as system])
  (:import (com.icegreen.greenmail.util GreenMail ServerSetupTest)
           (jakarta.mail.internet MimeMessage)))

(defonce system nil)

(defonce ^{:doc "GreenMail, an SMTP server in this JVM that catches the emails the system sends."}
  mail nil)

(defn- dev-env
  "The environment, with random keys when none are set (so restarting logs you
  out), a session cookie that also works over plain HTTP, and email going to
  GreenMail unless SMTP_HOST is set."
  [^GreenMail greenmail]
  (merge {"SESSION_KEY"   (system/random-key 16)
          "TOKEN_KEY"     (system/random-key 32)
          "COOKIE_SECURE" "false"}
         (when-not (System/getenv "SMTP_HOST")
           {"SMTP_HOST"     "127.0.0.1"
            "SMTP_PORT"     (str (.getPort (.getSmtp greenmail)))
            "SMTP_FROM"     "esopa <noreply@localhost>"
            "SMTP_STARTTLS" "false"})
         (System/getenv)))

(defn start
  []
  (alter-var-root #'mail #(or % (doto (GreenMail. (.dynamicPort ServerSetupTest/SMTP)) (.start))))
  (alter-var-root #'system #(or % (system/start (system/config (dev-env mail)))))
  :started)

(defn stop
  []
  (alter-var-root #'system system/stop)
  (alter-var-root #'mail #(some-> ^GreenMail % (.stop)))
  :stopped)

(defn restart
  []
  (stop)
  (start))

(defn emails
  "The emails sent since (start), newest first, with the links in them."
  []
  (for [^MimeMessage message (reverse (some-> ^GreenMail mail (.getReceivedMessages)))]
    (let [body (str/replace (str (.getContent message)) "\r\n" "\n")]
      {:to      (str/join ", " (.getAllRecipients message))
       :subject (.getSubject message)
       :links   (vec (re-seq #"https?://\S+" body))
       :body    body})))

(defn last-link
  "The first link in the newest email, ready to open in a browser."
  []
  (first (:links (first (emails)))))

(defn grant-role!
  "Gives the account with `email` the role named `role-name`, like \"admin\", in the
  running system, as the grant-role command does."
  [email role-name]
  (:message (commands/grant-role (:conn system) [email role-name])))

(comment
  (start)
  (emails)
  (last-link)
  (grant-role! "ada@example.com" "admin")
  (stop)
  (restart))
