(ns esopa.server-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [esopa.identity.service-test :as service-test]
            [esopa.server :as server]
            [esopa.system :as system]
            [esopa.web :as web]
            [esopa.webjars :as webjars]
            [io.pedestal.connector.test :as test]
            [io.pedestal.environment :as env]))

(use-fixtures :once test/disable-routing-table-output-fixture)

(defn connector-config
  "The configuration of a connector on the identity use cases' `deps` (see
  service-test/test-deps), connected to the other contexts as esopa.system connects
  them, with `overrides`."
  [deps & {:as overrides}]
  (merge {:host            "localhost"
          :port            0
          :session-key     (byte-array (range 16))
          :cookie-secure?  true
          :trusted-proxies 0}
         (system/context-deps deps)
         overrides))

(defn test-connector
  "A connector backed by a fresh in-memory database; see service-test/test-deps."
  ([]
   (test-connector (service-test/test-deps)))
  ([deps]
   (server/create-connector (connector-config deps))))

(deftest health-test
  (let [response (test/response-for (test-connector) :get "/health")]
    (is (= 200 (:status response)))
    (is (= "{\"status\":\"ok\"}" (:body response)))))

(deftest assets-test
  (testing "the stylesheet and scripts are served"
    (let [connector (test-connector)]
      (is (= 200 (:status (test/response-for connector :get "/assets/site.css"))))
      (is (= 200 (:status (test/response-for connector :get "/assets/show-password.js"))))
      (is (= 200 (:status (test/response-for connector :get "/assets/move-focus.js"))))
      (is (= 200 (:status (test/response-for connector :get "/assets/show-busy.js"))))
      (is (= 200 (:status (test/response-for connector :get "/assets/show-unreachable.js"))))
      (is (= 404 (:status (test/response-for connector :get "/assets/missing.js"))))))
  (testing "paths can't climb out of the assets with .. to reach the rest of the class path"
    (let [connector (test-connector)]
      (is (= 404 (:status (test/response-for connector :get "/assets/../../esopa/system.clj"))))
      (is (= 404 (:status (test/response-for connector :get "/assets/..%2F..%2Fesopa/system.clj")))))))

(deftest dev-mode-assets-test
  (testing "in dev mode, an asset edited while esopa runs is served as it is now"
    (let [file (io/file "resources/public/assets" (str "edited-" (random-uuid) ".txt"))
          url  (str "/assets/" (.getName file))]
      (try
        (spit file "before")
        (with-redefs [env/dev-mode? true]
          (let [connector (test-connector)]
            (is (= "before" (:body (test/response-for connector :get url))))
            (spit file "and after")
            (let [response (test/response-for connector :get url)]
              (is (= "and after" (:body response)))
              (is (= "9" (get-in response [:headers "Content-Length"]))))))
        (finally
          (io/delete-file file true))))))

(deftest webjars-test
  (let [connector (test-connector)]
    (testing "htmx and Pico are served from their WebJars"
      (let [response (test/response-for connector :get (webjars/path "htmx.org" "dist/htmx.min.js"))]
        (is (= 200 (:status response)))
        (is (str/starts-with? (get-in response [:headers "Content-Type"]) "text/javascript")))
      (let [response (test/response-for connector :get (webjars/path "picocss__pico" "css/pico.min.css"))]
        (is (= 200 (:status response)))
        (is (str/starts-with? (get-in response [:headers "Content-Type"]) "text/css"))))
    (testing "nothing else on the class path"
      (is (= 404 (:status (test/response-for connector :get "/webjars/htmx.org/missing.js"))))
      (is (= 404 (:status (test/response-for connector :get "/webjars/../../../esopa/system.clj")))))))

(deftest security-headers-test
  (let [headers (:headers (test/response-for (test-connector) :get "/"))]
    (testing "only esopa's own scripts, styles and form targets"
      (is (str/includes? (get headers "Content-Security-Policy") "default-src 'self'"))
      (is (str/includes? (get headers "Content-Security-Policy") "form-action 'self'")))
    (testing "images may also be data: URIs, which Pico uses for its icons"
      (is (str/includes? (get headers "Content-Security-Policy") "img-src 'self' data:")))
    (testing "the session cookie can't be read by scripts and stays on esopa's own pages"
      (let [cookie (str/join "\n" (get headers "Set-Cookie"))]
        (is (str/includes? cookie "esopa-session="))
        (is (str/includes? cookie "HttpOnly"))
        (is (str/includes? cookie "Secure"))
        (is (str/includes? cookie "SameSite=Lax"))))))

(deftest every-route-says-who-may-use-it-test
  (let [routes (:routes (server/routes))]
    (is (< 10 (count routes)))
    (doseq [{:keys [route-name interceptors]} routes]
      (is (#{::web/public ::web/logged-in ::web/permitted} (:name (first interceptors)))
          (str route-name " starts with esopa.web's public, logged-in or permitted")))))

(deftest not-found-test
  (let [response (test/response-for (test-connector) :get "/no-such-page")]
    (is (= 404 (:status response)))
    (testing "is a page in the site's layout that says what happened and leads on"
      (is (str/starts-with? (get-in response [:headers "Content-Type"]) "text/html"))
      (is (str/includes? (:body response) "<h1 tabindex=\"-1\">Page not found</h1>"))
      (is (str/includes? (:body response) "<a href=\"/\">Go to the home page</a>"))
      (is (str/includes? (:body response) "<nav>")))))

(deftest form-expired-test
  (testing "a form posted without a valid anti-forgery token gets a page that explains"
    (let [response (test/response-for (test-connector) :post "/register"
                                      :headers {:content-type "application/x-www-form-urlencoded"}
                                      :body "email=ada%40example.com&password=correct+horse")]
      (is (= 403 (:status response)))
      (is (str/starts-with? (get-in response [:headers "Content-Type"]) "text/html"))
      (is (str/includes? (:body response) "<h1 tabindex=\"-1\">This form has expired</h1>"))
      (is (str/includes? (:body response) "<a href=\"/\">Go to the home page</a>")))))

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
