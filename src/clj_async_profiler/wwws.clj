(ns clj-async-profiler.wwws
  "World's Worst Web Server: a single-file non-Ring-compliant (but LARPing as one)
  web server that should never be used ever.

  Supports:
  - serving files
  - generating response headers
  - basic MIME types
  - bare-bones query string parsing
  - primitive resource caching

  Doesn't support:
  - anything else."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           java.io.File
           (java.net HttpURLConnection InetSocketAddress URL)))

(defn get-extension [^String filename]
  (subs filename (inc (.lastIndexOf filename "."))))

(defn- query-string->map [^String params-string]
  (->> (.split (or params-string "") "&")
       (keep (fn [pair] (when-not (str/blank? pair)
                          (vec (.split ^String pair "=")))))
       (into {})))

(defn- serve-file [^HttpExchange exchange, file, cache?]
  (let [body-served (not= (.getRequestMethod exchange) "HEAD")
        length (condp instance? file
                 File (.length ^File file)
                 URL (.getContentLength (.openConnection ^URL file)))
        ext (get-extension (str file))
        response-headers (.getResponseHeaders exchange)]
    (.add response-headers "Content-Type"
          (case ext
            "svg" "image/svg+xml"
            "png" "image/png"
            "html" "text/html; charset=utf-8"
            "text/plain"))
    (.add response-headers "Content-Length" (str length))
    (when cache?
      (.add response-headers "Cache-Control" "private, max-age=31536000"))

    (.sendResponseHeaders exchange HttpURLConnection/HTTP_OK
                          (if body-served length -1))
    (when body-served
      (with-open [from (io/input-stream file)
                  to (io/output-stream (.getResponseBody exchange))]
        (io/copy from to :buffer-size 102400)))))

(defn- root-handler [^HttpExchange exchange, handler]
  (try
    (let [uri (.getRequestURI exchange)
          request {:uri uri
                   :path (.getPath uri)
                   :params (query-string->map (.getQuery uri))}
          {:keys [status headers body content-type cache?]} (handler request)
          response-headers (.getResponseHeaders exchange)
          code (or status HttpURLConnection/HTTP_OK)
          text-response? (string? body)
          headers (if text-response?
                    (merge {"Content-Type" "text/html"} headers)
                    headers)]
      (doseq [[header value] headers]
        (.add response-headers header value))
      (cond (nil? body) (.sendResponseHeaders exchange code -1)
            (string? body) (do (.sendResponseHeaders exchange code 0)
                               (doto (.getResponseBody exchange)
                                 (.write (.getBytes ^String body))
                                 (.close)))
            :else (serve-file exchange body (boolean cache?))))
    (catch Exception ex
      (binding [*out* *err*]
        (println "root-handler error:" ex)))))

(defn start-server
  "Starts a simple webserver with the local directory `dir` as its root."
  [handler, ^String host, port]
  (doto (HttpServer/create (InetSocketAddress. host ^int port) 0)
    (.createContext "/" (proxy [HttpHandler] []
                          (handle [^HttpExchange exchange]
                            (root-handler exchange handler))))
    (.setExecutor nil)
    (.start)))

(defn get-address [^HttpServer server]
  (some-> server .getAddress str))

(defn get-port [^HttpServer server]
  (some-> server .getAddress .getPort))

(defn stop-server [^HttpServer server]
  (.stop server 0))

;; Helpers

(defn redirect [url]
  {:headers {"Location" (str url)}
   :status HttpURLConnection/HTTP_SEE_OTHER})

(defn respond
  ([body] {:body body})
  ([code body] {:status code, :body body}))

(def no-content {:status 204})
