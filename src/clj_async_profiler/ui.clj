(ns clj-async-profiler.ui
  (:require [clj-async-profiler.core :as core]
            [clj-async-profiler.wwws :as wwws :refer [redirect respond]]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import java.io.File))

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

(defn- handler [{:keys [path params]} base]
  (try
    (let [files (->> (.listFiles (io/file base))
                     (remove #(.isDirectory ^File %))
                     sort)]
      (cond (= path "/toggle-show-raw")
            (do (swap! ui-state update :show-raw-files not)
                (redirect "/"))

            (= path "/start-profiler")
            (do (core/start {:event (keyword (params "event"))})
                (redirect "/"))

            (= path "/stop-profiler")
            (do (core/stop)
                (redirect "/"))

            (= path "/clear-results")
            (do (core/clear-results)
                (redirect "/"))

            (= path "/")
            (let [files (if (:show-raw-files @ui-state)
                          files
                          (remove #(= (wwws/get-extension (str %)) "txt") files))]
              {:body (main-page path files)})

            (= path "/favicon.png")
            {:body (io/resource "favicon.png")
             :cache? true}

            :else
            (let [f (io/file (str base path))]
              (if (contains? (set files) f)
                {:body f, :cache? true}
                (respond 404 (str "Not found: " f))))))
    (catch Exception e
      (binding [*out* *err*] (println e))
      (respond 500 (format "<body>%s<br><a href=\"/\">Back</a></body>"
                           (.getMessage e))))))

(defonce current-server (atom nil))

(defn start-server
  "Starts a simple profiler webserver with the local directory `dir` as its root."
  [port dir]
  (when @current-server (wwws/stop-server @current-server))
  (println "Starting server on port" port)
  (reset! current-server (wwws/start-server #(handler % dir) port)))
