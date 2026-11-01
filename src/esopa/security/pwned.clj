(ns esopa.security.pwned
  "Checks passwords against Have I Been Pwned's Pwned Passwords with its
  k-anonymity range API: only the first 5 characters of the password's SHA-1 hash
  leave the server. See https://haveibeenpwned.com/API/v3#PwnedPasswords"
  (:require [clojure.string :as str]
            [io.pedestal.log :as log])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpResponse HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.security MessageDigest)
           (java.time Duration)
           (java.util HexFormat)))

(defn sha1-hex
  "The upper-case hexadecimal SHA-1 hash of `password`, as Pwned Passwords uses."
  [^String password]
  (.formatHex (.withUpperCase (HexFormat/of))
              (.digest (MessageDigest/getInstance "SHA-1")
                       (.getBytes password StandardCharsets/UTF_8))))

(defn- listed-line?
  [suffix line]
  (let [[line-suffix n] (str/split (str/trim line) #":")]
    (and (= suffix line-suffix)
         (pos? (parse-long n)))))

(defn listed?
  "True when the range response `body` lists the hash `suffix` (the hash after its
  first 5 characters). Padding entries, with a count of zero, don't count."
  [body suffix]
  (boolean (some #(listed-line? suffix %) (str/split-lines body))))

(defn range-body
  "The body of a range response with `status`. Throws unless the request succeeded."
  [status body]
  (if (= 200 status)
    body
    (throw (ex-info "Unexpected Pwned Passwords response" {:status status}))))

(defn http-fetcher
  "A function of a 5-character hash prefix that returns the range response from
  `base-url`, giving up after `timeout` (a java.time.Duration)."
  [{:keys! [base-url timeout]}]
  (let [client (-> (HttpClient/newBuilder) (.connectTimeout timeout) (.build))]
    (fn [prefix]
      (let [request  (-> (HttpRequest/newBuilder (URI/create (str base-url prefix)))
                         (.timeout timeout)
                         (.header "Add-Padding" "true")
                         (.header "User-Agent" "esopa")
                         (.build))
            response ^HttpResponse (.send client request (HttpResponse$BodyHandlers/ofString))]
        (range-body (.statusCode response) (.body response))))))

(def default-fetcher-options
  {:base-url "https://api.pwnedpasswords.com/range/"
   :timeout  (Duration/ofSeconds 2)})

(defn checker
  "A function of a password that returns true when the password appears in a known
  breach. `fetch` returns the range response for a hash prefix (see http-fetcher).

  Fails open: when the check fails, it logs a warning and returns false, so
  registration doesn't depend on the service."
  [fetch]
  (fn [password]
    (let [hash (sha1-hex password)]
      (try
        (listed? (fetch (subs hash 0 5)) (subs hash 5))
        (catch Exception e
          (log/warn :msg "Pwned Passwords check failed; accepting the password" :exception e)
          false)))))
