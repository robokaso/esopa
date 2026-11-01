(ns build
  (:require [clojure.edn :as edn]
            [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/esopa.jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(def max-crap
  "The build fails when any function's CRAP score is above this."
  15)

(defn clean
  [_]
  (b/delete {:path "target"}))

(defn uber
  [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/compile-clj {:basis @basis :ns-compile '[esopa.main] :class-dir class-dir})
  (b/uber {:class-dir class-dir :uber-file uber-file :basis @basis :main 'esopa.main}))

(defn crap-gate
  "Fails when .metrics/crap.edn, written by crap4clj, reports a function above max-crap."
  [_]
  (let [offenders (->> (edn/read-string (slurp ".metrics/crap.edn"))
                       :entries
                       (filter #(some-> (:crap %) (> max-crap))))]
    (when (seq offenders)
      (println (str "CRAP gate failed: functions above " max-crap ":"))
      (doseq [{:keys [namespace name crap]} offenders]
        (println (str "  " namespace "/" name " " crap)))
      (System/exit 1))
    (println (str "CRAP gate passed: no function above " max-crap "."))))
