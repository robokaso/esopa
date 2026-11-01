(ns esopa.security.password-test
  (:require [buddy.hashers :as hashers]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [esopa.security.password :as password]))

(def cheap
  "Hashing settings that keep tests fast. Never use them outside tests."
  {:alg :argon2id :memory 8 :iterations 1})

(deftest default-options-test
  (testing "argon2id with at least OWASP's minimum memory and iterations"
    (is (= :argon2id (:alg password/default-options)))
    (is (<= 19456 (:memory password/default-options)))
    (is (<= 2 (:iterations password/default-options)))))

(deftest hash-and-verify-test
  (let [hash (password/hash-password cheap "correct horse battery")]
    (is (str/starts-with? hash "argon2id$"))
    (is (not (str/includes? hash "correct horse")))
    (is (password/verify-password cheap hash "correct horse battery"))
    (is (not (password/verify-password cheap hash "correct horse battery!")))
    (testing "each hash has its own salt"
      (is (not= hash (password/hash-password cheap "correct horse battery"))))))

(deftest unicode-normalization-test
  (testing "the same text typed with composed or decomposed characters matches"
    (let [composed   "café au lait"
          decomposed "café au lait"]
      (is (password/verify-password cheap (password/hash-password cheap composed) decomposed)))))

(deftest only-argon2id-hashes-are-accepted-test
  (is (not (password/verify-password cheap
                                     (hashers/derive "correct horse battery" {:alg :bcrypt+sha512})
                                     "correct horse battery"))))

(deftest no-account-test
  (testing "without a hash nothing matches, and the password is hashed anyway"
    (let [hashed (atom nil)]
      (with-redefs [password/hash-password (fn [options pw] (reset! hashed [options pw]) "hash")]
        (is (false? (password/verify-password cheap nil "correct horse battery"))))
      (is (= [cheap "correct horse battery"] @hashed)))))
