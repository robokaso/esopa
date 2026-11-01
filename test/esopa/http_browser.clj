(ns esopa.http-browser
  "Drives esopa's pages through its connector, like a browser without JavaScript: keeps
  cookies, follows redirects and submits forms with their anti-forgery token."
  (:require [clojure.string :as str]
            [esopa.identity.service-test :as service-test]
            [esopa.server-test :as server-test]
            [io.pedestal.connector.test :as test])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

(defn browser-on
  "A browser on an esopa with the identity `deps`."
  [deps]
  (atom {:deps deps :connector (server-test/test-connector deps) :cookies {}}))

(defn browser
  "A browser on a fresh esopa, or on the same esopa as `other`."
  ([]
   (browser-on (service-test/test-deps)))
  ([other]
   (atom (assoc (select-keys @other [:deps :connector]) :cookies {}))))

(defn store-cookies
  [cookies set-cookie]
  (reduce (fn [cookies header]
            (let [[_ cookie-name value] (re-find #"^([^=]+)=([^;]*)" header)]
              (assoc cookies cookie-name value)))
          cookies
          (if (string? set-cookie) [set-cookie] set-cookie)))

(defn encode
  [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn form-encode
  "`form`, a map of field names to values, form-encoded. A field with a vector of
  values is repeated, as checkboxes are."
  [form]
  (str/join "&" (for [[k vs] form
                      v      (if (vector? vs) vs [vs])]
                  (str (encode (name k)) "=" (encode v)))))

(defn request!
  "Sends a request with the browser's cookies, following redirects. The response
  lists the locations it was redirected to as :redirects."
  [browser method url & {:keys [body redirects] :or {redirects []}}]
  (let [{:keys [connector cookies]} @browser
        headers  (cond-> {}
                   (seq cookies) (assoc :cookie (str/join "; " (for [[k v] cookies] (str k "=" v))))
                   body          (assoc :content-type "application/x-www-form-urlencoded"))
        response (test/response-for connector method url :headers headers :body body)
        location (get-in response [:headers "Location"])]
    (swap! browser #(-> %
                        (update :cookies store-cookies (get-in response [:headers "Set-Cookie"]))
                        (assoc :page (:body response))))
    (if (= 303 (:status response))
      (request! browser :get location :redirects (conj redirects location))
      (assoc response :redirects redirects))))

(defn visit!
  [browser url]
  (request! browser :get url))

(defn hidden-field
  "The value of the hidden form field `field-name` in `html`."
  [html field-name]
  (second (re-find (re-pattern (str "name=\"" field-name "\"[^>]*value=\"([^\"]+)\"")) html)))

(defn csrf-token
  [html]
  (hidden-field html "__anti-forgery-token"))

(defn submit!
  "Submits `form` to `url` with the anti-forgery token of the current page."
  [browser url form]
  (request! browser :post url :body (form-encode (assoc form :__anti-forgery-token (csrf-token (:page @browser))))))

(defn emails
  [browser]
  @(::service-test/outbox (:deps @browser)))

(defn last-link
  "The path of the link in the last email sent."
  [browser]
  (second (re-find #"http://esopa\.test(\S+)" (:body (last (emails browser))))))

(defn registered!
  "Registers and confirms `email`, ada@example.com unless given, with the password
  \"correct horse\"."
  ([browser]
   (registered! browser "ada@example.com"))
  ([browser email]
   (visit! browser "/register")
   (submit! browser "/register" {:email email :password "correct horse"})
   (visit! browser (last-link browser))
   (submit! browser "/confirm" {:token (hidden-field (:page @browser) "token")})))

(defn logged-in!
  "Logs in as `email`, ada@example.com unless given, with `password`."
  ([browser password]
   (logged-in! browser "ada@example.com" password))
  ([browser email password]
   (visit! browser "/login")
   (submit! browser "/login" {:email email :password password})))
