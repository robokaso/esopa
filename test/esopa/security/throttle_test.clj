(ns esopa.security.throttle-test
  (:require [clojure.test :refer [deftest is testing]]
            [esopa.security.throttle :as throttle])
  (:import (java.time Duration)))

(def ^:private three-per-minute
  {:limit 3 :window (Duration/ofMinutes 1)})

(defn- at
  [seconds]
  (java.util.Date. (* seconds 1000)))

(defn- counted
  "Counters after an event for each of `keys` at `seconds`."
  [counters seconds & keys]
  (reduce #(throttle/count-event %1 three-per-minute %2 (at seconds)) counters keys))

(deftest allowed-test
  (let [counters (counted {} 0 :ada :ada)]
    (is (throttle/allowed? {} three-per-minute :ada (at 0)))
    (is (throttle/allowed? counters three-per-minute :ada (at 1)))
    (testing "refused once the limit is reached within the window"
      (is (not (throttle/allowed? (counted counters 30 :ada) three-per-minute :ada (at 59)))))
    (testing "keys are counted separately"
      (is (throttle/allowed? (counted counters 30 :ada) three-per-minute :eve (at 59))))
    (testing "allowed again when the window, which starts at the first event, ends"
      (is (throttle/allowed? (counted counters 30 :ada) three-per-minute :ada (at 60))))
    (testing "counting starts over in a new window"
      (is (throttle/allowed? (counted counters 60 :ada :ada) three-per-minute :ada (at 61)))
      (is (not (throttle/allowed? (counted counters 60 :ada :ada :ada) three-per-minute :ada (at 61)))))))

(deftest clear-test
  (let [counters (counted {} 0 :ada :ada :ada :eve)]
    (is (throttle/allowed? (throttle/clear counters :ada) three-per-minute :ada (at 1)))
    (is (= (dissoc counters :ada) (throttle/clear counters :ada)))))

(deftest prune-test
  (testing "expired counters are dropped once there are many, so memory stays bounded"
    (with-redefs [throttle/max-keys 2]
      (let [counters (counted {} 0 :ada :eve)]
        (is (= #{:bob}
               (set (keys (counted counters 60 :bob)))))
        (is (= #{:ada :eve :bob}
               (set (keys (counted counters 30 :bob)))))))))
