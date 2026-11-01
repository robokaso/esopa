(ns esopa.email
  "Sending email: over SMTP with Jakarta Mail when SMTP settings are configured,
  otherwise only to the log."
  (:require [io.pedestal.log :as log])
  (:import (jakarta.mail Message$RecipientType Session Transport)
           (jakarta.mail.internet InternetAddress MimeMessage)
           (java.util Properties)
           (java.util.concurrent Executor)))

(defn log-email!
  "Writes `email`, a map of :to, :subject and :body, to the log instead of sending it.
  The body can hold links that log someone in or reset a password, so this is only
  fit for development."
  [email]
  (log/info :msg "Email not sent: no email provider is configured" :email email))

(def ^:private timeout-ms
  "How long to wait for the SMTP server to connect, answer or accept data."
  "10000")

(defn- session
  [{:keys! [host port starttls?]}]
  (Session/getInstance
    (doto (Properties.)
      (.put "mail.smtp.host" host)
      (.put "mail.smtp.port" (str port))
      (.put "mail.smtp.starttls.enable" (str starttls?))
      (.put "mail.smtp.starttls.required" (str starttls?))
      (.put "mail.smtp.ssl.checkserveridentity" "true")
      (.put "mail.smtp.connectiontimeout" timeout-ms)
      (.put "mail.smtp.timeout" timeout-ms)
      (.put "mail.smtp.writetimeout" timeout-ms))))

(defn- message
  [session from {:keys! [to subject body]}]
  (doto (MimeMessage. ^Session session)
    (.setFrom (InternetAddress. ^String from true))
    (.setRecipient Message$RecipientType/TO (InternetAddress. ^String to true))
    (.setSubject ^String subject "UTF-8")
    (.setText ^String body "UTF-8")))

(defn smtp-sender
  "A function that sends an email map, of :to, :subject and :body, as plain text
  through the SMTP server in `settings`: :host, :port, :from, :starttls? and
  optionally :username and :password. With :starttls?, it refuses to send unless
  the server encrypts the connection with STARTTLS. Throws when sending fails."
  [{:keys! [from] :keys [username password] :as settings}]
  (let [session (session settings)]
    (fn [email]
      (let [message (message session from email)]
        (if username
          (Transport/send message username password)
          (Transport/send message))))))

(defn sender
  "The function that sends email: over SMTP with the `smtp` settings (see
  smtp-sender), or to the log when there are none."
  [smtp]
  (if smtp
    (smtp-sender smtp)
    log-email!))

(defn in-background
  "A function that hands each email to `executor` to be sent with `send`, and
  returns at once. Responses then take as long whether or not an email is sent,
  and a failure to send is logged instead of failing the request."
  [send executor]
  (fn [email]
    (.execute ^Executor executor
              ^Runnable (fn []
                          (try
                            (send email)
                            (catch Exception e
                              (log/error :msg "Sending email failed" :subject (:subject email) :exception e)))))
    nil))
