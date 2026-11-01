(ns esopa.webjars
  "Front-end libraries packaged as WebJars: Maven dependencies that hold their files
  under META-INF/resources/webjars on the class path. esopa.server serves them under
  /webjars."
  (:require [clojure.java.io :as io])
  (:import (org.webjars WebJarVersionLocator)))

(defn path
  "The URL path of `file` in the WebJar `webjar`, including the WebJar's version, like
  /webjars/htmx.org/4.0.0/dist/htmx.min.js. Throws when the file isn't on the class path."
  [webjar file]
  (let [full-path (.fullPath (WebJarVersionLocator.) webjar file)]
    (if (and full-path (io/resource full-path))
      (subs full-path (count "META-INF/resources"))
      (throw (ex-info "WebJar file not on the class path" {:webjar webjar :file file})))))
