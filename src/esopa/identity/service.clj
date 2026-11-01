(ns esopa.identity.service
  "The identity use cases. Each takes `deps` and the input, performs the effects and
  returns an outcome map; the rules come from esopa.domain.identity.account.

  `deps` holds:

  :conn              Datomic connection
  :password-options  argon2id settings (see esopa.security.password)
  :token-key         byte array that signs the links in emails
  :send-email        function of an email map
  :breached?         function of a password; true when it appears in a breach
  :now               function returning the current time
  :base-url          where esopa is served, for the links in emails
  :counters          atoms of throttle counters, from new-counters"
  (:require [datomic.client.api :as d]
            [esopa.domain.identity.account :as account]
            [esopa.identity.db :as identity-db]
            [esopa.identity.emails :as emails]
            [esopa.security.password :as password]
            [esopa.security.throttle :as throttle]
            [esopa.security.token :as token]
            [io.pedestal.log :as log]
            [malli.core :as m]))

(defn new-counters
  "Empty in-memory counters for each of account/limits, for `deps` :counters."
  []
  (update-vals account/limits (fn [_] (atom {}))))

(defn- now
  [deps]
  ((:now deps)))

(defn- allowed?
  "True when the `limit-key` limit (see account/limits) allows another event for `key`."
  [deps limit-key key]
  (throttle/allowed? @(get-in deps [:counters limit-key]) (account/limits limit-key) key (now deps)))

(defn- count!
  [deps limit-key key]
  (swap! (get-in deps [:counters limit-key]) throttle/count-event (account/limits limit-key) key (now deps)))

(defn- clear!
  [deps limit-key key]
  (swap! (get-in deps [:counters limit-key]) throttle/clear key))

(defn- send-email!
  "Sends `email` unless its address has had as many emails as account/limits allow."
  [deps email]
  (if (allowed? deps :emails-per-address (:to email))
    (do (count! deps :emails-per-address (:to email))
        ((:send-email deps) email))
    (log/warn :msg "Email not sent: too many emails to this address" :subject (:subject email))))

(defn- account-by-email
  [deps email]
  (identity-db/account-by-email (d/db (:conn deps)) (account/normalize-email email)))

(defn- hash-password
  [deps password]
  (password/hash-password (:password-options deps) password))

(defn- link
  "The URL of the emailed link for `purpose`."
  [deps path purpose account]
  (str (:base-url deps) path "?token="
       (token/sign (:token-key deps) (account/link-claims purpose account (now deps)))))

(defn- link-account
  "The account the link `token` was sent to, when the link is still valid for `purpose`."
  [deps purpose token]
  (let [claims  (token/unsign (:token-key deps) token)
        account (some->> (:account/id claims) (identity-db/account-by-id (d/db (:conn deps))))]
    (when (account/link-valid? purpose account claims (now deps))
      account)))

(defn- new-password-problem
  "Why `password` can't be used by the account with `email`, or nil. Only asks
  Pwned Passwords about passwords that pass the other rules."
  [deps email password]
  (or (account/password-problem password email)
      (when ((:breached? deps) password)
        :password/breached)))

(defn- registration-problem
  [deps email password]
  (if (m/validate account/Email email)
    (new-password-problem deps email password)
    :email/invalid))

(defn- send-confirmation!
  [deps account]
  (send-email! deps (emails/confirmation {:to   (:account/email account)
                                         :link (link deps "/confirm" :confirm-email account)})))

(defn- send-already-registered!
  [deps email]
  (send-email! deps (emails/already-registered {:to                   email
                                               :login-link           (str (:base-url deps) "/login")
                                               :forgot-password-link (str (:base-url deps) "/password/forgot")})))

(defn register!
  "Registers a new account and emails a confirmation link. When the address already
  has an account, emails its owner instead, with the same outcome, so registering
  doesn't reveal which addresses have accounts."
  [deps {:keys [email password]}]
  (let [email (account/normalize-email email)]
    (if-let [problem (registration-problem deps email password)]
      {:outcome :invalid :problem problem}
      (let [new-account (account/register {:email email :password-hash (hash-password deps password)}
                                          {:id (random-uuid) :now (now deps)})]
        (case (identity-db/create-account! (:conn deps) new-account)
          :created     (send-confirmation! deps new-account)
          :email-taken (send-already-registered! deps email))
        {:outcome :registered}))))

(defn confirm!
  "Confirms the email address the confirmation link `token` was sent to."
  [deps token]
  (if-let [link-account (link-account deps :confirm-email token)]
    (do (some->> (account/confirm link-account (now deps)) (identity-db/save! (:conn deps)))
        {:outcome :confirmed})
    {:outcome :invalid-link}))

(defn resend-confirmation!
  "Emails a new confirmation link when `email` belongs to an unconfirmed account.
  The outcome is the same either way."
  [deps email]
  (let [account (account-by-email deps email)]
    (when (and account (not (account/confirmed? account)))
      (send-confirmation! deps account))
    {:outcome :sent}))

(defn- log-in-allowed?
  [deps email client-ip]
  (and (allowed? deps :failed-logins-per-email email)
       (allowed? deps :failed-logins-per-ip client-ip)))

(defn- count-log-in!
  "Counts a failed login against the address and client IP; a successful one
  clears the address's failures."
  [deps outcome email client-ip]
  (if (= :invalid-credentials (:outcome outcome))
    (do (count! deps :failed-logins-per-email email)
        (count! deps :failed-logins-per-ip client-ip))
    (clear! deps :failed-logins-per-email email)))

(defn log-in
  "Checks an email address and password; see account/log-in for the outcomes.
  After too many failures (see account/limits), the outcome is :throttled without
  checking the password."
  [deps {:keys [email password client-ip]}]
  (let [email (account/normalize-email email)]
    (if (log-in-allowed? deps email client-ip)
      (let [account (identity-db/account-by-email (d/db (:conn deps)) email)
            valid?  (password/verify-password (:password-options deps) (:account/password-hash account) password)
            outcome (account/log-in account {:password-valid? valid? :now (now deps)})]
        (count-log-in! deps outcome email client-ip)
        outcome)
      {:outcome :throttled})))

(defn session-account
  "The account `session` logs in to, or nil when it is missing, expired or ended."
  [deps session]
  (when-let [id (:account/id session)]
    (let [account (identity-db/account-by-id (d/db (:conn deps)) id)]
      (when (account/session-valid? account session (now deps))
        account))))

(defn log-out-everywhere!
  "Ends every session of `account`."
  [deps account]
  (identity-db/save! (:conn deps) (account/log-out-everywhere account))
  {:outcome :logged-out-everywhere})

(defn request-password-reset!
  "Emails a password reset link when `email` belongs to an account. The outcome is
  the same either way."
  [deps email]
  (when-let [account (account-by-email deps email)]
    (send-email! deps (emails/password-reset {:to   (:account/email account)
                                             :link (link deps "/password/reset" :reset-password account)})))
  {:outcome :sent})

(defn reset-link-valid?
  "True when the password reset link `token` still works."
  [deps token]
  (some? (link-account deps :reset-password token)))

(defn reset-password!
  "Sets a new password through the reset link `token`."
  [deps {:keys [token password]}]
  (if-let [account (link-account deps :reset-password token)]
    (if-let [problem (new-password-problem deps (:account/email account) password)]
      {:outcome :invalid :problem problem}
      (do (identity-db/save! (:conn deps) (account/reset-password account {:new-password-hash (hash-password deps password)
                                                                           :now               (now deps)}))
          {:outcome :password-reset}))
    {:outcome :invalid-link}))

(defn change-password!
  "Changes the password of the logged-in `account`. On success, the outcome holds a
  new session for the current device; every other session has ended."
  [deps account {:keys [current-password new-password]}]
  (if-let [problem (new-password-problem deps (:account/email account) new-password)]
    {:outcome :invalid :problem problem}
    (let [current-valid? (password/verify-password (:password-options deps)
                                                   (:account/password-hash account)
                                                   current-password)
          result         (account/change-password account {:current-password-valid? current-valid?
                                                           :new-password-hash       (hash-password deps new-password)
                                                           :now                     (now deps)})]
      (some->> (:changes result) (identity-db/save! (:conn deps)))
      (dissoc result :changes))))
