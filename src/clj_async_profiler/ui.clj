(ns clj-async-profiler.ui
  (:require [clj-async-profiler.core :as core]
            [clj-async-profiler.wwws :as wwws :refer [redirect respond]]
            [clj-async-profiler.render :as render]
            [clj-async-profiler.results :as results]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import java.io.File
           java.util.Date
           java.time.format.DateTimeFormatter
           java.time.ZoneId))

(def ^:private ui-state (atom {}))

(defmacro ^:private forj {:style/indent 1} [bindings body]
  `(str/join (for ~bindings ~body)))

(defn- profiler-controls []
  (let [^String status-msg (core/status)]
    (if (.contains status-msg "is running")
      (format "<form action=\"/stop-profiler\">[%s] profiling is running&nbsp;
               <input type=\"submit\" value=\"Stop profiler\"/></form>"
              (name (@@#'core/start-options :event :cpu)))
      (format "<form action=\"/start-profiler\">Event:&nbsp;
               <select name=\"event\">%s</select>&nbsp;
               <input type=\"submit\" value=\"Start profiler\"/></form>"
              (forj [ev (remove #{:ClassName.methodName}
                                (core/list-event-types {:silent? true}))]
                (format "<option value=%s>%s</option>" (name ev) (name ev)))))))

(def ^:private ^DateTimeFormatter date-formatter (DateTimeFormatter/ofPattern "MMM d, yyyy"))
(def ^:private ^DateTimeFormatter time-formatter (DateTimeFormatter/ofPattern "HH:mm"))

(defn- date->local-date [^Date date]
  (.format date-formatter (.atZone (.toInstant date) (ZoneId/systemDefault))))

(defn- date->local-time [^Date date]
  (.format time-formatter (.atZone (.toInstant date) (ZoneId/systemDefault))))

(defn- row [tag tr-classes & cells]
  (let [tag (name tag)
        tds (forj [c cells]
              (format "<%s>%s</%s>" tag (or c "") tag))]
    (format "<tr class='%s'>%s</tr>" tr-classes tds)))

(defn- format-size [sz samples]
  (let [sz (if (< sz 1024) (str sz " b") (format "%.1f Kb" (/ sz 1024.0)))
        samples (cond (nil? samples) nil
                      (< samples 1000) (str "<br>" samples " samples")
                      :else (format "<br>%.1fk samples" (/ samples 1e3)))]
    (cond-> sz samples (str samples))))

(defn- file-table [root files]
  (let [files (sort-by #(.lastModified ^File %) > files) ;; Newer on top
        parsed-files (for [^File f files
                           :let [info (results/parse-results-filename (.getName f))]]
                       [f (update info :date #(or % (Date. (.lastModified f))))])
        grouped (group-by #(date->local-date (:date (second %))) parsed-files)]
    (format
     "<table><thead>%s</thead><tbody>%s</tbody></table>"
     (row :th "heading"
          "Date/time" "ID" "File" "Event" "Size" "Extras" "Actions")
     (forj [[local-date day-files] grouped]
       (str (row :td "heading"
                 (str "" local-date "") "" "" "" "" "" "")
            (forj [[^File f {:keys [id date kind event]}] day-files
                   :let [filename (.getName f)
                         ^File stacks (results/find-stacks-file-by-flamegraph-file f)
                         {:keys [id1 id2 ^File stacks1 ^File stacks2]}
                         (@results/file->metadata f)]]
              (row :td "data"
                   (date->local-time date)
                   (when (and id stacks
                              (= (:stacks-file (results/find-profile id))
                                 stacks))
                     (format "%02d" id))
                   (format "<a href='%s'>%s</a>"
                           (str root filename)
                           (or kind filename))
                   (some-> event name)
                   (str "<small>" (format-size (.length f) (:samples (@results/file->metadata f))) "</small>")
                   (cond id1 (format "<a href='%s'>%02d</a> vs <a href='%s'>%02d</a>"
                                     (str root (.getName ^File stacks1))
                                     id1
                                     (str root (.getName ^File stacks2))
                                     id2)
                         stacks (format "<a href='%s'>raw</a>"
                                        (str root (.getName stacks))))
                   "")))))))

(defn- main-page [root files]
  (->> {:profiler-controls (profiler-controls)
        :file-table (file-table root files)}
       (render/render-template (slurp (io/resource "ui/index.html")))))

(defn- handler [{:keys [path params]} base]
  (try
    (let [files (->> (.listFiles (io/file base))
                     (remove #(.isDirectory ^File %))
                     sort)]
      (cond (= path "/start-profiler")
            (do (core/start {:event (keyword (params "event"))})
                (redirect "/"))

            (= path "/stop-profiler")
            (do (core/stop)
                (redirect "/"))

            (= path "/clear-results")
            (do (core/clear-results)
                (redirect "/"))

            (= path "/")
            {:body (main-page path files)}

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

(defn stop-server
  "Stops the profiler web UI server."
  []
  (when @current-server (wwws/stop-server @current-server)))

(defn start-server
  "Starts the profiler web UI with the local directory `dir` as its root."
  [host port dir]
  (stop-server)
  (let [server (reset! current-server
                       (wwws/start-server #(handler % dir) host port))]
    (println "[clj-async-profiler.ui] Started server at" (wwws/get-address server))
    server))
