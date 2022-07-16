(ns example.client
  (:require [aleph.http :as http]
            [cheshire.core :as json])
  (:import (java.time Instant ZoneId)
           java.time.format.DateTimeFormatter))

(defn rand-string
  ([] (rand-string (rand-int 20)))
  ([length]
   (let [arr (char-array length)]
     (dotimes [i length]
       (aset arr i (char (+ (int \a) (rand-int 25)))))
     (String. arr))))

(def date-formatter
  (-> (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
      (.withZone (ZoneId/systemDefault))))

(defn rand-date-str []
  (.format date-formatter (Instant/ofEpochSecond (rand-int Integer/MAX_VALUE))))

(declare rand-json)

(defn rand-json-array [max-depth]
  (doall (repeatedly (rand-int 10) #(rand-json (dec max-depth)))))

(defn rand-json-object [max-depth]
  (loop [n (rand-int 5), res {}]
    (if (<= n 0)
      res
      (recur (dec n) (assoc res (rand-string) (rand-json max-depth))))))

(defn rand-json-object [max-depth]
  (reduce (fn [m _] (assoc m (rand-string) (rand-json max-depth)))
          {} (range max-depth)))

(defn rand-json [max-depth]
  (case (rand-int 7)
    0 (rand-string)
    1 (rand-int 1000000)
    2 (rand-date-str)
    (3 4) (when (> max-depth 0)
            (rand-json-array max-depth))
    (5 6) (when (> max-depth 0)
            (rand-json-object max-depth))))

(defn generate-request []
  (format "%s,%d [ip=%d.%d.%d.%d usr=%d ses=%d origin=%s]: REQUEST (len=%s, action=%s): %s"
          (rand-date-str)
          (+ 100 (rand-int 800))
          (rand-int 255) (rand-int 255) (rand-int 255) (rand-int 255)
          (rand-int 1000000000)
          (rand-int 1000000000)
          (rand-string 8)
          (rand-int 100)
          (rand-string 8)
          (json/encode (rand-json-object 3))))

#_(generate-request)

(defn query-server [port]
  (-> @(http/post (format "http://localhost:%d/api/extract-json-from-log" port)
                  {:body (generate-request)})
      :body
      slurp))

#_(query-server 12345)

(defmacro do-for [millis & body]
  `(let [deadline# (+ (System/currentTimeMillis) ~millis)]
     (while (< (System/currentTimeMillis) deadline#)
       ~@body)))

(comment
  (require 'example.server 'clj-async-profiler.core)
  (example.server/start-server 12345)
  (clj-async-profiler.core/profile
   (do-for 5000 (query-server 12345)))
  (example.server/stop-server))
