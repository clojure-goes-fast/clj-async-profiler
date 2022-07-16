(ns example.server
  (:require [aleph.http :as http]
            [cheshire.core :as json]
            [compojure.core :refer [defroutes POST]]
            [instaparse.core :as parse]
            [ring.middleware.defaults :refer :all]))

(def parser
  (parse/parser "
line = timestamp <' ['> event-meta <']: '> (request / <#'.*'>)
timestamp = #'\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2},\\d{3}'
event-meta = <'ip='> ip <ws> <'usr='> usr <ws> <'ses='> session <ws> <'origin='> origin
ip = #'\\d+\\.\\d+\\.\\d+\\.\\d+'
usr = #'[^\\s]+'
session = #'[^\\s]+'
origin = #'[^\\s\\]]+'
ws = #'\\s+'
request = <'REQUEST '> request-meta <#':\\s+'> json
request-meta = <'(len='> len <', action='> action <')'>
len = #'\\d+'
action = #'[^\\)]+'
content = #'.*'
json = #'.+'
"))

(def -example "2017-05-18 07:22:27,921 [ip=91.74.72.58     usr=623643726            ses=25268249  origin=ioakkpcpgfkobkghlhen]: REQUEST (len=61, action=submit): {\"ch\":[\"-1l4:2op: :0\"],\"rev\":50,\"action\":\"submit\",\"id\":52}")

(defn foreach-iterator [it f]
  (while (.hasNext it)
    (f (.next it))))

(defn hash-total
  ([object] (hash-total object (atom BigInteger/ZERO)))
  ([object mbi]
   (letfn [(append [i] (swap! mbi #(.add % (BigInteger/valueOf i))))]
     (cond (map? object) (foreach-iterator (.iterator object)
                                           #(do (append (.hashCode (.key %)))
                                                (append (.hashCode (.val %)))))
           (sequential? object) (foreach-iterator (.iterator object)
                                                  #(append (.hashCode %)))
           :else (append (.hashCode object)))
     @mbi)))

(defn parse-log-and-hash [log-line]
  (let [json (-> (parser log-line) (nth 3) (nth 2) second json/parse-string)]
    (str (hash-total json))))

#_(parse-log-and-hash -example)

(defroutes handler
  (POST "/api/extract-json-from-log" {body :body}
        {:body (parse-log-and-hash (slurp body))}))

(def server (atom nil))

(defn stop-server []
  (when @server (.close @server)))

(defn start-server [port]
  (stop-server)
  (reset! server (http/start-server (wrap-defaults #'handler {}) {:port 12345})))
