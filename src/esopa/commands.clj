(ns esopa.commands
  "Commands run with esopa's jar while esopa itself is stopped: Datomic Local serves one
  process at a time. Each takes a Datomic connection and the command's arguments, and
  returns the process's :exit status and a :message to print."
  (:require [clojure.string :as str]
            [datomic.client.api :as d]
            [esopa.access.service :as access]
            [esopa.domain.access.roles :as roles]
            [esopa.domain.identity.account :as account]
            [esopa.identity.db :as identity-db]))

(def ^:private role-names
  (str/join ", " (sort (map name (keys roles/roles)))))

(def usage
  (str "Usage: java -jar esopa.jar grant-role EMAIL ROLE\n"
       "Gives the account with the email address EMAIL the role ROLE: one of " role-names "."))

(defn grant-role
  "Gives the account with the email address `email` the role named `role-name`, like
  admin. This is how the first admin is made."
  [conn [email role-name :as args]]
  (if (not= 2 (count args))
    {:exit 2 :message usage}
    (if-let [found (identity-db/account-by-email (d/db conn) (account/normalize-email email))]
      (if (= :granted (:outcome (access/grant-role! {:conn conn} {:account-id (:account/id found)
                                                                  :role       (keyword "role" role-name)})))
        {:exit 0 :message (str "Granted the role " role-name " to " (:account/email found) ".")}
        {:exit 1 :message (str "There's no role " role-name ". Roles: " role-names ".")})
      {:exit 1 :message (str "No account has the email address " email ".")})))
