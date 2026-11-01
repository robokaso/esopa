(ns esopa.access.web-test
  "The pages for roles, driven like a browser (see esopa.http-browser)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datomic.client.api :as d]
            [esopa.access.service :as access]
            [esopa.http-browser :refer [browser logged-in! registered! submit! visit!]]
            [esopa.identity.db :as identity-db]
            [io.pedestal.connector.test :as test]))

(use-fixtures :once test/disable-routing-table-output-fixture)

(defn- id-of
  [b email]
  (:account/id (identity-db/account-by-email (d/db (:conn (:deps @b))) email)))

(defn- roles-of
  [b email]
  (access/roles-of {:conn (:conn (:deps @b))} (id-of b email)))

(defn- admin!
  "Makes the account with `email` an admin, as the grant-role command does."
  [b email]
  (access/grant-role! {:conn (:conn (:deps @b))} {:account-id (id-of b email) :role :role/admin}))

(defn- admin-and-member
  "A browser logged in as ada, an admin, on an esopa where eve is a member."
  []
  (let [b   (browser)
        eve (browser b)]
    (registered! b "ada@example.com")
    (registered! eve "eve@example.com")
    (admin! b "ada@example.com")
    (logged-in! b "ada@example.com" "correct horse")
    b))

(deftest registering-makes-a-member-test
  (let [b (browser)]
    (registered! b)
    (is (= #{:role/member} (roles-of b "ada@example.com")))))

(deftest accounts-page-test
  (let [b    (admin-and-member)
        page (:body (visit! b "/admin/accounts"))]
    (is (str/includes? page "<h1 tabindex=\"-1\">Accounts</h1>"))
    (testing "lists every account with its roles, as checkboxes"
      (is (str/includes? page "<legend>ada@example.com</legend>"))
      (is (str/includes? page "<legend>eve@example.com</legend>"))
      (is (str/includes? page (str "<input name=\"account\" type=\"hidden\" value=\"" (id-of b "eve@example.com") "\">")))
      (is (str/includes? page "<label><input checked name=\"roles\" type=\"checkbox\" value=\"admin\">Admin</label>"))
      (is (str/includes? page "<label><input name=\"roles\" type=\"checkbox\" value=\"admin\">Admin</label>")))
    (testing "each button says whose roles it saves"
      (is (str/includes? page "<button aria-label=\"Save roles for eve@example.com\">Save roles</button>")))
    (testing "the navigation leads there"
      (is (str/includes? page "<a href=\"/admin/accounts\">Accounts</a>")))))

(deftest assign-roles-test
  (let [b   (admin-and-member)
        eve (id-of b "eve@example.com")]
    (visit! b "/admin/accounts")
    (let [response (submit! b "/admin/accounts/roles" {:account eve :roles ["admin" "member"]})]
      (is (= ["/admin/accounts"] (:redirects response)))
      (is (str/includes? (:body response) "Roles saved."))
      (is (= #{:role/admin :role/member} (roles-of b "eve@example.com"))))
    (testing "an account can have no roles at all"
      (submit! b "/admin/accounts/roles" {:account eve})
      (is (= #{} (roles-of b "eve@example.com"))))))

(deftest assign-roles-problems-test
  (let [b   (admin-and-member)
        ada (id-of b "ada@example.com")]
    (visit! b "/admin/accounts")
    (testing "admins can't take away their own permission to change roles"
      (let [response (submit! b "/admin/accounts/roles" {:account ada :roles ["member"]})]
        (is (= 422 (:status response)))
        (is (str/includes? (:body response) "role=\"alert\""))
        (is (str/includes? (:body response) "You can&apos;t take away your own permission to change roles."))
        (is (= #{:role/admin :role/member} (roles-of b "ada@example.com")))))
    (testing "only roles from the list can be given"
      (let [response (submit! b "/admin/accounts/roles" {:account ada :roles ["admin" "owner"]})]
        (is (= 422 (:status response)))
        (is (str/includes? (:body response) "Choose only roles from the list."))))
    (testing "an account that doesn't exist has no page"
      (is (= 404 (:status (submit! b "/admin/accounts/roles" {:account (random-uuid) :roles ["member"]}))))
      (is (= 404 (:status (submit! b "/admin/accounts/roles" {:account "garbage" :roles ["member"]})))))))

(deftest members-test
  (let [b (browser)]
    (registered! b "eve@example.com")
    (logged-in! b "eve@example.com" "correct horse")
    (testing "a member doesn't have access to accounts and roles"
      (let [response (visit! b "/admin/accounts")]
        (is (= 403 (:status response)))
        (is (str/includes? (:body response) "You don&apos;t have access")))
      (is (= 403 (:status (submit! b "/admin/accounts/roles" {:account (id-of b "eve@example.com")
                                                             :roles   ["admin"]}))))
      (is (= #{:role/member} (roles-of b "eve@example.com"))))
    (testing "and isn't shown the way there"
      (is (not (str/includes? (:body (visit! b "/")) "/admin/accounts"))))))

(deftest visitors-test
  (testing "a visitor who isn't logged in is sent to log in first"
    (let [b (browser)]
      (is (= ["/login"] (:redirects (visit! b "/admin/accounts"))))
      (visit! b "/login")
      (is (= ["/login"] (:redirects (submit! b "/admin/accounts/roles" {:account (random-uuid) :roles ["admin"]})))))))
