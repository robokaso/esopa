(ns esopa.web-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [esopa.web :as web]))

(def ^:private ada
  {:account/id (random-uuid) :account/email "ada@example.com"})

(defn- enter
  "The response `interceptor` gives `request` on entering, or nil when it lets it through."
  [interceptor request]
  (:response ((:enter interceptor) {:request request})))

(deftest public-test
  (testing "lets everyone through"
    (is (nil? (enter web/public {})))))

(deftest logged-in-test
  (testing "lets logged-in visitors through"
    (is (nil? (enter web/logged-in {:account ada}))))
  (testing "sends others to the login page"
    (let [response (enter web/logged-in {})]
      (is (= 303 (:status response)))
      (is (= "/login" (get-in response [:headers "Location"])))
      (is (= "Log in first." (:flash response))))))

(deftest permitted-test
  (let [interceptor (web/permitted :access/assign-roles)]
    (testing "lets accounts with the permission through"
      (is (nil? (enter interceptor {:account ada :roles #{:role/admin}}))))
    (testing "tells accounts without it that they don't have access"
      (let [response (enter interceptor {:account ada :roles #{:role/member}})]
        (is (= 403 (:status response)))
        (is (str/starts-with? (get-in response [:headers "Content-Type"]) "text/html"))
        (is (str/includes? (:body response) "<h1 tabindex=\"-1\">You don&apos;t have access</h1>"))))
    (testing "sends visitors who aren't logged in to the login page"
      (is (= "/login" (get-in (enter interceptor {}) [:headers "Location"]))))))
