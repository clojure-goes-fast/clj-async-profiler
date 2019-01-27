;; This file was extracted from lein-simpleton[1] to avoid extra dependencies.
;; Copyright (C) 2013 Fogus and contributors. lein-simpleton is distributed
;; under the Eclipse Public License, the same as Clojure.
;; [1] https://github.com/tailrecursion/lein-simpleton

(ns clj-async-profiler.server
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpHandler HttpServer HttpExchange)
           (java.io File FileNotFoundException)
           (java.net HttpURLConnection InetSocketAddress URLDecoder)))

(defn- respond
  ([exchange body]
   (respond exchange body HttpURLConnection/HTTP_OK))
  ([^HttpExchange exchange, ^String body, code]
   (.sendResponseHeaders exchange code 0)
   (doto (.getResponseBody exchange)
     (.write (.getBytes body))
     (.close))))

(defn- html
  [root things]
  (format "<html><head></head><body><h2>Directory listing for %s</h2><hr>
<ul>%s</ul><hr></body></html>"
          root
          (str/join (for [f things]
                      (format "<li><a href='%s'>%s</a></li>"
                              (str root (if (= "/" root) "" File/separator) f)
                              f)))))

(defn- get-extension [^String filename]
  (.substring filename (+ 1 (.lastIndexOf filename "."))))

(defn- serve [^HttpExchange exchange, ^File file]
  (let [ext (get-extension (.getName file))
        body-served (not= (.getRequestMethod exchange) "HEAD")
        length (.length file)]
    (doto (.getResponseHeaders exchange)
      (.add "Content-Type" (if (= ext "svg") "image/svg+xml" "text/plain"))
      (.add "Content-Length" (str length)))

    (.sendResponseHeaders exchange HttpURLConnection/HTTP_OK
                          (if body-served length -1))
    (when body-served
      (with-open [from (io/input-stream file)
                  to (io/output-stream (.getResponseBody exchange))]
        (io/copy from to)))))

(defn- remove-url-params [uri]
  (str/replace uri #"\?\S*$" ""))

(defn- fs-handler [base]
  (proxy [HttpHandler] []
    (handle [^HttpExchange exchange]
      (let [uri (URLDecoder/decode (remove-url-params (str (.getRequestURI exchange))))
            f (io/file (str base uri))
            filenames (sort (.list f))]
        (if (.isDirectory f)
          (do (.add (.getResponseHeaders exchange) "Content-Type" "text/html")
              (respond exchange (html uri filenames)))
          (try
            (serve exchange f)
            (catch FileNotFoundException e
              (respond exchange (.getMessage e) HttpURLConnection/HTTP_NOT_FOUND))
            (catch Exception e
              (respond exchange (.getMessage e) HttpURLConnection/HTTP_INTERNAL_ERROR))))))))

(defonce current-server (atom nil))

(defn start-server
  "Starts a simple webserver with the local directory `dir` as its root."
  [port dir]
  (when @current-server (.stop ^HttpServer @current-server 0))
  (println "Starting server on port" port)
  (reset! current-server
          (doto (HttpServer/create (InetSocketAddress. port) 0)
            (.createContext "/" (fs-handler dir))
            (.setExecutor nil)
            (.start))))
