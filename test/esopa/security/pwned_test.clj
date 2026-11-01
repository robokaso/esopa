(ns esopa.security.pwned-test
  (:require [clojure.test :refer [deftest is testing]]
            [esopa.security.pwned :as pwned]))

;; SHA-1 of "password" is 5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8.
(def ^:private range-5baa6
  (str "003D68EB55068C33ACE09247EE4C639306B:3\r\n"
       "1E4C9B93F3F0682250B6CF8331B7EE68FD8:52256179\r\n"
       "011053FD0102E94D6AE2F8B83D76FAF94F6:0\r\n"))

(deftest sha1-hex-test
  (is (= "5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8" (pwned/sha1-hex "password"))))

(deftest listed-test
  (is (pwned/listed? range-5baa6 "1E4C9B93F3F0682250B6CF8331B7EE68FD8"))
  (is (not (pwned/listed? range-5baa6 "1E4C9B93F3F0682250B6CF8331B7EE68FD9")))
  (testing "padding entries, with a count of zero, don't count"
    (is (not (pwned/listed? range-5baa6 "011053FD0102E94D6AE2F8B83D76FAF94F6")))))

(deftest range-body-test
  (is (= range-5baa6 (pwned/range-body 200 range-5baa6)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unexpected Pwned Passwords response"
                        (pwned/range-body 503 "Service Unavailable"))))

(deftest checker-test
  (let [requested (atom nil)
        breached? (pwned/checker (fn [prefix] (reset! requested prefix) range-5baa6))]
    (is (true? (breached? "password")))
    (testing "only the first five characters of the hash leave the server"
      (is (= "5BAA6" @requested)))
    (is (false? (breached? "a password nobody has used")))))

(deftest checker-fails-open-test
  (let [breached? (pwned/checker (fn [_] (throw (java.io.IOException. "timed out"))))]
    (is (false? (breached? "password")))))
