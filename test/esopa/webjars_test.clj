(ns esopa.webjars-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [esopa.webjars :as webjars]))

(deftest path-test
  (testing "the path includes the WebJar's version, so a new version gets a new URL"
    (let [path (webjars/path "htmx.org" "dist/htmx.min.js")]
      (is (re-matches #"/webjars/htmx\.org/\d+\.\d+\.\d+[^/]*/dist/htmx\.min\.js" path))
      (is (io/resource (str "META-INF/resources" path)))))
  (testing "a file missing from its WebJar, or a WebJar missing from the class path, fails at once"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not on the class path"
                          (webjars/path "htmx.org" "dist/missing.js")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not on the class path"
                          (webjars/path "missing" "missing.js")))))
