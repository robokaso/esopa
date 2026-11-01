(ns esopa.domain.identity.account-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [esopa.domain.identity.account :as account]
            [malli.core :as m]
            [malli.generator :as mg]))

(def ^:private id #uuid "6a1f0c1e-7d5b-4a43-9a52-0f6b6d3c2a11")

(defn- at
  "The instant `minutes` after a fixed point in time."
  [minutes]
  (java.util.Date. (+ 1767225600000 (* minutes 60 1000))))

(def ^:private unconfirmed
  (account/register {:email "ada@example.com" :password-hash "hash-1"}
                    {:id id :now (at 0)}))

(def ^:private confirmed
  (merge unconfirmed (account/confirm unconfirmed (at 5))))

(deftest normalize-email-test
  (is (= "ada@example.com" (account/normalize-email "  Ada@Example.COM\t")))
  (testing "ignores the default locale, which would lower-case I differently in Turkish"
    (let [default (java.util.Locale/getDefault)]
      (try
        (java.util.Locale/setDefault (java.util.Locale/forLanguageTag "tr"))
        (is (= "info@example.com" (account/normalize-email "INFO@example.com")))
        (finally
          (java.util.Locale/setDefault default))))))

(deftest email-schema-test
  (is (m/validate account/Email "ada@example.com"))
  (is (not (m/validate account/Email "ada@example")))
  (is (not (m/validate account/Email "ada example@example.com")))
  (is (not (m/validate account/Email "@example.com")))
  (is (not (m/validate account/Email (str (apply str (repeat 243 "a")) "@example.com")))))

(deftest password-problem-test
  (testing "length is counted in characters, not UTF-16 units"
    (is (= :password/too-short (account/password-problem "1234567" "ada@example.com")))
    (is (nil? (account/password-problem "12345678" "ada@example.com")))
    (is (= :password/too-short (account/password-problem "🔑🔑🔑🔑🔑🔑🔑" "ada@example.com")))
    (is (nil? (account/password-problem (apply str (repeat 128 "x")) "ada@example.com")))
    (is (= :password/too-long (account/password-problem (apply str (repeat 129 "x")) "ada@example.com"))))

  (testing "the email address can't be the password, whatever its case"
    (is (= :password/same-as-email (account/password-problem "ADA@example.com" "ada@example.com")))))

(deftest register-test
  (is (= {:account/id               id
          :account/email            "ada@example.com"
          :account/password-hash    "hash-1"
          :account/registered-at    (at 0)
          :account/session-version  0}
         unconfirmed))
  (is (m/validate account/Account unconfirmed))
  (is (not (account/confirmed? unconfirmed))))

(deftest confirm-test
  (is (= {:account/id id :account/confirmed-at (at 5)}
         (account/confirm unconfirmed (at 5))))
  (is (account/confirmed? confirmed))
  (is (m/validate account/Account confirmed))
  (testing "confirming again changes nothing"
    (is (nil? (account/confirm confirmed (at 9))))))

(deftest log-in-test
  (testing "a confirmed account with the right password gets a session"
    (is (= {:outcome :logged-in
            :session {:account/id id :session/version 0 :session/issued-at (at 10)}}
           (account/log-in confirmed {:password-valid? true :now (at 10)}))))

  (testing "a wrong password and a missing account look the same"
    (is (= {:outcome :invalid-credentials}
           (account/log-in confirmed {:password-valid? false :now (at 10)})
           (account/log-in nil {:password-valid? false :now (at 10)}))))

  (testing "an unconfirmed account has to confirm first"
    (is (= {:outcome :unconfirmed}
           (account/log-in unconfirmed {:password-valid? true :now (at 10)}))))

  (testing "nothing is revealed about an unconfirmed account without its password"
    (is (= {:outcome :invalid-credentials}
           (account/log-in unconfirmed {:password-valid? false :now (at 10)})))))

(deftest session-valid-test
  (let [session (:session (account/log-in confirmed {:password-valid? true :now (at 10)}))
        lifetime-minutes (.toMinutes account/session-lifetime)]
    (is (account/session-valid? confirmed session (at 11)))
    (is (account/session-valid? confirmed session (at (+ 10 lifetime-minutes -1))))
    (testing "expires after the session lifetime"
      (is (not (account/session-valid? confirmed session (at (+ 10 lifetime-minutes))))))
    (testing "ends when the session version changes"
      (is (not (account/session-valid? (merge confirmed (account/log-out-everywhere confirmed))
                                       session
                                       (at 11)))))
    (testing "ends when there is no account"
      (is (not (account/session-valid? nil session (at 11)))))))

(deftest change-password-test
  (testing "the right current password changes it and ends every session"
    (let [result (account/change-password confirmed {:current-password-valid? true
                                                     :new-password-hash       "hash-2"
                                                     :now                     (at 20)})
          changed (merge confirmed (:changes result))]
      (is (= {:outcome :password-changed
              :changes {:account/id              id
                        :account/password-hash   "hash-2"
                        :account/session-version 1}
              :session {:account/id id :session/version 1 :session/issued-at (at 20)}}
             result))
      (testing "the new session keeps this device logged in"
        (is (account/session-valid? changed (:session result) (at 21))))))

  (testing "a wrong current password changes nothing"
    (is (= {:outcome :invalid-credentials}
           (account/change-password confirmed {:current-password-valid? false
                                               :new-password-hash       "hash-2"
                                               :now                     (at 20)})))))

(deftest reset-password-test
  (testing "sets the password and ends every session"
    (is (= {:account/id              id
            :account/password-hash   "hash-2"
            :account/session-version 1}
           (account/reset-password confirmed {:new-password-hash "hash-2" :now (at 30)}))))

  (testing "confirms the email address, since the reset link reached it"
    (is (= (at 30)
           (:account/confirmed-at (account/reset-password unconfirmed {:new-password-hash "hash-2"
                                                                       :now               (at 30)}))))))

(deftest log-out-everywhere-test
  (is (= {:account/id id :account/session-version 1}
         (account/log-out-everywhere confirmed))))

(deftest confirmation-link-test
  (let [link (account/link-claims :confirm-email unconfirmed (at 0))
        lifetime-minutes (.toMinutes (account/link-lifetimes :confirm-email))]
    (is (= 24 (/ lifetime-minutes 60)))
    (is (account/link-valid? :confirm-email unconfirmed link (at 1)))
    (testing "expires"
      (is (not (account/link-valid? :confirm-email unconfirmed link (at lifetime-minutes)))))
    (testing "only confirms the address it was sent to"
      (is (not (account/link-valid? :confirm-email
                                    (assoc unconfirmed :account/email "eve@example.com")
                                    link
                                    (at 1)))))
    (testing "can't be used for another purpose"
      (is (not (account/link-valid? :reset-password unconfirmed link (at 1)))))
    (testing "needs an account"
      (is (not (account/link-valid? :confirm-email nil link (at 1)))))))

(deftest reset-link-test
  (let [link (account/link-claims :reset-password confirmed (at 0))
        lifetime-minutes (.toMinutes (account/link-lifetimes :reset-password))]
    (is (= 60 lifetime-minutes))
    (is (account/link-valid? :reset-password confirmed link (at 59)))
    (is (not (account/link-valid? :reset-password confirmed link (at 60))))
    (testing "works once: the reset changes the session version"
      (let [reset (merge confirmed (account/reset-password confirmed {:new-password-hash "hash-2"
                                                                      :now               (at 1)}))]
        (is (not (account/link-valid? :reset-password reset link (at 2))))))
    (testing "logging out everywhere cancels it"
      (is (not (account/link-valid? :reset-password
                                    (merge confirmed (account/log-out-everywhere confirmed))
                                    link
                                    (at 2)))))
    (testing "belongs to one account"
      (is (not (account/link-valid? :reset-password
                                    (assoc confirmed :account/id (random-uuid))
                                    link
                                    (at 2)))))))

(defspec ^:generative normalizing-an-email-is-idempotent 100
  (prop/for-all [email (mg/generator account/Email)]
    (let [normalized (account/normalize-email email)]
      (= normalized (account/normalize-email normalized)))))

(defspec ^:generative registered-accounts-are-valid 100
  (prop/for-all [email (mg/generator account/Email)
                 hash  (mg/generator [:string {:min 1}])
                 id    (mg/generator :uuid)
                 now   (mg/generator inst?)]
    (m/validate account/Account
                (account/register {:email (account/normalize-email email) :password-hash hash}
                                  {:id id :now now}))))
