(ns esopa.security.token
  "Tamper-proof tokens for the links in emails: EDN data signed with HMAC-SHA256
  as a compact JWS, using buddy-sign. The data is signed, not encrypted, so it
  must not hold secrets."
  (:require [buddy.sign.jws :as jws]
            [clojure.edn :as edn])
  (:import (clojure.lang ExceptionInfo)
           (java.nio.charset StandardCharsets)))

(defn sign
  "A URL-safe token carrying `data`, signed with `key`, a byte array of at least
  32 bytes."
  [key data]
  (let [payload (binding [*print-length* nil
                          *print-level*  nil]
                  (pr-str data))]
    (jws/sign payload key {:alg :hs256})))

(defn unsign
  "The data in `token` when it was signed with `key`, otherwise nil."
  [key token]
  (when (string? token)
    (try
      (edn/read-string (String. ^bytes (jws/unsign token key {:alg :hs256}) StandardCharsets/UTF_8))
      (catch ExceptionInfo _
        nil))))
