(ns esopa.server-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [esopa.db :as db]
            [esopa.server :as server]
            [io.pedestal.connector.test :as test]
            [io.pedestal.json :as json])
  (:import (java.io StringReader)))

(use-fixtures :once test/disable-routing-table-output-fixture)

(defn- test-connector
  "A connector backed by a fresh in-memory database."
  []
  (server/create-connector {:host "localhost"
                            :port 0
                            :conn (db/connect {:storage-dir :mem
                                               :db-name     (str (random-uuid))})}))

(defn- request
  [connector method url & options]
  (let [response (apply test/response-for connector method url options)]
    (update response :body #(some-> % not-empty StringReader. (json/read-json {:key-fn keyword})))))

(deftest health-test
  (let [response (request (test-connector) :get "/health")]
    (is (= 200 (:status response)))
    (is (= {:status "ok"} (:body response)))))

(deftest notes-test
  (let [connector (test-connector)]
    (testing "starts empty"
      (is (= [] (:body (request connector :get "/api/notes")))))

    (testing "creating a note"
      (let [response (request connector :post "/api/notes"
                              :headers {:content-type "application/json"}
                              :body "{\"text\": \"hello datomic\"}")]
        (is (= 201 (:status response)))
        (is (string? (get-in response [:body :id])))))

    (testing "rejects a note without text"
      (let [response (request connector :post "/api/notes"
                              :headers {:content-type "application/json"}
                              :body "{}")]
        (is (= 400 (:status response)))))

    (testing "lists stored notes"
      (is (= ["hello datomic"]
             (map :text (:body (request connector :get "/api/notes"))))))))
