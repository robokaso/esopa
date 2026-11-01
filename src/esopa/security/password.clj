(ns esopa.security.password
  "Password hashing with argon2id, using buddy-hashers."
  (:require [buddy.hashers :as hashers])
  (:import (java.text Normalizer Normalizer$Form)))

(def default-options
  "argon2id with 64 MiB of memory and 2 iterations, above OWASP's minimum of
  19 MiB and 2 iterations."
  {:alg :argon2id :memory 65536 :iterations 2})

(defn- normalize
  "The same text typed with composed or decomposed characters becomes the same
  string (Unicode NFKC, as NIST recommends)."
  [password]
  (Normalizer/normalize password Normalizer$Form/NFKC))

(defn hash-password
  "Hashes `password` with argon2id `options` (see default-options). The hash
  records the salt and settings it was made with."
  [options password]
  (hashers/derive (normalize password) options))

(defn verify-password
  "True when `password` matches `password-hash`. Only argon2id hashes are accepted.
  Without a hash, because there is no account, it hashes the password anyway, so
  the response takes as long and doesn't reveal that the account doesn't exist."
  [options password-hash password]
  (if password-hash
    (:valid (hashers/verify (normalize password) password-hash {:limit #{:argon2id}}))
    (do (hash-password options password)
        false)))
