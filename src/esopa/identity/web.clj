(ns esopa.identity.web
  "Routes and handlers for registering, logging in and managing passwords. Handlers
  read the form, call esopa.identity.service and turn its outcome into a page or
  a redirect."
  (:require [esopa.identity.service :as service]
            [esopa.identity.views :as views]
            [esopa.web :as web]
            [io.pedestal.http.csrf :as csrf]
            [io.pedestal.interceptor :as interceptor]))

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

(defn- log-in-session
  "The request's session with the login `session` data added."
  [request session]
  (merge (:session request) session))

(defn home
  [request]
  (web/html 200 (views/home request)))

(defn register-form
  [request]
  (web/html 200 (views/register request {})))

(defn register
  [request]
  (let [email   (web/param request :email)
        outcome (service/register! (deps request) {:email email :password (web/param request :password)})]
    (if (= :registered (:outcome outcome))
      (web/redirect "/check-email")
      (web/html 422 (views/register request {:email email :problem-key (:problem outcome)})))))

(defn check-email
  [request]
  (web/html 200 (views/check-email request)))

(defn confirm-form
  [request]
  (web/html 200 (views/confirm request (web/param request :token))))

(defn confirm
  [request]
  (case (:outcome (service/confirm! (deps request) (web/param request :token)))
    :confirmed    (web/redirect "/login" :flash "Your email address is confirmed. You can log in now.")
    :invalid-link (web/html 400 (views/invalid-link request))))

(defn resend-confirmation-form
  [request]
  (web/html 200 (views/resend-confirmation request)))

(defn resend-confirmation
  [request]
  (service/resend-confirmation! (deps request) (web/param request :email))
  (web/redirect "/check-email"))

(defn log-in-form
  [request]
  (web/html 200 (views/log-in request {})))

(defn log-in
  [request]
  (let [email   (web/param request :email)
        outcome (service/log-in (deps request) {:email     email
                                                :password  (web/param request :password)
                                                :client-ip (:client-ip request)})]
    (case (:outcome outcome)
      :logged-in           (-> (web/redirect "/" :session (log-in-session request (:session outcome)))
                               (csrf/rotate-token))
      :unconfirmed         (web/html 403 (views/unconfirmed request))
      :throttled           (web/html 429 (views/throttled request))
      :invalid-credentials (web/html 422 (views/log-in request {:email email :problem-key :credentials/invalid})))))

(defn log-out
  [_request]
  (web/redirect "/" :session nil))

(defn log-out-everywhere
  [request]
  (service/log-out-everywhere! (deps request) (:account request))
  (web/redirect "/login" :session nil :flash "You've been logged out on every device."))

(defn forgot-password-form
  [request]
  (web/html 200 (views/forgot-password request)))

(defn forgot-password
  [request]
  (service/request-password-reset! (deps request) (web/param request :email))
  (web/redirect "/check-email"))

(defn reset-password-form
  [request]
  (let [token (web/param request :token)]
    (if (service/reset-link-valid? (deps request) token)
      (web/html 200 (views/reset-password request {:token token}))
      (web/html 400 (views/invalid-link request)))))

(defn reset-password
  [request]
  (let [token   (web/param request :token)
        outcome (service/reset-password! (deps request) {:token token :password (web/param request :password)})]
    (case (:outcome outcome)
      :password-reset (web/redirect "/login" :session nil :flash "Your password has been changed. Log in with your new password.")
      :invalid        (web/html 422 (views/reset-password request {:token token :problem-key (:problem outcome)}))
      :invalid-link   (web/html 400 (views/invalid-link request)))))

(defn change-password-form
  [request]
  (web/html 200 (views/change-password request {})))

(defn change-password
  [request]
  (let [outcome (service/change-password! (deps request) (:account request)
                                          {:current-password (web/param request :current-password)
                                           :new-password     (web/param request :new-password)})]
    (case (:outcome outcome)
      :password-changed    (web/redirect "/" :session (log-in-session request (:session outcome))
                                         :flash "Your password has been changed.")
      :invalid             (web/html 422 (views/change-password request {:problem-key (:problem outcome)}))
      :invalid-credentials (web/html 422 (views/change-password request {:problem-key :credentials/invalid})))))

(def routes
  #{["/" :get [web/public home] :route-name ::home]
    ["/register" :get [web/public register-form] :route-name ::register-form]
    ["/register" :post [web/public register] :route-name ::register]
    ["/check-email" :get [web/public check-email] :route-name ::check-email]
    ["/confirm" :get [web/public confirm-form] :route-name ::confirm-form]
    ["/confirm" :post [web/public confirm] :route-name ::confirm]
    ["/confirm/resend" :get [web/public resend-confirmation-form] :route-name ::resend-confirmation-form]
    ["/confirm/resend" :post [web/public resend-confirmation] :route-name ::resend-confirmation]
    ["/login" :get [web/public log-in-form] :route-name ::log-in-form]
    ["/login" :post [web/public log-in] :route-name ::log-in]
    ["/logout" :post [web/public log-out] :route-name ::log-out]
    ["/logout-everywhere" :post [web/logged-in log-out-everywhere] :route-name ::log-out-everywhere]
    ["/password/forgot" :get [web/public forgot-password-form] :route-name ::forgot-password-form]
    ["/password/forgot" :post [web/public forgot-password] :route-name ::forgot-password]
    ["/password/reset" :get [web/public reset-password-form] :route-name ::reset-password-form]
    ["/password/reset" :post [web/public reset-password] :route-name ::reset-password]
    ["/password/change" :get [web/logged-in change-password-form] :route-name ::change-password-form]
    ["/password/change" :post [web/logged-in change-password] :route-name ::change-password]})
