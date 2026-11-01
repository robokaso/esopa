(ns esopa.server-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [esopa.identity.service-test :as service-test]
            [esopa.server :as server]
            [io.pedestal.connector.test :as test]))

(use-fixtures :once test/disable-routing-table-output-fixture)

(defn test-connector
  "A connector backed by a fresh in-memory database; see service-test/test-deps."
  ([]
   (test-connector (service-test/test-deps)))
  ([deps]
   (server/create-connector {:host           "localhost"
                             :port           0
                             :session-key    (byte-array (range 16))
                             :cookie-secure? true
                             :trusted-proxies 0
                             :identity-deps  deps})))

(deftest health-test
  (let [response (test/response-for (test-connector) :get "/health")]
    (is (= 200 (:status response)))
    (is (= "{\"status\":\"ok\"}" (:body response)))))

(deftest assets-test
  (testing "the stylesheet and script are served"
    (let [connector (test-connector)]
      (is (= 200 (:status (test/response-for connector :get "/assets/site.css"))))
      (is (= 200 (:status (test/response-for connector :get "/assets/show-password.js"))))
      (is (= 404 (:status (test/response-for connector :get "/assets/missing.js")))))))

(deftest security-headers-test
  (let [headers (:headers (test/response-for (test-connector) :get "/"))]
    (testing "only esopa's own scripts, styles and form targets"
      (is (str/includes? (get headers "Content-Security-Policy") "default-src 'self'"))
      (is (str/includes? (get headers "Content-Security-Policy") "form-action 'self'")))
    (testing "the session cookie can't be read by scripts and stays on esopa's own pages"
      (let [cookie (str/join "\n" (get headers "Set-Cookie"))]
        (is (str/includes? cookie "esopa-session="))
        (is (str/includes? cookie "HttpOnly"))
        (is (str/includes? cookie "Secure"))
        (is (str/includes? cookie "SameSite=Lax"))))))

(deftest client-ip-test
  (let [request {:remote-addr "10.0.0.2"
                 :headers     {"x-forwarded-for" "203.0.113.9, 192.0.2.1"}}]
    (testing "without proxies, the connection's own address"
      (is (= "10.0.0.2" (server/client-ip request 0))))
    (testing "behind proxies, the address the outermost trusted proxy saw"
      (is (= "192.0.2.1" (server/client-ip request 1)))
      (is (= "203.0.113.9" (server/client-ip request 2))))
    (testing "entries a client adds itself can't move it further than the trusted proxies allow"
      (is (= "192.0.2.1" (server/client-ip (update-in request [:headers "x-forwarded-for"] #(str "1.1.1.1, " %)) 1))))
    (testing "with fewer hops than configured, the leftmost address"
      (is (= "203.0.113.9" (server/client-ip request 5)))
      (is (= "10.0.0.2" (server/client-ip {:remote-addr "10.0.0.2" :headers {}} 1))))))
