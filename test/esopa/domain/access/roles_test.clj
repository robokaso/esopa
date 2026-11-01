(ns esopa.domain.access.roles-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [esopa.domain.access.roles :as roles]
            [malli.core :as m]
            [malli.generator :as mg]))

(def ^:private ada (random-uuid))

(def ^:private eve (random-uuid))

(deftest definitions-test
  (testing "every role has a name and grants only permissions that exist"
    (doseq [[role {:role/keys [name permissions]}] roles/roles]
      (is (string? name) (str role))
      (is (every? roles/permissions permissions) (str role))))
  (testing "admins may assign roles, so the grant-role command can always make someone who can"
    (is (roles/permitted? #{:role/admin} :access/assign-roles)))
  (testing "new accounts are members"
    (is (= #{:role/member} roles/new-account-roles))))

(deftest permissions-of-test
  (testing "no roles grant nothing"
    (is (= #{} (roles/permissions-of #{}))))
  (testing "a role grants its own permissions"
    (doseq [[role {:role/keys [permissions]}] roles/roles]
      (is (= permissions (roles/permissions-of #{role})))))
  (testing "roles together grant every permission any of them grants"
    (is (= (into #{} (mapcat :role/permissions) (vals roles/roles))
           (roles/permissions-of (set (keys roles/roles)))))))

(deftest permitted-test
  (is (roles/permitted? #{:role/member :role/admin} :access/assign-roles))
  (is (not (roles/permitted? #{} :access/assign-roles))))

(deftest assign-test
  (let [admin  {:account/id ada :access/roles #{:role/admin}}
        member {:account/id eve :access/roles #{:role/member}}]
    (testing "an account that may assign roles gives another account its new roles"
      (is (= {:outcome :roles-assigned :access/roles #{:role/admin :role/member}}
             (roles/assign admin member #{:role/admin :role/member})))
      (is (= {:outcome :roles-assigned :access/roles #{}}
             (roles/assign admin member #{}))))
    (testing "an account that may not assign roles can't"
      (is (= {:outcome :forbidden} (roles/assign member member #{:role/admin}))))
    (testing "only roles that exist can be given"
      (is (= {:outcome :invalid :problem :roles/unknown}
             (roles/assign admin member #{:role/member :role/owner}))))
    (testing "an account can't take away its own permission to assign roles"
      (is (= {:outcome :invalid :problem :roles/own-permission}
             (roles/assign admin admin #{:role/member})))
      (is (= {:outcome :roles-assigned :access/roles #{:role/admin :role/member}}
             (roles/assign admin admin #{:role/admin :role/member}))))))

(defspec ^:generative assigning-never-takes-away-your-own-permission-to-assign-roles 100
  (prop/for-all [actor-roles (mg/generator roles/Roles)
                 new-roles   (mg/generator roles/Roles)]
    (let [actor   {:account/id ada :access/roles actor-roles}
          outcome (roles/assign actor actor new-roles)]
      (or (not= :roles-assigned (:outcome outcome))
          (roles/permitted? (:access/roles outcome) :access/assign-roles)))))

(deftest grant-test
  (testing "adds a role to an account's roles"
    (is (= {:outcome :granted :access/roles #{:role/member :role/admin}}
           (roles/grant #{:role/member} :role/admin)))
    (is (= {:outcome :granted :access/roles #{:role/admin}}
           (roles/grant #{} :role/admin))))
  (testing "only roles that exist can be granted"
    (is (= {:outcome :invalid :problem :roles/unknown}
           (roles/grant #{:role/member} :role/owner)))))

(deftest schemas-test
  (is (m/validate roles/Roles #{:role/admin :role/member}))
  (is (not (m/validate roles/Roles #{:role/owner})))
  (is (m/validate roles/Permission :access/assign-roles))
  (is (not (m/validate roles/Permission :access/everything))))
