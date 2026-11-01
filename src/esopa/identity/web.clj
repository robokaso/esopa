(ns esopa.identity.web
  "Routes and handlers for registering, logging in and managing passwords. Handlers
  read the form, call esopa.identity.service and turn its outcome into a page or
  a redirect."
  (:require [esopa.identity.service :as service]
            [esopa.identity.views :as views]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.interceptor :as interceptor]))

(defn- page
  [status html]
  {:status  status
   :headers {"Content-Type" "text/html;charset=UTF-8"}
   :body    html})

(defn- redirect
  [location & {:as response}]
  (merge {:status 303 :headers {"Location" location}} response))

(defn- param
  "The form or query parameter `k` as a string. Missing or repeated parameters
  become the empty string, so they fail validation like any other wrong value."
  [request k]
  (let [value (or (get-in request [:form-params k]) (get-in request [:query-params k]))]
    (if (string? value) value "")))

(defn- deps
  [request]
  (:identity request))

(defn identity-interceptor
  "Puts the identity use cases' `deps` on the request as :identity, and the
  logged-in account, if any, as :account."
  [deps]
  (interceptor/interceptor
    {:name  ::identity
     :enter (fn [context]
              (update context :request
                      #(assoc % :identity deps :account (service/session-account deps (:session %)))))}))

(def require-login
  "Sends visitors who aren't logged in to the login page."
  (interceptor/interceptor
    {:name  ::require-login
     :enter (fn [{:keys [request] :as context}]
              (if (:account request)
                context
                (assoc context :response (redirect "/login" :flash "Log in first."))))}))

(defn- log-in-session
  "The request's session with the login `session` data added."
  [request session]
  (merge (:session request) session))

(defn home
  [request]
  (page 200 (views/home request)))

(defn register-form
  [request]
  (page 200 (views/register request {})))

(defn register
  [request]
  (let [email   (param request :email)
        outcome (service/register! (deps request) {:email email :password (param request :password)})]
    (if (= :registered (:outcome outcome))
      (redirect "/check-email")
      (page 422 (views/register request {:email email :problem-key (:problem outcome)})))))

(defn check-email
  [request]
  (page 200 (views/check-email request)))

(defn confirm-form
  [request]
  (page 200 (views/confirm request (param request :token))))

(defn confirm
  [request]
  (case (:outcome (service/confirm! (deps request) (param request :token)))
    :confirmed    (redirect "/login" :flash "Your email address is confirmed. You can log in now.")
    :invalid-link (page 400 (views/invalid-link request))))

(defn resend-confirmation-form
  [request]
  (page 200 (views/resend-confirmation request)))

(defn resend-confirmation
  [request]
  (service/resend-confirmation! (deps request) (param request :email))
  (redirect "/check-email"))

(defn log-in-form
  [request]
  (page 200 (views/log-in request {})))

(defn log-in
  [request]
  (let [email   (param request :email)
        outcome (service/log-in (deps request) {:email     email
                                                :password  (param request :password)
                                                :client-ip (:client-ip request)})]
    (case (:outcome outcome)
      :logged-in           (-> (redirect "/" :session (log-in-session request (:session outcome)))
                               (csrf/rotate-token))
      :unconfirmed         (page 403 (views/unconfirmed request))
      :throttled           (page 429 (views/throttled request))
      :invalid-credentials (page 422 (views/log-in request {:email email :problem-key :credentials/invalid})))))

(defn log-out
  [_request]
  (redirect "/" :session nil))

(defn log-out-everywhere
  [request]
  (service/log-out-everywhere! (deps request) (:account request))
  (redirect "/login" :session nil :flash "You've been logged out on every device."))

(defn forgot-password-form
  [request]
  (page 200 (views/forgot-password request)))

(defn forgot-password
  [request]
  (service/request-password-reset! (deps request) (param request :email))
  (redirect "/check-email"))

(defn reset-password-form
  [request]
  (let [token (param request :token)]
    (if (service/reset-link-valid? (deps request) token)
      (page 200 (views/reset-password request {:token token}))
      (page 400 (views/invalid-link request)))))

(defn reset-password
  [request]
  (let [token   (param request :token)
        outcome (service/reset-password! (deps request) {:token token :password (param request :password)})]
    (case (:outcome outcome)
      :password-reset (redirect "/login" :session nil :flash "Your password has been changed. Log in with your new password.")
      :invalid        (page 422 (views/reset-password request {:token token :problem-key (:problem outcome)}))
      :invalid-link   (page 400 (views/invalid-link request)))))

(defn change-password-form
  [request]
  (page 200 (views/change-password request {})))

(defn change-password
  [request]
  (let [outcome (service/change-password! (deps request) (:account request)
                                          {:current-password (param request :current-password)
                                           :new-password     (param request :new-password)})]
    (case (:outcome outcome)
      :password-changed    (redirect "/" :session (log-in-session request (:session outcome))
                                     :flash "Your password has been changed.")
      :invalid             (page 422 (views/change-password request {:problem-key (:problem outcome)}))
      :invalid-credentials (page 422 (views/change-password request {:problem-key :credentials/invalid})))))

(def routes
  #{["/" :get home :route-name ::home]
    ["/register" :get register-form :route-name ::register-form]
    ["/register" :post register :route-name ::register]
    ["/check-email" :get check-email :route-name ::check-email]
    ["/confirm" :get confirm-form :route-name ::confirm-form]
    ["/confirm" :post confirm :route-name ::confirm]
    ["/confirm/resend" :get resend-confirmation-form :route-name ::resend-confirmation-form]
    ["/confirm/resend" :post resend-confirmation :route-name ::resend-confirmation]
    ["/login" :get log-in-form :route-name ::log-in-form]
    ["/login" :post log-in :route-name ::log-in]
    ["/logout" :post log-out :route-name ::log-out]
    ["/logout-everywhere" :post [require-login log-out-everywhere] :route-name ::log-out-everywhere]
    ["/password/forgot" :get forgot-password-form :route-name ::forgot-password-form]
    ["/password/forgot" :post forgot-password :route-name ::forgot-password]
    ["/password/reset" :get reset-password-form :route-name ::reset-password-form]
    ["/password/reset" :post reset-password :route-name ::reset-password]
    ["/password/change" :get [require-login change-password-form] :route-name ::change-password-form]
    ["/password/change" :post [require-login change-password] :route-name ::change-password]})
