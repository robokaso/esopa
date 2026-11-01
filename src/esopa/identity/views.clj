(ns esopa.identity.views
  "HTML pages for registering, logging in and managing passwords, in the site's
  layout (see esopa.views)."
  (:require [clojure.string :as str]
            [esopa.domain.identity.account :as account]
            [esopa.views :as views]))

(def problem-messages
  {:email/invalid          "Enter a valid email address, like name@example.com."
   :password/too-short     (str "Use at least " (:min account/password-length) " characters.")
   :password/too-long      (str "Use at most " (:max account/password-length) " characters.")
   :password/same-as-email "Don't use your email address as your password."
   :password/breached      (str "This password has appeared in a data breach, so others may know it. "
                                "Choose a different one.")
   :credentials/invalid    "The email address or password is incorrect."})

(defn- problem
  "The message for `problem-key`, which screen readers announce and which takes focus."
  [problem-key]
  (when problem-key
    [:p#problem {:role "alert" :tabindex "-1" :autofocus true} (problem-messages problem-key)]))

(defn- about?
  "Whether the problem `problem-key` is about `topic`. Problem keys are namespaced
  by what they're about, like :password/too-short."
  [problem-key topic]
  (and (some? problem-key) (= topic (namespace problem-key))))

(defn- field
  "A labelled input with `attributes`. When `problem-key` is about the field's
  `topic`, the input is marked invalid and described by the problem. A `help` text
  below the input describes it too."
  [{:keys [id label topic problem-key help]} attributes]
  (let [invalid? (about? problem-key topic)
        help-id  (when help (str id "-help"))]
    (list [:label {:for id} label]
          [:input (merge {:id               id
                          :name             id
                          :required         true
                          :aria-invalid     (when invalid? "true")
                          :aria-describedby (some->> [(when invalid? "problem") help-id]
                                                     (remove nil?)
                                                     seq
                                                     (str/join " "))}
                         attributes)]
          (when help
            [:small {:id help-id} help]))))

(defn- email-field
  [{:keys [value problem-key]}]
  (field {:id "email" :label "Email address" :topic "email" :problem-key problem-key}
         {:type "email" :value value :autocomplete "email"}))

(defn- password-field
  [{:keys [id label autocomplete]}]
  (field {:id id :label label}
         {:type "password" :class "password" :autocomplete autocomplete}))

(defn- new-password-field
  [{:keys [id label problem-key]}]
  (field {:id          id
          :label       label
          :topic       "password"
          :problem-key problem-key
          :help        (str "At least " (:min account/password-length) " characters. "
                            "A few unrelated words make a strong password that's easy to remember.")}
         {:type "password" :class "password" :autocomplete "new-password"}))

(def ^:private show-password
  [:label [:input {:type "checkbox" :role "switch" :data-show-password true}] "Show password"])

(defn home
  [{:keys [account] :as request}]
  (views/layout request "Welcome"
                (if account
                  [:p "You're logged in as " (:account/email account) "."]
                  [:p "Register to get started, or log in if you have an account."])
                (when account
                  (views/form request "/logout-everywhere"
                              [:p "Lost a device, or logged in somewhere you shouldn't have?"]
                              [:button "Log out everywhere"]))))

(defn register
  [request {:keys [email problem-key]}]
  (views/layout request "Register"
                (problem problem-key)
                (views/form request "/register"
                            (email-field {:value email :problem-key problem-key})
                            (new-password-field {:id "password" :label "Password" :problem-key problem-key})
                            show-password
                            [:button "Register"])
                [:p "Already registered? " [:a {:href "/login"} "Log in"]]))

(defn check-email
  [request]
  (views/layout request "Check your email"
                [:p "If an account can use that address, we've sent it an email with a link. "
                 "It can take a few minutes to arrive, so check your spam folder too."]))

(defn confirm
  [request token]
  (views/layout request "Confirm your email address"
                (views/form request "/confirm"
                            [:input {:type "hidden" :name "token" :value token}]
                            [:button "Confirm my email address"])))

(defn invalid-link
  [request]
  (views/layout request "This link doesn't work"
                [:p "It may have expired or already been used. "
                 [:a {:href "/confirm/resend"} "Get a new confirmation link"] " or "
                 [:a {:href "/password/forgot"} "reset your password"] "."]))

(defn resend-confirmation
  [request]
  (views/layout request "Get a new confirmation link"
                (views/form request "/confirm/resend"
                            (email-field {})
                            [:button "Send link"])))

(defn log-in
  [request {:keys [email problem-key]}]
  (views/layout request "Log in"
                (problem problem-key)
                (views/form request "/login"
                            (email-field {:value email})
                            (password-field {:id "password" :label "Password" :autocomplete "current-password"})
                            show-password
                            [:button "Log in"])
                [:p [:a {:href "/password/forgot"} "Forgot your password?"]]
                [:p "No account yet? " [:a {:href "/register"} "Register"]]))

(defn unconfirmed
  [request]
  (views/layout request "Confirm your email address first"
                [:p "We sent you an email with a link when you registered. Open it to confirm your address, then log in."]
                [:p [:a {:href "/confirm/resend"} "Get a new confirmation link"]]))

(defn throttled
  [request]
  (views/layout request "Too many failed attempts"
                [:p "To protect your account, logging in is paused for a while. "
                 "Wait " (.toMinutes ^java.time.Duration (:window (account/limits :failed-logins-per-email)))
                 " minutes and try again, or " [:a {:href "/password/forgot"} "reset your password"] "."]))

(defn forgot-password
  [request]
  (views/layout request "Reset your password"
                [:p "Enter your email address and we'll send you a link to choose a new password."]
                (views/form request "/password/forgot"
                            (email-field {})
                            [:button "Send link"])))

(defn reset-password
  [request {:keys [token problem-key]}]
  (views/layout request "Choose a new password"
                (problem problem-key)
                (views/form request "/password/reset"
                            [:input {:type "hidden" :name "token" :value token}]
                            (new-password-field {:id "password" :label "New password" :problem-key problem-key})
                            show-password
                            [:button "Save password"])))

(defn change-password
  [request {:keys [problem-key]}]
  (views/layout request "Change password"
                (problem problem-key)
                (views/form request "/password/change"
                            (password-field {:id           "current-password"
                                             :label        "Current password"
                                             :autocomplete "current-password"})
                            (new-password-field {:id "new-password" :label "New password" :problem-key problem-key})
                            show-password
                            [:button "Change password"])
                [:p "You'll stay logged in here, and be logged out everywhere else."]))
