(ns esopa.identity.emails
  "The emails the identity context sends, as maps of :to, :subject and :body."
  (:require [esopa.domain.identity.account :as account]))

(defn- lifetime
  [purpose]
  (let [hours (.toHours ^java.time.Duration (account/link-lifetimes purpose))]
    (str hours (if (= 1 hours) " hour" " hours"))))

(defn confirmation
  [{:keys! [to link]}]
  {:to      to
   :subject "Confirm your email address for esopa"
   :body    (str "Welcome to esopa.\n\n"
                 "To confirm your email address, open this link within " (lifetime :confirm-email) ":\n\n"
                 link "\n\n"
                 "If you didn't register, you can ignore this email.\n")})

(defn already-registered
  [{:keys! [to login-link forgot-password-link]}]
  {:to      to
   :subject "You already have an esopa account"
   :body    (str "Someone, probably you, tried to register with this email address, "
                 "but it already has an account.\n\n"
                 "Log in: " login-link "\n"
                 "Forgot your password? " forgot-password-link "\n\n"
                 "If it wasn't you, you can ignore this email. Nothing has changed.\n")})

(defn password-reset
  [{:keys! [to link]}]
  {:to      to
   :subject "Reset your esopa password"
   :body    (str "To choose a new password, open this link within " (lifetime :reset-password) ":\n\n"
                 link "\n\n"
                 "If you didn't ask to reset your password, you can ignore this email. "
                 "Your password stays the same.\n")})
