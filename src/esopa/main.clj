(ns esopa.main
  (:gen-class)
  (:require [esopa.commands :as commands]
            [esopa.db :as db]
            [esopa.system :as system]
            [io.pedestal.log :as log]))

(defn- serve
  [config]
  (let [running (system/start config)]
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(system/stop running)))
    (log/info :msg "Server started" :host (:host config) :port (:port config))))

(defn -main
  "Starts esopa, or with the arguments `grant-role EMAIL ROLE`, gives an account a role
  and exits (see esopa.commands)."
  [& args]
  (let [config (system/config (System/getenv))]
    (if (= "grant-role" (first args))
      (let [{:keys [exit message]} (commands/grant-role (db/connect config) (rest args))]
        (println message)
        (System/exit exit))
      (serve config))))
