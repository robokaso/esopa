(ns esopa.main
  (:gen-class)
  (:require [esopa.system :as system]
            [io.pedestal.log :as log]))

(defn -main
  [& _args]
  (let [{:keys [config] :as running} (system/start (system/config (System/getenv)))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(system/stop running)))
    (log/info :msg "Server started" :host (:host config) :port (:port config))))
