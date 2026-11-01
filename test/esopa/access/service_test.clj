(ns esopa.access.service-test
  (:require [clojure.test :refer [deftest is testing]]
            [datomic.client.api :as d]
            [esopa.access.service :as access]
            [esopa.db.transaction-test :as transaction-test]
            [esopa.identity.service :as identity]
            [esopa.identity.service-test :as identity-test]))

(defn access-deps
  "Dependencies for the access use cases, on the same database as the identity use
  cases' `identity-deps` (see identity-test/test-deps), which register the accounts."
  [identity-deps]
  {:conn     (:conn identity-deps)
   :accounts #(identity/accounts identity-deps)})

(defn- account!
  "Registers an account with `email`, and returns its :account/id."
  [identity-deps email]
  (identity/register! identity-deps {:email email :password "correct horse"})
  (last @(::identity-test/registered identity-deps)))

(deftest account-registered-test
  (let [identity-deps (identity-test/test-deps)
        deps          (access-deps identity-deps)
        ada           (account! identity-deps "ada@example.com")]
    (access/account-registered! deps ada)
    (testing "a new account gets the roles new accounts get, as its own action"
      (is (= #{:role/member} (access/roles-of deps ada)))
      (is (= ada (transaction-test/actor-of (d/db (:conn deps)) [:account/id ada] :access/roles :role/member))))))

(deftest grant-role-test
  (let [identity-deps (identity-test/test-deps)
        deps          (access-deps identity-deps)
        ada           (account! identity-deps "ada@example.com")]
    (access/account-registered! deps ada)
    (is (= {:outcome :granted :access/roles #{:role/member :role/admin}}
           (access/grant-role! deps {:account-id ada :role :role/admin})))
    (is (= #{:role/member :role/admin} (access/roles-of deps ada)))
    (testing "is nobody's action in particular"
      (is (nil? (transaction-test/actor-of (d/db (:conn deps)) [:account/id ada] :access/roles :role/admin))))
    (testing "only roles that exist can be granted"
      (is (= {:outcome :invalid :problem :roles/unknown}
             (access/grant-role! deps {:account-id ada :role :role/owner})))
      (is (= #{:role/member :role/admin} (access/roles-of deps ada))))))

(defn- admin-and-member
  "Deps, and the ids of ada, an admin, and eve, a member."
  []
  (let [identity-deps (identity-test/test-deps)
        deps          (access-deps identity-deps)
        ada           (account! identity-deps "ada@example.com")
        eve           (account! identity-deps "eve@example.com")]
    (access/account-registered! deps ada)
    (access/account-registered! deps eve)
    (access/grant-role! deps {:account-id ada :role :role/admin})
    [deps ada eve]))

(defn- actor
  [deps id]
  {:account/id id :access/roles (access/roles-of deps id)})

(deftest accounts-test
  (let [[deps ada eve] (admin-and-member)]
    (testing "an admin sees every account with its roles"
      (let [{:keys [outcome accounts]} (access/accounts deps (actor deps ada))]
        (is (= :listed outcome))
        (is (= [["ada@example.com" #{:role/admin :role/member}] ["eve@example.com" #{:role/member}]]
               (map (juxt :account/email :access/roles) accounts)))
        (is (= [ada eve] (map :account/id accounts)))))
    (testing "an account without roles is listed without any"
      (access/assign-roles! deps (actor deps ada) {:account-id eve :roles #{}})
      (is (= #{} (:access/roles (second (:accounts (access/accounts deps (actor deps ada)))))))))
  (testing "a member doesn't"
    (let [[deps _ eve] (admin-and-member)]
      (is (= {:outcome :forbidden} (access/accounts deps (actor deps eve)))))))

(deftest assign-roles-test
  (let [[deps ada eve] (admin-and-member)]
    (testing "an admin changes another account's roles, as the admin's action"
      (is (= {:outcome :roles-assigned :access/roles #{:role/admin}}
             (access/assign-roles! deps (actor deps ada) {:account-id eve :roles #{:role/admin}})))
      (is (= #{:role/admin} (access/roles-of deps eve)))
      (is (= ada (transaction-test/actor-of (d/db (:conn deps)) [:account/id eve] :access/roles :role/admin))))
    (testing "the rules of roles/assign apply"
      (is (= {:outcome :invalid :problem :roles/own-permission}
             (access/assign-roles! deps (actor deps ada) {:account-id ada :roles #{:role/member}})))
      (is (= #{:role/admin :role/member} (access/roles-of deps ada))))
    (testing "an account that doesn't exist can't be given roles"
      (is (= {:outcome :not-found}
             (access/assign-roles! deps (actor deps ada) {:account-id (random-uuid) :roles #{:role/member}})))))
  (testing "a member can't change roles"
    (let [[deps ada eve] (admin-and-member)]
      (is (= {:outcome :forbidden}
             (access/assign-roles! deps (actor deps eve) {:account-id eve :roles #{:role/admin}})))
      (is (= #{:role/member} (access/roles-of deps eve)))
      (is (= #{:role/admin :role/member} (access/roles-of deps ada))))))
