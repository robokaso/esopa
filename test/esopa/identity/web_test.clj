(ns esopa.identity.web-test
  "Drives the pages like a browser: keeps cookies, follows redirects and submits
  forms with their anti-forgery token."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [esopa.identity.service-test :as service-test]
            [esopa.server-test :as server-test]
            [io.pedestal.connector.test :as test])
  (:import (java.net URLEncoder)
           (java.nio.charset StandardCharsets)))

(use-fixtures :once test/disable-routing-table-output-fixture)

(defn- browser
  "A browser on a fresh esopa, or on the same esopa as `other`."
  ([]
   (let [deps (service-test/test-deps)]
     (atom {:deps deps :connector (server-test/test-connector deps) :cookies {}})))
  ([other]
   (atom (assoc (select-keys @other [:deps :connector]) :cookies {}))))

(defn- store-cookies
  [cookies set-cookie]
  (reduce (fn [cookies header]
            (let [[_ cookie-name value] (re-find #"^([^=]+)=([^;]*)" header)]
              (assoc cookies cookie-name value)))
          cookies
          (if (string? set-cookie) [set-cookie] set-cookie)))

(defn- encode
  [s]
  (URLEncoder/encode (str s) StandardCharsets/UTF_8))

(defn- form-encode
  [form]
  (str/join "&" (for [[k v] form] (str (encode (name k)) "=" (encode v)))))

(defn- request!
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

(defn- visit!
  [browser url]
  (request! browser :get url))

(defn- hidden-field
  "The value of the hidden form field `field-name` in `html`."
  [html field-name]
  (second (re-find (re-pattern (str "name=\"" field-name "\"[^>]*value=\"([^\"]+)\"")) html)))

(defn- csrf-token
  [html]
  (hidden-field html "__anti-forgery-token"))

(defn- submit!
  "Submits `form` to `url` with the anti-forgery token of the current page."
  [browser url form]
  (request! browser :post url :body (form-encode (assoc form :__anti-forgery-token (csrf-token (:page @browser))))))

(defn- emails
  [browser]
  @(::service-test/outbox (:deps @browser)))

(defn- last-link
  "The path of the link in the last email sent."
  [browser]
  (second (re-find #"http://esopa\.test(\S+)" (:body (last (emails browser))))))

(defn- registered!
  "Registers and confirms ada@example.com with the password \"correct horse\"."
  [browser]
  (visit! browser "/register")
  (submit! browser "/register" {:email "ada@example.com" :password "correct horse"})
  (visit! browser (last-link browser))
  (submit! browser "/confirm" {:token (hidden-field (:page @browser) "token")}))

(defn- logged-in!
  [browser password]
  (visit! browser "/login")
  (submit! browser "/login" {:email "ada@example.com" :password password}))

(defn- logged-in-as-ada?
  [browser]
  (str/includes? (:body (visit! browser "/")) "You&apos;re logged in as ada@example.com."))

(deftest register-confirm-and-log-in-test
  (let [b (browser)]
    (is (= 200 (:status (visit! b "/register"))))
    (let [response (submit! b "/register" {:email "ada@example.com" :password "correct horse"})]
      (is (= ["/check-email"] (:redirects response)))
      (is (str/includes? (:body response) "Check your email")))

    (let [confirm-page (visit! b (last-link b))]
      (is (= 200 (:status confirm-page)))
      (testing "opening the link doesn't confirm by itself; the button does"
        (is (str/includes? (:body confirm-page) "Confirm my email address"))))
    (let [response (submit! b "/confirm" {:token (second (re-find #"token=(\S+)" (:body (last (emails b)))))})]
      (is (= ["/login"] (:redirects response)))
      (is (str/includes? (:body response) "Your email address is confirmed.")))

    (let [response (submit! b "/login" {:email "Ada@Example.com" :password "correct horse"})]
      (is (= ["/"] (:redirects response)))
      (is (logged-in-as-ada? b)))))

(deftest form-fields-test
  (testing "fields carry the hints browsers and password managers use"
    (let [b (browser)]
      (is (re-find #"<input autocomplete=\"email\" id=\"email\" name=\"email\" required type=\"email\""
                   (:body (visit! b "/register"))))
      (is (str/includes? (:page @b) "autocomplete=\"new-password\""))
      (is (str/includes? (:body (visit! b "/login")) "autocomplete=\"current-password\""))))
  (testing "the show-password script runs once the page has loaded"
    (is (str/includes? (:body (visit! (browser) "/")) "<script defer src=\"/assets/show-password.js\">"))))

(deftest register-problems-test
  (let [b (browser)]
    (visit! b "/register")
    (let [response (submit! b "/register" {:email "ada@example.com" :password "short"})]
      (is (= 422 (:status response)))
      (is (str/includes? (:body response) "Use at least 8 characters."))
      (testing "keeps what was typed, except the password"
        (is (str/includes? (:body response) "value=\"ada@example.com\""))
        (is (not (str/includes? (:body response) "short")))))
    (is (str/includes? (:body (submit! b "/register" {:email "ada@example.com" :password "password123"}))
                       "appeared in a data breach"))
    (testing "escapes what was typed"
      (let [body (:body (submit! b "/register" {:email "<script>alert(1)</script>" :password "correct horse"}))]
        (is (str/includes? body "Enter a valid email address"))
        (is (not (str/includes? body "<script>alert")))
        (is (str/includes? body "&lt;script&gt;"))))
    (is (empty? (emails b)))))

(deftest anti-forgery-test
  (testing "forms posted without the page's token are refused"
    (let [b (browser)]
      (visit! b "/register")
      (is (= 403 (:status (request! b :post "/register" :body (form-encode {:email    "ada@example.com"
                                                                            :password "correct horse"})))))
      (is (empty? (emails b))))))

(deftest repeated-parameters-test
  (let [b (browser)]
    (visit! b "/login")
    (is (= 422 (:status (request! b :post "/login"
                                  :body (str "email=ada%40example.com&email=eve%40example.com&password=x"
                                             "&__anti-forgery-token=" (encode (csrf-token (:page @b))))))))))

(deftest invalid-confirmation-link-test
  (let [b (browser)]
    (visit! b "/confirm?token=garbage")
    (let [response (submit! b "/confirm" {:token "garbage"})]
      (is (= 400 (:status response)))
      (is (str/includes? (:body response) "This link doesn&apos;t work")))))

(deftest resend-confirmation-test
  (let [b (browser)]
    (visit! b "/register")
    (submit! b "/register" {:email "ada@example.com" :password "correct horse"})
    (is (= 200 (:status (visit! b "/confirm/resend"))))
    (is (= ["/check-email"] (:redirects (submit! b "/confirm/resend" {:email "ada@example.com"}))))
    (is (= 2 (count (emails b))))
    (is (str/includes? (last-link b) "/confirm?token="))))

(deftest log-in-problems-test
  (let [b (browser)]
    (visit! b "/register")
    (submit! b "/register" {:email "ada@example.com" :password "correct horse"})
    (testing "an unconfirmed account is asked to confirm first"
      (let [response (logged-in! b "correct horse")]
        (is (= 403 (:status response)))
        (is (str/includes? (:body response) "Confirm your email address first"))))
    (testing "a wrong password says only that something is wrong"
      (let [response (logged-in! b "wrong horse")]
        (is (= 422 (:status response)))
        (is (str/includes? (:body response) "The email address or password is incorrect."))
        (is (not (logged-in-as-ada? b)))))))

(deftest log-out-test
  (let [b (browser)]
    (registered! b)
    (logged-in! b "correct horse")
    (let [response (submit! b "/logout" {})]
      (is (= ["/"] (:redirects response)))
      (is (str/includes? (:body response) "Log in")))
    (is (not (logged-in-as-ada? b)))))

(deftest login-required-test
  (let [b (browser)]
    (let [response (visit! b "/password/change")]
      (is (= ["/login"] (:redirects response)))
      (is (str/includes? (:body response) "Log in first.")))
    (is (= ["/login"] (:redirects (submit! b "/logout-everywhere" {}))))))

(deftest log-out-everywhere-test
  (let [phone  (browser)
        laptop (browser phone)]
    (registered! phone)
    (logged-in! phone "correct horse")
    (logged-in! laptop "correct horse")
    (visit! phone "/")
    (let [response (submit! phone "/logout-everywhere" {})]
      (is (= ["/login"] (:redirects response)))
      (is (str/includes? (:body response) "logged out on every device")))
    (is (not (logged-in-as-ada? phone)))
    (is (not (logged-in-as-ada? laptop)))))

(deftest password-reset-test
  (let [b      (browser)
        laptop (browser b)]
    (registered! b)
    (logged-in! laptop "correct horse")
    (is (= 200 (:status (visit! b "/password/forgot"))))
    (is (= ["/check-email"] (:redirects (submit! b "/password/forgot" {:email "ada@example.com"}))))
    (let [link  (last-link b)
          token (second (re-find #"token=(\S+)" link))]
      (is (= 200 (:status (visit! b link))))
      (is (= 422 (:status (submit! b "/password/reset" {:token token :password "password123"}))))
      (let [response (submit! b "/password/reset" {:token token :password "new correct horse"})]
        (is (= ["/login"] (:redirects response)))
        (is (str/includes? (:body response) "Your password has been changed.")))
      (testing "the old sessions have ended and the new password works"
        (is (not (logged-in-as-ada? laptop)))
        (logged-in! b "new correct horse")
        (is (logged-in-as-ada? b)))
      (testing "the link works once"
        (is (= 400 (:status (visit! b link))))
        (is (= 400 (:status (submit! b "/password/reset" {:token token :password "third correct horse"})))))
      (testing "an unknown address looks the same"
        (visit! b "/password/forgot")
        (is (= ["/check-email"] (:redirects (submit! b "/password/forgot" {:email "eve@example.com"}))))))))

(deftest change-password-test
  (let [b      (browser)
        laptop (browser b)]
    (registered! b)
    (logged-in! b "correct horse")
    (logged-in! laptop "correct horse")
    (is (= 200 (:status (visit! b "/password/change"))))
    (let [response (submit! b "/password/change" {:current-password "wrong horse" :new-password "new correct horse"})]
      (is (= 422 (:status response)))
      (is (str/includes? (:body response) "The email address or password is incorrect.")))
    (is (= 422 (:status (submit! b "/password/change" {:current-password "correct horse" :new-password "short"}))))
    (let [response (submit! b "/password/change" {:current-password "correct horse" :new-password "new correct horse"})]
      (is (= ["/"] (:redirects response)))
      (is (str/includes? (:body response) "Your password has been changed.")))
    (testing "this device stays logged in and the others are logged out"
      (is (logged-in-as-ada? b))
      (is (not (logged-in-as-ada? laptop))))))

(deftest log-in-throttled-test
  (let [b (browser)]
    (registered! b)
    (dotimes [_ 5]
      (logged-in! b "wrong horse"))
    (let [response (logged-in! b "correct horse")]
      (is (= 429 (:status response)))
      (is (str/includes? (:body response) "Too many failed attempts"))
      (is (not (logged-in-as-ada? b))))))
