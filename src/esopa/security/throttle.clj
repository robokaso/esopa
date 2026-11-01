(ns esopa.security.throttle
  "Fixed-window event counters, for limiting how often something can happen per
  key, such as failed logins per email address. A limit is a map of :limit, the
  number of events allowed, and :window, a java.time.Duration that starts with
  the first event. Counters are plain maps; callers keep them in memory, so they
  start over when the server restarts."
  (:import (java.time Duration)))

(def max-keys
  "Above this many keys, expired counters are dropped when an event is counted."
  100000)

(defn- window-ms
  [limit]
  (.toMillis ^Duration (:window limit)))

(defn- expired?
  [limit counter now]
  (<= (+ (:start counter) (window-ms limit)) (inst-ms now)))

(defn- current
  "The counter for `key` in the current window, starting a new one when needed."
  [counters limit key now]
  (let [counter (get counters key)]
    (if (and counter (not (expired? limit counter now)))
      counter
      {:start (inst-ms now) :count 0})))

(defn allowed?
  "True when fewer than the limit's events were counted for `key` in its current window."
  [counters limit key now]
  (< (:count (current counters limit key now)) (:limit limit)))

(defn- prune
  [counters limit now]
  (into {} (remove #(expired? limit (val %) now)) counters))

(defn count-event
  "The counters with one more event for `key`."
  [counters limit key now]
  (let [counters (if (< (count counters) max-keys) counters (prune counters limit now))]
    (assoc counters key (update (current counters limit key now) :count inc))))

(defn clear
  "The counters without `key`."
  [counters key]
  (dissoc counters key))
