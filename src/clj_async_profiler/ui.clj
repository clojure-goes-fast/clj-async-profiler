;; HTTP serving parts were extracted from lein-simpleton[1] to avoid extra dependencies.
;; Copyright (C) 2013 Fogus and contributors. lein-simpleton is distributed
;; under the Eclipse Public License, the same as Clojure.
;; [1] https://github.com/tailrecursion/lein-simpleton

(ns clj-async-profiler.ui
  (:require [clj-async-profiler.core :as core]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpHandler HttpServer HttpExchange)
           (java.io File FileNotFoundException)
           (java.net HttpURLConnection InetSocketAddress URL URLDecoder)))

(defn- respond
  ([exchange body]
   (respond exchange body HttpURLConnection/HTTP_OK))
  ([^HttpExchange exchange, ^String body, code]
   (.sendResponseHeaders exchange code 0)
   (doto (.getResponseBody exchange)
     (.write (.getBytes body))
     (.close))))

(def ^:private ui-state (atom {}))

(defn- profiler-controls []
  (let [^String status-msg (core/status)]
    (if (.contains status-msg "is running")
      (format "<form action=\"/stop-profiler\">[%s] profiling is running&nbsp;
               <input type=\"submit\" value=\"Stop profiler\"/></form>"
              (name (@@#'core/start-options :event :cpu)))
      (format "<form action=\"/start-profiler\">Event:&nbsp;
               <select name=\"event\">%s</select>&nbsp;
               <input type=\"submit\" value=\"Start profiler\"/></form>"
              (str/join
               (for [ev (core/list-event-types {:silent? true})]
                 (format "<option value=%s>%s</option>" (name ev) (name ev))))))))

(defn- main-page
  [root files]
  (let [{:keys [show-raw-files]} @ui-state]
   (format
    "<html><head>
<title>clj-async-profiler</title>
<link rel='icon' href='favicon.png' type='image/x-icon'/>
<style>
body {
margin: 1em auto;
max-width: 40em;
font: 1.1em/1.5 sans-serif;
}
a { text-decoration: none; }
</style></head><body><div><big>clj-async-profiler</big>
<small style=\"float: right\"><a href=\"/toggle-show-raw\">%s</a> | <a href=\"/clear-results\">Clear all results</a></small></div><hr>
<small>%s</small>
<hr><ul>%s</ul><hr></body></html>"
    (if show-raw-files "Hide raw files" "Show raw files")
    (profiler-controls)
    (->> (for [^File f files
               :let [fname (.getName f)
                     sz (.length f)]]
           (format "<li><a href='%s'>%s</a> <small><font color=\"gray\">(%s%s)</font></small></li>"
                   (str root fname)
                   fname
                   (if-let [{:keys [samples]} (@@#'core/flamegraph-file->metadata f)]
                     (if (< samples 1000)
                       (str samples " samples, ")
                       (format "%.1fk samples, " (/ samples 1e3)))
                     "")
                   (if (< sz 1024) (str sz " b") (format "%.1f Kb" (/ sz 1024.0)))))
         str/join))))

(defn- get-extension [^String filename]
  (subs filename (inc (.lastIndexOf filename "."))))

(defn- serve [^HttpExchange exchange file]
  (let [body-served (not= (.getRequestMethod exchange) "HEAD")
        length (condp instance? file
                 File (.length ^File file)
                 URL (.getContentLength (.openConnection ^URL file)))
        ext (get-extension (str file))]
    (doto (.getResponseHeaders exchange)
      (.add "Content-Type" (if (= ext "svg") "image/svg+xml" "text/plain"))
      (.add "Content-Length" (str length)))

    (.sendResponseHeaders exchange HttpURLConnection/HTTP_OK
                          (if body-served length -1))
    (when body-served
      (with-open [from (io/input-stream file)
                  to (io/output-stream (.getResponseBody exchange))]
        (io/copy from to)))))

(defn- redirect [^HttpExchange exchange, url]
  (.set (.getResponseHeaders exchange) "Location" (str url))
  (.sendResponseHeaders exchange HttpURLConnection/HTTP_SEE_OTHER -1))

(defn- split-url-params [uri]
  (rest (re-matches  #"([^?]+)(\?\S*)?" uri)))

(defn- handler [^HttpExchange exchange, base]
  (try
    (let [[uri params] (split-url-params (str (.getRequestURI exchange)))
          uri (URLDecoder/decode uri)
          files (->> (.listFiles (io/file base))
                     (remove #(.isDirectory ^File %))
                     sort)]
      (cond (= uri "/toggle-show-raw")
            (do (swap! ui-state update :show-raw-files not)
                (redirect exchange "/"))

            (= uri "/start-profiler")
            (do (core/start {:event (keyword (second (re-find #"event=([^&]+)" (str params))))})
                (redirect exchange "/"))

            (= uri "/stop-profiler")
            (do (core/stop)
                (redirect exchange "/"))

            (= uri "/clear-results")
            (do (core/clear-results)
                (redirect exchange "/"))

            (= uri "/")
            (let [files (if (:show-raw-files @ui-state)
                          files
                          (remove #(= (get-extension (str %)) "txt") files))]
              (.add (.getResponseHeaders exchange) "Content-Type" "text/html")
              (respond exchange (main-page uri files)))

            (= uri "/favicon.png")
            (serve exchange (io/resource "favicon.png"))

            :else
            (let [f (io/file (str base uri))]
              (if (contains? (set files) f)
                (serve exchange f)
                (respond exchange (str "Not found: " f) HttpURLConnection/HTTP_NOT_FOUND)))))
    (catch Exception e
      (binding [*out* *err*] (println e))
      (respond exchange (format "<body>%s<br><a href=\"/\">Back</a></body>" (.getMessage e))
               HttpURLConnection/HTTP_INTERNAL_ERROR))))

(defonce current-server (atom nil))

(defn start-server
  "Starts a simple webserver with the local directory `dir` as its root."
  [port dir]
  (when @current-server (.stop ^HttpServer @current-server 0))
  (println "Starting server on port" port)
  (reset! current-server
          (doto (HttpServer/create (InetSocketAddress. port) 0)
            (.createContext "/" (proxy [HttpHandler] []
                                  (handle [^HttpExchange exchange]
                                    (handler exchange dir))))
            (.setExecutor nil)
            (.start))))
