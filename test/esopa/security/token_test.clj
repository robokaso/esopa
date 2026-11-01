(ns esopa.security.token-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [esopa.security.token :as token]))

(def ^:private key-1 (byte-array (range 32)))
(def ^:private key-2 (byte-array (reverse (range 32))))

(def ^:private claims
  {:account/id      #uuid "6a1f0c1e-7d5b-4a43-9a52-0f6b6d3c2a11"
   :link/purpose    :confirm-email
   :link/expires-at #inst "2026-01-01T10:00:00.000-00:00"})

(deftest round-trip-test
  (let [signed (token/sign key-1 claims)]
    (is (= claims (token/unsign key-1 signed)))
    (testing "is safe to put in a URL"
      (is (re-matches #"[A-Za-z0-9_.-]+" signed)))
    (testing "ignores REPL print settings"
      (binding [*print-length* 1 *print-namespace-maps* true]
        (is (= claims (token/unsign key-1 (token/sign key-1 claims))))))))

(deftest rejected-tokens-test
  (let [signed (token/sign key-1 claims)]
    (testing "signed with another key"
      (is (nil? (token/unsign key-2 signed))))
    (testing "tampered with"
      (is (nil? (token/unsign key-1 (str signed "x"))))
      (is (nil? (token/unsign key-1 (str "x" signed)))))
    (testing "malformed or missing"
      (is (nil? (token/unsign key-1 "garbage")))
      (is (nil? (token/unsign key-1 "a.b.c")))
      (is (nil? (token/unsign key-1 "")))
      (is (nil? (token/unsign key-1 nil))))))

(def ^:private instant
  "Instants with four-digit years, which EDN's #inst reader requires."
  (gen/fmap #(java.util.Date. (long %)) (gen/large-integer* {:min 0 :max 253402300799999})))

(defspec ^:generative signed-data-round-trips 100
  (prop/for-all [data (gen/map gen/keyword
                               (gen/one-of [gen/string gen/large-integer gen/uuid gen/boolean instant]))]
    (= data (token/unsign key-1 (token/sign key-1 data)))))
