(ns esopa.commands-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [esopa.access.service :as access]
            [esopa.commands :as commands]
            [esopa.identity.db :as identity-db]
            [esopa.identity.service :as identity]
            [esopa.identity.service-test :as identity-test]))

(defn- with-ada
  "The connection to a fresh database with an account for ada@example.com, and its id."
  []
  (let [deps (identity-test/test-deps)]
    (identity/register! deps {:email "ada@example.com" :password "correct horse"})
    [(:conn deps) (:account/id (identity-db/account-by-email (d/db (:conn deps)) "ada@example.com"))]))

(deftest grant-role-test
  (let [[conn ada] (with-ada)]
    (is (= {:exit 0 :message "Granted the role admin to ada@example.com."}
           (commands/grant-role conn [" Ada@Example.com " "admin"])))
    (is (= #{:role/admin} (access/roles-of {:conn conn} ada)))))

(deftest grant-role-problems-test
  (let [[conn ada] (with-ada)]
    (testing "an address without an account"
      (is (= {:exit 1 :message "No account has the email address eve@example.com."}
             (commands/grant-role conn ["eve@example.com" "admin"]))))
    (testing "a role that doesn't exist"
      (let [{:keys [exit message]} (commands/grant-role conn ["ada@example.com" "owner"])]
        (is (= 1 exit))
        (is (str/starts-with? message "There's no role owner. Roles: "))
        (is (str/includes? message "admin"))))
    (testing "the wrong arguments"
      (doseq [args [[] ["ada@example.com"] ["ada@example.com" "admin" "member"]]]
        (is (= {:exit 2 :message commands/usage} (commands/grant-role conn args)))))
    (is (= #{} (access/roles-of {:conn conn} ada)) "nothing was granted")))
