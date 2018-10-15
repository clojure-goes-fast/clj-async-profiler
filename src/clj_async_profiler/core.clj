(ns clj-async-profiler.core
  (:require [clj-async-profiler.server :as server]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh])
  (:import clojure.lang.DynamicClassLoader
           java.lang.management.ManagementFactory
           java.text.SimpleDateFormat
           java.util.Date))

;; Initialization and unpacking

(defonce ^:private tools-jar-classloader
  ;; First, find top-level Clojure classloader.
  (let [^DynamicClassLoader loader
        (loop [loader (.getContextClassLoader (Thread/currentThread))]
          (let [parent (.getParent loader)]
            (if (instance? DynamicClassLoader parent)
              (recur parent)
              loader)))]
    ;; Loader found, add tools.jar to it
    (let [file (io/file (System/getProperty "java.home"))
          file (if (.equalsIgnoreCase (.getName file) "jre")
                 (.getParentFile file)
                 file)
          file (io/file file "lib" "tools.jar")]
      (.addURL loader (io/as-url file)))
    loader))

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
  [in-stacks-file out-svg-file {:keys [min-width reverse? icicle?]
                                :or {icicle? reverse?}}]
  (let [args (flatten ["perl" (flamegraph-script) "--colors=java"
                       (if min-width [(str "--minwidth=" min-width)] [])
                       (if reverse? ["--reverse"] [])
                       (if icicle? ["--inverted"] [])
                       (str in-stacks-file)])
        p (apply sh/sh args)]
    (if (zero? (:exit p))
      (let [f (io/file out-svg-file)]
        (io/copy (:out p) f)
        f)
      (do (io/copy (:err p) *err*)
          (binding [*err* *out*] (flush))))))


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
    "stop" (format "%s,file=%s,collapsed" command (:file options))))

(def ^:private virtual-machines (atom {}))

(defn- mk-vm [pid]
  (let [vm-class (Class/forName "com.sun.tools.attach.VirtualMachine"
                                false tools-jar-classloader)
        method (.getDeclaredMethod vm-class "attach" (into-array Class [String]))]
    (.invoke method nil (object-array [pid]))))

(defn attach-agent [pid command-string]
  (let [pid (str pid)
        vm (or (@virtual-machines pid)
               (let [new-vm (mk-vm pid)]
                 (swap! virtual-machines assoc pid new-vm)
                 new-vm))
        agent-so (async-profiler-agent)]
    (try (.loadAgentPath vm agent-so command-string)
         (catch Exception ex
           ;; If agent failed to load, try to load the library with System/load
           ;; which hopefully throws a more informative exception.
           (System/load agent-so)
           ;; But if it didn't throw the original one.
           (throw ex)))))

(defn list-event-types
  "Print all event types that can be sampled by the profiler. Available options:

  :pid - process to attach to (default: current process)"
  ([] (list-event-types {}))

  ([options]
   (let [pid (or (:pid options) (get-self-pid))
         f (tmp-internal-file "list" "txt")]
     (attach-agent pid (make-command-string "list" {:file f}))
     (println (slurp f))))

  ([pid options]
   (println "[pid options] arity is deprecated. Add :pid to options map instead.")
   (list-event-types (assoc options :pid pid))))

(defn start
  "Start the profiler for the specified process ID. Available options:

  :pid - process to attach to (default: current process)
  :interval - sampling interval in nanoseconds (default: 1000000 - 1ms)"
  ([] (start {}))

  ([options]
   (let [pid (or (:pid options) (get-self-pid))
         f (tmp-internal-file "start" "txt")]
     (attach-agent pid (make-command-string
                        "start" (assoc options :file f, :event "cpu")))
     (slurp f)))

  ([pid options]
   (println "[pid options] arity is deprecated. Add :pid to options map instead.")
   (start (assoc options :pid pid))))

(defn stop
  "Stop the profiler for the specified process ID. and save the results into a
  temporary file. Return the file object with the results. Available options:

  :pid - process to attach to (default: current process)
  :generate-flamegraph? - if true, generate flamegraph in the same directory as
                          the profile (default: true)
  :min-width - minimum width in pixels for a frame to be shown on a flamegraph.
               Use this if the resulting flamegraph is too big and hangs your
               browser (default: nil, recommended: 1-5)
  :reverse? - if true, generate the reverse flamegraph which grows from callees
              up to callers (default: false)
  :icicle? - if true, invert the flamegraph upside down (default: false for
             regular flamegraph, true for reverse)"
  ([] (stop {}))

  ([options]
   (let [pid (or (:pid options) (get-self-pid))
         f (tmp-results-file "profile" "txt")]
     (attach-agent pid (make-command-string "stop" {:file f}))
     (if (:generate-flamegraph? options true)
       (let [flamegraph-file (tmp-results-file "flamegraph" "svg")]
         (run-flamegraph-script f flamegraph-file options)
         flamegraph-file)
       f)))

  ([pid options]
   (println "[pid options] arity is deprecated. Add :pid to options map instead.")
   (stop (assoc options :pid pid))))

(defmacro profile
  "Profile the execution of `body`. If the first argument is a map, treat it as
  options. For available options, see `clj-async-profiler.core/start` and
  `clj-async-profiler.core/stop`. `:pid` option is ignored, current process is
  always profiled. Additional options:

  :return-file - if true, return the generated flamegraph file, otherwise return
                 the value returned by `body` (default: false - return value)"
  [options? & body]
  (let [[options body] (if (map? options?)
                         [(dissoc options? :pid) body]
                         [{} (cons options? body)])]
    `(try (let [options# ~options
                _# (start options#)
                ret# (try ~@body
                          (finally (stop options#)))
                f# (stop options#)]
            (if (:return-file options#)
              f# ret#)))))

(defn profile-for
  "Run the profiler for the specified duration. Return a future that will deliver
  the file with the results. For available options, see `clj-async-profiler.core/start` and
  `clj-async-profiler.core/stop`."
  ([duration-in-seconds]
   (profile-for duration-in-seconds {}))

  ([duration-in-seconds options]
   (println (start options))
   (future
     (let [deadline (+ (.getTime (Date.)) (* duration-in-seconds 1000))]
       (while (< (.getTime (Date.)) deadline)
         (Thread/sleep 1000))
       (stop options))))

  ([pid duration-in-seconds options]
   (println "[pid duration options] arity is deprecated. Add :pid to options map instead.")
   (profile-for duration-in-seconds (assoc options :pid pid))))

(defn serve-files
  "Start a simple webserver of the results directory on the provided port."
  [port]
  (server/start-server port (io/file temp-directory "results")))

#_(serve-files 8080)
