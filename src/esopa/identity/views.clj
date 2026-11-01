(ns esopa.identity.views
  "HTML pages for registering, logging in and managing passwords, rendered with
  Hiccup, which escapes text by default. Pico styles the semantic HTML, and htmx
  boosts links and forms (see layout)."
  (:require [clojure.string :as str]
            [esopa.domain.identity.account :as account]
            [esopa.webjars :as webjars]
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

(def ^:private stylesheets
  [(webjars/path "picocss__pico" "css/pico.min.css")
   "/assets/site.css"])

(def ^:private scripts
  [(webjars/path "htmx.org" "dist/htmx.min.js")
   "/assets/show-password.js"
   "/assets/show-busy.js"
   "/assets/move-focus.js"])

(defn- form
  "A form that posts `fields` to `action` with the request's anti-forgery token."
  [request action & fields]
  [:form {:method "post" :action action}
   [:input {:type "hidden" :name csrf/anti-forgery-token-str :value (csrf/anti-forgery-token request)}]
   fields])

(defn- nav
  [{:keys [account] :as request}]
  [:nav
   [:ul [:li [:a {:href "/"} [:strong "esopa"]]]]
   [:ul
    (if account
      (list [:li (:account/email account)]
            [:li [:a {:href "/password/change"} "Change password"]]
            [:li (form request "/logout" [:button "Log out"])])
      (list [:li [:a {:href "/login"} "Log in"]]
            [:li [:a {:href "/register"} "Register"]]))]])

(defn- layout
  "A page titled `title`. htmx boosts its links and forms: it fetches the next page
  and swaps in its body and title instead of loading it, then move-focus.js moves
  focus to the heading. A message in the flash follows the heading, so reading on
  from there reaches it."
  [request title & content]
  (str (h/html {:mode :html}
               (h/raw "<!DOCTYPE html>")
               [:html {:lang "en"}
                [:head
                 [:meta {:charset "utf-8"}]
                 [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
                 [:meta {:name "htmx-config" :content "includeIndicatorCSS:false"}]
                 [:title (str title " · esopa")]
                 (for [href stylesheets]
                   [:link {:rel "stylesheet" :href href}])
                 (for [src scripts]
                   [:script {:src src :defer true}])]
                [:body {:hx-boost:inherited "true"}
                 [:header.container (nav request)]
                 [:main.container
                  [:h1 {:tabindex "-1"} title]
                  (when-let [flash (:flash request)]
                    [:p {:role "status"} flash])
                  content]]])))

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
                (email-field {:value email :problem-key problem-key})
                (new-password-field {:id "password" :label "Password" :problem-key problem-key})
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
                (email-field {})
                [:button "Send link"])))

(defn log-in
  [request {:keys [email problem-key]}]
  (layout request "Log in"
          (problem problem-key)
          (form request "/login"
                (email-field {:value email})
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
                (email-field {})
                [:button "Send link"])))

(defn reset-password
  [request {:keys [token problem-key]}]
  (layout request "Choose a new password"
          (problem problem-key)
          (form request "/password/reset"
                [:input {:type "hidden" :name "token" :value token}]
                (new-password-field {:id "password" :label "New password" :problem-key problem-key})
                show-password
                [:button "Save password"])))

(defn change-password
  [request {:keys [problem-key]}]
  (layout request "Change password"
          (problem problem-key)
          (form request "/password/change"
                (password-field {:id "current-password" :label "Current password" :autocomplete "current-password"})
                (new-password-field {:id "new-password" :label "New password" :problem-key problem-key})
                show-password
                [:button "Change password"])
          [:p "You'll stay logged in here, and be logged out everywhere else."]))
