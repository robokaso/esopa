(ns esopa.identity.service-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [esopa.db :as db]
            [esopa.domain.identity.account :as account]
            [esopa.identity.db :as identity-db]
            [esopa.identity.service :as service]))

(def ^:private start #inst "2026-01-01T10:00:00.000-00:00")

(defn test-deps
  "Dependencies for the identity use cases, backed by a fresh in-memory database.
  Sent emails collect in ::outbox, ::clock is the current time and
  \"password123\" is the only breached password."
  []
  (let [outbox (atom [])
        clock  (atom start)]
    {:conn             (db/connect {:storage-dir :mem :db-name (str (random-uuid))})
     :password-options {:alg :argon2id :memory 8 :iterations 1}
     :token-key        (byte-array (range 32))
     :send-email       #(swap! outbox conj %)
     :breached?        #(= "password123" %)
     :now              #(deref clock)
     :base-url         "http://esopa.test"
     :counters         (service/new-counters)
     ::outbox          outbox
     ::clock           clock}))

(defn- advance!
  [deps duration]
  (swap! (::clock deps) #(java.util.Date. (+ (inst-ms %) (.toMillis ^java.time.Duration duration)))))

(defn- sent
  [deps]
  @(::outbox deps))

(defn- token-in
  [email]
  (second (re-find #"token=(\S+)" (:body email))))

(defn- account-for
  [deps email]
  (identity-db/account-by-email (d/db (:conn deps)) email))

(defn- registered!
  "Registers and confirms ada@example.com, and empties the outbox."
  [deps]
  (service/register! deps {:email "ada@example.com" :password "correct horse"})
  (service/confirm! deps (token-in (last (sent deps))))
  (reset! (::outbox deps) [])
  (account-for deps "ada@example.com"))

(defn- logged-in-session
  [deps password]
  (:session (service/log-in deps {:email "ada@example.com" :password password})))

(deftest register-test
  (let [deps (test-deps)]
    (is (= {:outcome :registered}
           (service/register! deps {:email " Ada@Example.com " :password "correct horse"})))
    (testing "stores an unconfirmed account under the normalized address"
      (let [ada (account-for deps "ada@example.com")]
        (is (some? ada))
        (is (not (account/confirmed? ada)))))
    (testing "emails a confirmation link"
      (let [[email] (sent deps)]
        (is (= 1 (count (sent deps))))
        (is (= "ada@example.com" (:to email)))
        (is (str/includes? (:body email) "http://esopa.test/confirm?token="))
        (is (str/includes? (:body email) "24 hours"))))))

(deftest register-rejects-invalid-input-test
  (let [deps   (test-deps)
        checks (atom [])
        deps   (assoc deps :breached? #(do (swap! checks conj %) (= "password123" %)))]
    (is (= {:outcome :invalid :problem :email/invalid}
           (service/register! deps {:email "ada" :password "correct horse"})))
    (is (= {:outcome :invalid :problem :password/too-short}
           (service/register! deps {:email "ada@example.com" :password "short"})))
    (is (= {:outcome :invalid :problem :password/same-as-email}
           (service/register! deps {:email "ada@example.com" :password "Ada@example.com"})))
    (is (= {:outcome :invalid :problem :password/breached}
           (service/register! deps {:email "ada@example.com" :password "password123"})))
    (testing "the breach check only runs for passwords that pass the other rules"
      (is (= ["password123"] @checks)))
    (testing "nothing is stored or sent"
      (is (nil? (account-for deps "ada@example.com")))
      (is (empty? (sent deps))))))

(deftest register-taken-address-test
  (let [deps (test-deps)
        ada  (registered! deps)]
    (testing "looks the same as a new registration"
      (is (= {:outcome :registered}
             (service/register! deps {:email "ada@example.com" :password "another password"}))))
    (testing "emails the owner instead of a confirmation link"
      (let [[email] (sent deps)]
        (is (= "ada@example.com" (:to email)))
        (is (str/includes? (:body email) "already has an account"))
        (is (str/includes? (:body email) "http://esopa.test/password/forgot"))
        (is (not (str/includes? (:body email) "token=")))))
    (testing "leaves the account alone"
      (is (= ada (account-for deps "ada@example.com"))))))

(deftest confirm-test
  (let [deps (test-deps)]
    (service/register! deps {:email "ada@example.com" :password "correct horse"})
    (let [token (token-in (first (sent deps)))]
      (is (= {:outcome :confirmed} (service/confirm! deps token)))
      (is (= start (:account/confirmed-at (account-for deps "ada@example.com"))))
      (testing "using the link again is harmless"
        (is (= {:outcome :confirmed} (service/confirm! deps token)))))))

(deftest confirm-rejects-bad-links-test
  (let [deps (test-deps)]
    (service/register! deps {:email "ada@example.com" :password "correct horse"})
    (let [token (token-in (first (sent deps)))]
      (is (= {:outcome :invalid-link} (service/confirm! deps "garbage")))
      (is (= {:outcome :invalid-link} (service/confirm! deps nil)))
      (advance! deps (account/link-lifetimes :confirm-email))
      (is (= {:outcome :invalid-link} (service/confirm! deps token)))
      (is (not (account/confirmed? (account-for deps "ada@example.com")))))))

(deftest resend-confirmation-test
  (let [deps (test-deps)]
    (service/register! deps {:email "ada@example.com" :password "correct horse"})
    (reset! (::outbox deps) [])
    (is (= {:outcome :sent} (service/resend-confirmation! deps "ADA@example.com")))
    (is (= {:outcome :confirmed} (service/confirm! deps (token-in (first (sent deps))))))
    (testing "nothing is sent to confirmed or unknown addresses, but the outcome is the same"
      (reset! (::outbox deps) [])
      (is (= {:outcome :sent} (service/resend-confirmation! deps "ada@example.com")))
      (is (= {:outcome :sent} (service/resend-confirmation! deps "eve@example.com")))
      (is (empty? (sent deps))))))

(deftest log-in-test
  (let [deps (test-deps)
        ada  (registered! deps)]
    (is (= {:outcome :logged-in
            :session {:account/id (:account/id ada) :session/version 0 :session/issued-at start}}
           (service/log-in deps {:email "ADA@example.com" :password "correct horse"})))
    (is (= {:outcome :invalid-credentials}
           (service/log-in deps {:email "ada@example.com" :password "wrong horse"})
           (service/log-in deps {:email "eve@example.com" :password "correct horse"})))))

(deftest log-in-unconfirmed-test
  (let [deps (test-deps)]
    (service/register! deps {:email "ada@example.com" :password "correct horse"})
    (is (= {:outcome :unconfirmed}
           (service/log-in deps {:email "ada@example.com" :password "correct horse"})))))

(deftest session-account-test
  (let [deps    (test-deps)
        ada     (registered! deps)
        session (logged-in-session deps "correct horse")]
    (is (= ada (service/session-account deps session)))
    (is (nil? (service/session-account deps nil)))
    (is (nil? (service/session-account deps {})))
    (testing "expires"
      (advance! deps account/session-lifetime)
      (is (nil? (service/session-account deps session))))))

(deftest log-out-everywhere-test
  (let [deps    (test-deps)
        ada     (registered! deps)
        session (logged-in-session deps "correct horse")]
    (is (= {:outcome :logged-out-everywhere} (service/log-out-everywhere! deps ada)))
    (is (nil? (service/session-account deps session)))))

(deftest password-reset-test
  (let [deps    (test-deps)
        _       (registered! deps)
        session (logged-in-session deps "correct horse")]
    (is (= {:outcome :sent} (service/request-password-reset! deps "Ada@example.com")))
    (let [[email] (sent deps)
          token   (token-in email)]
      (is (= "ada@example.com" (:to email)))
      (is (str/includes? (:body email) "http://esopa.test/password/reset?token="))
      (is (str/includes? (:body email) "1 hour"))
      (is (service/reset-link-valid? deps token))

      (testing "rejects an unacceptable password and keeps the link working"
        (is (= {:outcome :invalid :problem :password/breached}
               (service/reset-password! deps {:token token :password "password123"})))
        (is (service/reset-link-valid? deps token)))

      (is (= {:outcome :password-reset}
             (service/reset-password! deps {:token token :password "new correct horse"})))
      (testing "the new password works and the old one doesn't"
        (is (= :logged-in (:outcome (service/log-in deps {:email "ada@example.com" :password "new correct horse"}))))
        (is (= :invalid-credentials (:outcome (service/log-in deps {:email "ada@example.com" :password "correct horse"})))))
      (testing "ends every session"
        (is (nil? (service/session-account deps session))))
      (testing "the link works once"
        (is (not (service/reset-link-valid? deps token)))
        (is (= {:outcome :invalid-link}
               (service/reset-password! deps {:token token :password "third correct horse"})))))))

(deftest password-reset-unknown-address-test
  (let [deps (test-deps)]
    (is (= {:outcome :sent} (service/request-password-reset! deps "eve@example.com")))
    (is (empty? (sent deps)))))

(deftest password-reset-link-expires-test
  (let [deps (test-deps)]
    (registered! deps)
    (service/request-password-reset! deps "ada@example.com")
    (advance! deps (account/link-lifetimes :reset-password))
    (is (not (service/reset-link-valid? deps (token-in (first (sent deps))))))
    (is (not (service/reset-link-valid? deps "garbage")))))

(deftest password-reset-confirms-test
  (let [deps (test-deps)]
    (service/register! deps {:email "ada@example.com" :password "correct horse"})
    (service/request-password-reset! deps "ada@example.com")
    (service/reset-password! deps {:token (token-in (last (sent deps))) :password "new correct horse"})
    (is (account/confirmed? (account-for deps "ada@example.com")))))

(deftest change-password-test
  (let [deps    (test-deps)
        ada     (registered! deps)
        session (logged-in-session deps "correct horse")]
    (testing "a wrong current password changes nothing"
      (is (= {:outcome :invalid-credentials}
             (service/change-password! deps ada {:current-password "wrong horse" :new-password "new correct horse"})))
      (is (= ada (account-for deps "ada@example.com"))))

    (testing "an unacceptable new password changes nothing"
      (is (= {:outcome :invalid :problem :password/too-short}
             (service/change-password! deps ada {:current-password "correct horse" :new-password "short"})))
      (is (= ada (account-for deps "ada@example.com"))))

    (let [result (service/change-password! deps ada {:current-password "correct horse"
                                                     :new-password     "new correct horse"})
          changed (account-for deps "ada@example.com")]
      (is (= :password-changed (:outcome result)))
      (testing "keeps this device logged in and logs out the others"
        (is (= changed (service/session-account deps (:session result))))
        (is (nil? (service/session-account deps session))))
      (testing "the new password works"
        (is (= :logged-in (:outcome (service/log-in deps {:email "ada@example.com" :password "new correct horse"}))))))))

(defn- attempt
  [deps email password client-ip]
  (:outcome (service/log-in deps {:email email :password password :client-ip client-ip})))

(deftest log-in-throttling-test
  (let [deps (test-deps)]
    (registered! deps)
    (dotimes [_ 5]
      (is (= :invalid-credentials (attempt deps "ada@example.com" "wrong horse" "192.0.2.1"))))
    (testing "after 5 failures the address is refused, even with the right password and from elsewhere"
      (is (= :throttled (attempt deps "ADA@example.com" "correct horse" "198.51.100.7"))))
    (testing "an address without an account is treated the same"
      (dotimes [_ 5]
        (attempt deps "eve@example.com" "wrong horse" "198.51.100.7"))
      (is (= :throttled (attempt deps "eve@example.com" "wrong horse" "198.51.100.7"))))
    (testing "allowed again when the window ends"
      (advance! deps (:window (account/limits :failed-logins-per-email)))
      (is (= :logged-in (attempt deps "ada@example.com" "correct horse" "192.0.2.1"))))))

(deftest log-in-success-clears-failures-test
  (let [deps (test-deps)]
    (registered! deps)
    (dotimes [_ 4]
      (attempt deps "ada@example.com" "wrong horse" "192.0.2.1"))
    (is (= :logged-in (attempt deps "ada@example.com" "correct horse" "192.0.2.1")))
    (dotimes [_ 4]
      (attempt deps "ada@example.com" "wrong horse" "192.0.2.1"))
    (is (= :logged-in (attempt deps "ada@example.com" "correct horse" "192.0.2.1")))))

(deftest log-in-ip-throttling-test
  (let [deps (test-deps)]
    (registered! deps)
    (doseq [n (range 50)]
      (attempt deps (str "user" n "@example.com") "wrong horse" "192.0.2.1"))
    (testing "after 50 failures from one address, it is refused for every account"
      (is (= :throttled (attempt deps "ada@example.com" "correct horse" "192.0.2.1"))))
    (testing "other client addresses aren't affected"
      (is (= :logged-in (attempt deps "ada@example.com" "correct horse" "198.51.100.7"))))))

(deftest email-cap-test
  (let [deps (test-deps)]
    (service/register! deps {:email "ada@example.com" :password "correct horse"})
    (dotimes [_ 5]
      (service/resend-confirmation! deps "ada@example.com"))
    (testing "at most 5 emails an hour reach one address; the outcome doesn't change"
      (is (= 5 (count (sent deps))))
      (is (= {:outcome :sent} (service/request-password-reset! deps "ada@example.com")))
      (is (= 5 (count (sent deps)))))
    (advance! deps (:window (account/limits :emails-per-address)))
    (service/request-password-reset! deps "ada@example.com")
    (is (= 6 (count (sent deps))))))
