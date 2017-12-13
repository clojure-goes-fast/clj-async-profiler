(ns clj-async-profiler.core
  (:require [clj-async-profiler.server :as server]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh])
  (:import java.lang.management.ManagementFactory
           java.net.URL
           java.text.SimpleDateFormat
           java.util.Date))

;; Initialization and unpacking

(defonce ^:private tools-jar-loaded
  (let [tools-jar (format "file:///%s/../lib/tools.jar"
                          (System/getProperty "java.home"))
        cl (.getContextClassLoader (Thread/currentThread))]
    (.addURL cl (URL. tools-jar))))

(def flamegraph-script-path (atom nil))
(def async-profiler-agent-path (atom nil))

(defonce ^:private temp-directory
  (let [root (io/file "/tmp" "clj-async-profiler")]
    (.mkdirs (io/file root "results"))
    (.mkdirs (io/file root "internal"))
    root))

(defn- macos? []
  (re-find #"(?i)mac" (System/getProperty "os.name")))

(defn- unpack-from-jar [resource-name]
  (let [path (io/file temp-directory resource-name)]
    (when-not (.exists path)
      (io/copy (io/input-stream (io/resource resource-name)) path))
    (.getAbsolutePath path)))

(defn async-profiler-agent
  "Get the async profiler agent file. If the value of `async-profiler-agent-path`
  is not `nil`, return it, otherwise extract the .so from the JAR."
  []
  (or @async-profiler-agent-path
      (unpack-from-jar (if (macos?)
                         "libasyncProfiler-darwin.so"
                         "libasyncProfiler-linux.so"))))

(defn flamegraph-script
  "Get the flamegraph.pl file. If the value of `flamegraph-script-path` is not
  nil, return it, otherwise extract the script from the JAR."
  []
  (or @flamegraph-script-path (unpack-from-jar "flamegraph.pl")))


;;; Temp file machinery

(defonce ^:private ^SimpleDateFormat date-format
  (SimpleDateFormat. "yyyy-MM-dd-HH-mm-ss"))

(defn- tmp-internal-file [prefix extension]
  (io/file temp-directory "internal"
           (format "%s-%s.%s" prefix (.format date-format (Date.)) extension)))

(defn- tmp-results-file [prefix extension]
  (io/file temp-directory "results"
           (format "%s-%s.%s" prefix (.format date-format (Date.)) extension)))


;;; Flamegraph generation

(defn run-flamegraph-script
  "Run Flamegraph script on the provided stacks file, rendering the SVG result."
  [in-stacks-file out-svg-file]
  (let [p (sh/sh "perl" (flamegraph-script) "--colors=java" (str in-stacks-file))]
    (if (zero? (:exit p))
      (let [f (io/file out-svg-file)]
        (io/copy (:out p) f)
        f)
      (io/copy (:err p) *err*))))


;;; Profiling

(defn get-self-pid
  "Returns the process ID of the current JVM process."
  []
  (let [^String rt-name (.getName (ManagementFactory/getRuntimeMXBean))]
    (subs rt-name 0 (.indexOf rt-name "@"))))

(defn- make-command-string [command options]
  (case command
    "list" (format "%s,file=%s" command (:file options))
    "start" (format "%s,event=%s,file=%s,interval=%s,collapsed"
                    command (:event options) (:file options)
                    (:interval options 1000000))
   "status" (format "%s,file=%s,collapsed" command (:file options))
    "stop" (format "%s,file=%s,collapsed" command (:file options))))

(def ^:private virtual-machines (atom {}))

(defn- mk-vm [pid]
  (let [method (.getDeclaredMethod (resolve 'com.sun.tools.attach.VirtualMachine)
                                   "attach"
                                   (into-array Class [String]))]
    (.invoke method nil (object-array [pid]))))

(defn attach-agent [pid command-string]
  (let [pid (str pid)
        vm (or (@virtual-machines pid)
               (let [new-vm (mk-vm pid)]
                 (swap! virtual-machines assoc pid new-vm)
                 new-vm))]
    (.loadAgentPath vm (async-profiler-agent) command-string)))

(defn start
  "Start the profiler for the specified process ID. If `pid` is not provided,
  target the current process. Available options:

  :interval - sampling interval in nanoseconds (default: 1000000 - 1ms)"
  ([options] (start (get-self-pid) options))
  ([pid options]
   (let [f (tmp-internal-file "start" "txt")]
     (attach-agent pid (make-command-string
                        "start" (assoc options :file f, :event "cpu")))
     (slurp f))))

(defn status
  "Get agent status"
  ([] (status (get-self-pid)))
  ([pid]
   (let [f (tmp-results-file "status" "txt")]
     (attach-agent pid (make-command-string "status" {:file f}))
     (slurp f))))

(defn is-running?
   "checks if the agent is running"
   [pid]
   (.contains (status pid) "is running"))

(defn persist-flamegraph [f {generate-flamegraph? :generate-flamegraph? :or {generate-flamegraph? true}}]
  (when generate-flamegraph?
       (let [flamegraph-file (tmp-results-file "flamegraph" "svg")]
         (run-flamegraph-script f flamegraph-file)
         flamegraph-file)))

(defn stop
  "Stop the profiler for the specified process ID. and save the results into a
  temporary file. Return the file object with the results. If `pid` is not
  provided, target the current process. Available options:

  :generate-flamegraph? - if true, generate flamegraph in the same directory as
                          the profile (default: true)"
  ([options] (stop (get-self-pid) options))
  ([pid options]
    (when (is-running? pid)
       (let [f (tmp-results-file "profile" "txt")]
         (attach-agent pid (make-command-string "stop" {:file f}))
         (persist-flamegraph f options)))
     ))

(defn profile-for
  "Run the profiler for the specified duration. Return a future that will deliver
  the file with the results. If `pid` is not provided, target the current
  process. For available options, see `clj-async-profiler.core/start` and
  `clj-async-profiler.core/stop`."
  ([duration-in-seconds options]
   (profile-for (get-self-pid) duration-in-seconds options))
  ([pid duration-in-seconds options]
   (println (start pid options))
   (future
     (let [deadline (+ (.getTime (Date.)) (* duration-in-seconds 1000))]
       (while (< (.getTime (Date.)) deadline)
         (Thread/sleep 1000))
       (stop pid options)))))

(defn serve-files
  "Start a simple webserver of the results directory on the provided port."
  [port]
  (server/start-server port (io/file temp-directory "results")))

(comment

  (defn list-options [pid]
    (let [f (tmp-internal-file "list" "txt")]
      (attach-agent pid (make-command-string "list" {:file f}))
      (println (slurp f))))

  (serve-files 8080))



