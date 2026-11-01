(ns esopa.system-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [esopa.system :as system]))

(deftest config-test
  (testing "defaults suit local development"
    (is (= {:host        "localhost"
            :port        8080
            :storage-dir (.getAbsolutePath (io/file "data/datomic"))
            :db-name     "esopa"}
           (system/config {}))))

  (testing "environment variables override the defaults"
    (is (= {:host        "0.0.0.0"
            :port        9090
            :storage-dir "/var/lib/esopa"
            :db-name     "prod"}
           (system/config {"HOST"                "0.0.0.0"
                           "PORT"                "9090"
                           "DATOMIC_STORAGE_DIR" "/var/lib/esopa"
                           "DATOMIC_DB_NAME"     "prod"})))))
