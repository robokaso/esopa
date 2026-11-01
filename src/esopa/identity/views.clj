(ns esopa.identity.views
  "HTML pages for registering, logging in and managing passwords, rendered with
  Hiccup, which escapes text by default."
  (:require [esopa.domain.identity.account :as account]
            [hiccup2.core :as h]
            [io.pedestal.http.csrf :as csrf]))

(def problem-messages
  {:email/invalid          "Enter a valid email address, like name@example.com."
   :password/too-short     (str "Use at least " (:min account/password-length) " characters.")
   :password/too-long      (str "Use at most " (:max account/password-length) " characters.")
   :password/same-as-email "Don't use your email address as your password."
   :password/breached      (str "This password has appeared in a data breach, so others may know it. "
                                "Choose a different one.")
   :credentials/invalid    "The email address or password is incorrect."})

(defn- nav
  [{:keys [account] :as request}]
  [:nav
   [:a {:href "/"} "esopa"]
   (if account
     (list [:span (:account/email account)]
           [:a {:href "/password/change"} "Change password"]
           [:form {:method "post" :action "/logout"}
            [:input {:type "hidden" :name csrf/anti-forgery-token-str :value (csrf/anti-forgery-token request)}]
            [:button "Log out"]])
     (list [:a {:href "/login"} "Log in"]
           [:a {:href "/register"} "Register"]))])

(defn- layout
  [request title & content]
  (str (h/html {:mode :html}
               (h/raw "<!DOCTYPE html>")
               [:html {:lang "en"}
                [:head
                 [:meta {:charset "utf-8"}]
                 [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                 [:title (str title " · esopa")]
                 [:link {:rel "stylesheet" :href "/assets/site.css"}]
                 [:script {:src "/assets/show-password.js" :defer true}]]
                [:body
                 [:header (nav request)]
                 [:main
                  (when-let [flash (:flash request)]
                    [:p.flash {:role "status"} flash])
                  [:h1 title]
                  content]]])))

(defn- form
  [request action & fields]
  [:form {:method "post" :action action}
   [:input {:type "hidden" :name csrf/anti-forgery-token-str :value (csrf/anti-forgery-token request)}]
   fields])

(defn- problem
  [problem-key]
  (when problem-key
    [:p.problem {:role "alert"} (problem-messages problem-key)]))

(defn- email-field
  [value]
  (list [:label {:for "email"} "Email address"]
        [:input {:id "email" :name "email" :type "email" :value value
                 :autocomplete "email" :required true}]))

(defn- password-field
  [{:keys [id label autocomplete]}]
  (list [:label {:for id} label]
        [:input.password {:id id :name id :type "password" :autocomplete autocomplete :required true}]))

(defn- new-password-field
  [id label]
  (list (password-field {:id id :label label :autocomplete "new-password"})
        [:p (str "At least " (:min account/password-length) " characters. "
                 "A few unrelated words make a strong password that's easy to remember.")]))

(def ^:private show-password
  [:p [:label.inline [:input {:type "checkbox" :data-show-password true}] " Show password"]])

(defn home
  [{:keys [account] :as request}]
  (layout request "Welcome"
          (if account
            [:p "You're logged in as " (:account/email account) "."]
            [:p "Register to get started, or log in if you have an account."])
          (when account
            (form request "/logout-everywhere"
                  [:p "Lost a device, or logged in somewhere you shouldn't have?"]
                  [:button "Log out everywhere"]))))

(defn register
  [request {:keys [email problem-key]}]
  (layout request "Register"
          (problem problem-key)
          (form request "/register"
                (email-field email)
                (new-password-field "password" "Password")
                show-password
                [:button "Register"])
          [:p "Already registered? " [:a {:href "/login"} "Log in"]]))

(defn check-email
  [request]
  (layout request "Check your email"
          [:p "If an account can use that address, we've sent it an email with a link. "
           "It can take a few minutes to arrive, so check your spam folder too."]))

(defn confirm
  [request token]
  (layout request "Confirm your email address"
          (form request "/confirm"
                [:input {:type "hidden" :name "token" :value token}]
                [:button "Confirm my email address"])))

(defn invalid-link
  [request]
  (layout request "This link doesn't work"
          [:p "It may have expired or already been used. "
           [:a {:href "/confirm/resend"} "Get a new confirmation link"] " or "
           [:a {:href "/password/forgot"} "reset your password"] "."]))

(defn resend-confirmation
  [request]
  (layout request "Get a new confirmation link"
          (form request "/confirm/resend"
                (email-field nil)
                [:button "Send link"])))

(defn log-in
  [request {:keys [email problem-key]}]
  (layout request "Log in"
          (problem problem-key)
          (form request "/login"
                (email-field email)
                (password-field {:id "password" :label "Password" :autocomplete "current-password"})
                show-password
                [:button "Log in"])
          [:p [:a {:href "/password/forgot"} "Forgot your password?"]]
          [:p "No account yet? " [:a {:href "/register"} "Register"]]))

(defn unconfirmed
  [request]
  (layout request "Confirm your email address first"
          [:p "We sent you an email with a link when you registered. Open it to confirm your address, then log in."]
          [:p [:a {:href "/confirm/resend"} "Get a new confirmation link"]]))

(defn throttled
  [request]
  (layout request "Too many failed attempts"
          [:p "To protect your account, logging in is paused for a while. "
           "Wait " (.toMinutes ^java.time.Duration (:window (account/limits :failed-logins-per-email)))
           " minutes and try again, or " [:a {:href "/password/forgot"} "reset your password"] "."]))

(defn forgot-password
  [request]
  (layout request "Reset your password"
          [:p "Enter your email address and we'll send you a link to choose a new password."]
          (form request "/password/forgot"
                (email-field nil)
                [:button "Send link"])))

(defn reset-password
  [request {:keys [token problem-key]}]
  (layout request "Choose a new password"
          (problem problem-key)
          (form request "/password/reset"
                [:input {:type "hidden" :name "token" :value token}]
                (new-password-field "password" "New password")
                show-password
                [:button "Save password"])))

(defn change-password
  [request {:keys [problem-key]}]
  (layout request "Change password"
          (problem problem-key)
          (form request "/password/change"
                (password-field {:id "current-password" :label "Current password" :autocomplete "current-password"})
                (new-password-field "new-password" "New password")
                show-password
                [:button "Change password"])
          [:p "You'll stay logged in here, and be logged out everywhere else."]))
