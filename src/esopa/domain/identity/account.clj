(ns esopa.domain.identity.account
  "The Account aggregate of the identity context: registration, email confirmation,
  passwords and login sessions. See docs/domain.md.

  Pure: callers pass in ids, the current time, password hashes and whether a
  password was right. Functions that change an account return only the attributes
  that change, as a map that is also Datomic tx-data."
  (:require [clojure.string :as str])
  (:import (java.time Duration)
           (java.util Date Locale)))

(def password-length
  "Allowed password lengths, in characters (Unicode code points)."
  {:min 8 :max 128})

(def session-lifetime
  "How long a login lasts. Activity doesn't extend it."
  (Duration/ofDays 14))

(def link-lifetimes
  "How long the link in each kind of email works."
  {:confirm-email  (Duration/ofHours 24)
   :reset-password (Duration/ofHours 1)})

(def limits
  "How often things may happen before further attempts are refused, as
  esopa.security.throttle limits. Failed logins are limited per email address,
  whether or not it has an account, and per client IP. The account itself is
  never locked."
  {:failed-logins-per-email {:limit 5 :window (Duration/ofMinutes 15)}
   :failed-logins-per-ip    {:limit 50 :window (Duration/ofMinutes 15)}
   :emails-per-address      {:limit 5 :window (Duration/ofHours 1)}})

(def Email
  "A normalized email address (see normalize-email)."
  [:and {:gen/schema [:tuple [:string {:min 1 :max 20}] [:string {:min 1 :max 20}]]
         :gen/fmap   (fn [[local domain]] (str local "@" domain ".example"))}
   [:string {:max 254}]
   [:re #"^[^\s@]+@[^\s@]+\.[^\s@]+$"]])

(def Account
  [:map
   [:account/id :uuid]
   [:account/email Email]
   [:account/password-hash :string]
   [:account/registered-at inst?]
   [:account/confirmed-at {:optional true} inst?]
   [:account/session-version nat-int?]])

(defn normalize-email
  "The canonical form of an email address: trimmed and lower-cased, so addresses
  compare case-insensitively."
  [email]
  (.toLowerCase ^String (str/trim email) Locale/ROOT))

(defn- code-point-count
  [^String s]
  (.codePointCount s 0 (.length s)))

(defn password-problem
  "Why `password` can't be used by the account with `email`, or nil when it can.
  Breached passwords are rejected too, but checking for them needs a network call,
  so callers do that separately."
  [password email]
  (let [length (code-point-count password)]
    (cond
      (< length (:min password-length))                    :password/too-short
      (> length (:max password-length))                    :password/too-long
      (= (normalize-email password) (normalize-email email)) :password/same-as-email)))

(defn register
  "A new account, unconfirmed until its email address is confirmed."
  [{:keys! [email password-hash]} {:keys! [id now]}]
  {:account/id              id
   :account/email           email
   :account/password-hash   password-hash
   :account/registered-at   now
   :account/session-version 0})

(defn confirmed?
  [account]
  (contains? account :account/confirmed-at))

(defn confirm
  "Confirms the account's email address. Returns nil when it was already confirmed."
  [account now]
  (when-not (confirmed? account)
    {:account/id           (:account/id account)
     :account/confirmed-at now}))

(defn- session
  "The data a login session cookie holds."
  [account now]
  {:account/id        (:account/id account)
   :session/version   (:account/session-version account)
   :session/issued-at now})

(defn log-in
  "The outcome of a login attempt. `account` is nil when no account has the email.
  A successful login includes the session to store in the cookie."
  [account {:keys! [password-valid? now]}]
  (cond
    (not (and account password-valid?)) {:outcome :invalid-credentials}
    (not (confirmed? account))          {:outcome :unconfirmed}
    :else                               {:outcome :logged-in
                                         :session (session account now)}))

(defn- before-expiry?
  [start ^Duration lifetime now]
  (< (inst-ms now) (+ (inst-ms start) (.toMillis lifetime))))

(defn session-valid?
  "True when `session` still logs in to `account`: it was issued for the account's
  current session version, less than session-lifetime ago."
  [account session now]
  (and (= (:account/session-version account) (:session/version session))
       (before-expiry? (:session/issued-at session) session-lifetime now)))

(defn- ending-sessions
  "Changes that end every session of the account."
  [account]
  {:account/id              (:account/id account)
   :account/session-version (inc (:account/session-version account))})

(defn change-password
  "Changes the password of a logged-in account, given whether the current password
  was right. Ends every session, and returns a new one for the current device."
  [account {:keys! [current-password-valid? new-password-hash now]}]
  (if current-password-valid?
    (let [changes (assoc (ending-sessions account) :account/password-hash new-password-hash)]
      {:outcome :password-changed
       :changes changes
       :session (session (merge account changes) now)})
    {:outcome :invalid-credentials}))

(defn reset-password
  "Sets a new password through a reset link, ending every session. Also confirms
  the email address, since the link reached it."
  [account {:keys! [new-password-hash now]}]
  (merge (ending-sessions account)
         {:account/password-hash new-password-hash}
         (confirm account now)))

(defn log-out-everywhere
  "Ends every session of the account."
  [account]
  (ending-sessions account))

(def ^:private link-keys
  "The account attributes a link is bound to. A link stops working when they change."
  {:confirm-email  [:account/id :account/email]
   :reset-password [:account/id :account/session-version]})

(defn link-claims
  "What the emailed link for `purpose` (:confirm-email or :reset-password) carries.
  The caller signs it."
  [purpose account now]
  (merge (select-keys account (link-keys purpose))
         {:link/purpose    purpose
          :link/expires-at (Date. (+ (inst-ms now) (.toMillis ^Duration (link-lifetimes purpose))))}))

(defn link-valid?
  "True when the `link` claims are for `purpose`, haven't expired and still match
  `account`."
  [purpose account link now]
  (let [ks (link-keys purpose)]
    (and (= purpose (:link/purpose link))
         (= (select-keys link ks) (select-keys account ks))
         (< (inst-ms now) (inst-ms (:link/expires-at link))))))
