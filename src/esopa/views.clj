(ns esopa.views
  "The site's layout and forms, and the pages for errors, rendered with Hiccup, which
  escapes text by default. Pico styles the semantic HTML, and htmx boosts links and
  forms (see layout)."
  (:require [esopa.domain.access.roles :as roles]
            [esopa.webjars :as webjars]
            [hiccup2.core :as h]
            [io.pedestal.http.csrf :as csrf]))

(def ^:private stylesheets
  [(webjars/path "picocss__pico" "css/pico.min.css")
   "/assets/site.css"])

(def ^:private scripts
  [(webjars/path "htmx.org" "dist/htmx.min.js")
   "/assets/show-password.js"
   "/assets/show-busy.js"
   "/assets/show-unreachable.js"
   "/assets/move-focus.js"])

(defn form
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
            (when (roles/permitted? (:roles request) :access/assign-roles)
              [:li [:a {:href "/admin/accounts"} "Accounts"]])
            [:li [:a {:href "/password/change"} "Change password"]]
            [:li (form request "/logout" [:button "Log out"])])
      (list [:li [:a {:href "/login"} "Log in"]]
            [:li [:a {:href "/register"} "Register"]]))]])

(defn layout
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

(defn problem
  "The `message` about a form's problem, which screen readers announce and which takes
  focus, or nil without one."
  [message]
  (when message
    [:p#problem {:role "alert" :tabindex "-1" :autofocus true} message]))

(def ^:private home-link
  [:p [:a {:href "/"} "Go to the home page"]])

(defn not-found
  "The page for an address esopa has no page at."
  [request]
  (layout request "Page not found"
          [:p "There's no page at this address. If you typed it, check it for mistakes."]
          home-link))

(defn form-expired
  "The page for a form posted without a valid anti-forgery token. Its token stops
  working when the visitor logs in or out in another tab or the session ends, and it
  never works when the browser doesn't keep esopa's cookie."
  [request]
  (layout request "This form has expired"
          [:p "For your security, a form stops working when you log in or out in another tab, "
           "or after a long time. Go back, reload the page and try again."]
          [:p "If this keeps happening, check that your browser accepts cookies from esopa."]
          home-link))

(defn server-error
  "The page for a request that failed. It doesn't say why: that's in the log."
  [request]
  (layout request "Something went wrong"
          [:p "esopa ran into a problem and couldn't finish what you asked. "
           "The problem has been logged. Try again in a moment."]
          home-link))

(defn forbidden
  "The page for an account whose roles don't grant what a page needs."
  [request]
  (layout request "You don't have access"
          [:p "Your account doesn't have permission to use this page. If you need it, ask an admin."]
          home-link))
