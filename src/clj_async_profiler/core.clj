(ns clj-async-profiler.core
  (:require [clj-async-profiler.server :as server]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh])
  (:import clojure.lang.DynamicClassLoader
           java.lang.management.ManagementFactory
           java.net.URLClassLoader
           java.text.SimpleDateFormat
           java.util.Date))

;;; Temp file machinery

(defonce ^:private temp-directory
  (let [root (io/file "/tmp" "clj-async-profiler")]
    (.mkdirs (io/file root "results"))
    (.mkdirs (io/file root "internal"))
    root))

(defonce ^:private ^SimpleDateFormat date-format
  (SimpleDateFormat. "yyyy-MM-dd-HH-mm-ss"))

(defn- tmp-internal-file [prefix extension]
  (io/file temp-directory "internal"
           (format "%s-%s.%s" prefix (.format date-format (Date.)) extension)))

(defn- tmp-results-file [prefix extension]
  (io/file temp-directory "results"
           (format "%s-%s.%s" prefix (.format date-format (Date.)) extension)))

;;; Dynamic attach initialization

(defn- tools-jar-url []
  (let [file (io/file (System/getProperty "java.home"))
        file (if (.equalsIgnoreCase (.getName file) "jre")
               (.getParentFile file)
               file)
        file (io/file file "lib" "tools.jar")]
    (io/as-url file)))

(defn- add-url-to-classloader-reflective
  "This is needed for cases when there is no DynamicClassLoader in the classloader
  chain (i.e., the env is not a REPL)."
  [^URLClassLoader loader, url]
  (doto (.getDeclaredMethod URLClassLoader "addURL" (into-array Class [java.net.URL]))
    (.setAccessible true)
    (.invoke loader (object-array [url]))))

(defn- get-classloader
  "Find the uppermost DynamicClassLoader in the chain. However, if the immediate
  context classloader is not a DynamicClassLoader, it means we are not run in
  the REPL environment, and have to use reflection to patch this classloader.

  Return a tuple of [classloader is-it-dynamic?]."
  []
  (let [dynamic-cl?
        #(#{"clojure.lang.DynamicClassLoader" "boot.AddableClassLoader"}
          (.getName (class %)))

        ctx-loader (.getContextClassLoader (Thread/currentThread))]
    (if (dynamic-cl? ctx-loader)
      ;; The chain starts with a dynamic classloader, walk the chain up to find
      ;; the uppermost one.
      (loop [loader ctx-loader]
        (let [parent (.getParent loader)]
          (if (dynamic-cl? parent)
            (recur parent)
            [loader true])))

      ;; Otherwise, return the immediate classloader and tell it's not dynamic.
      [ctx-loader false])))

(def ^:private tools-jar-classloader
  (delay
   (let [tools-jar (tools-jar-url)
         [loader dynamic?] (get-classloader)]
     (if dynamic?
       (.addURL loader tools-jar)
       (add-url-to-classloader-reflective loader tools-jar))
     loader)))

(defn- get-virtualmachine-class []
  ;; In JDK9+, the class is already present, no extra steps required.
  (or (try (resolve 'com.sun.tools.attach.VirtualMachine)
           (catch ClassNotFoundException _))
      ;; In earlier JDKs, load tools.jar and get the class from there.
      (Class/forName "com.sun.tools.attach.VirtualMachine"
                     false @tools-jar-classloader)))

;;; Agent unpacking

(def async-profiler-agent-path (atom nil))

(defn- macos? []
  (re-find #"(?i)mac" (System/getProperty "os.name")))

(defn- arm? []
  (re-find #"(?i)arm" (System/getProperty "os.arch")))

(defn- unpack-from-jar [resource-name]
  (let [path (io/file temp-directory resource-name)]
    (when-not (.exists path)
      (io/copy (io/input-stream (io/resource resource-name)) path))
    (.getAbsolutePath path)))

(defn- async-profiler-agent
  "Get the async profiler agent file. If the value of `async-profiler-agent-path`
  is not `nil`, return it, otherwise extract the .so from the JAR."
  []
  (or @async-profiler-agent-path
      (unpack-from-jar (cond (macos?) "libasyncProfiler-darwin.so"
                             (arm?)   "libasyncProfiler-linux-arm.so"
                             :else    "libasyncProfiler-linux.so"))))

;;; Flamegraph generation

(def flamegraph-script-path (atom nil))

(defn- flamegraph-script
  "Get the flamegraph.pl file. If the value of `flamegraph-script-path` is not
  nil, return it, otherwise extract the script from the JAR."
  []
  (or @flamegraph-script-path (unpack-from-jar "flamegraph.pl")))

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
    "status" (format "%s,file=%s" command (:file options))
    "start" (format "%s,event=%s,file=%s,interval=%s,collapsed"
                    command (name (:event options :cpu)) (:file options)
                    (:interval options 1000000))
    "stop" (format "%s,file=%s,collapsed" command (:file options))))

(defonce ^:private virtual-machines (atom {}))

(defn- mk-vm [pid]
  (let [vm-class (get-virtualmachine-class)
        method (.getDeclaredMethod vm-class "attach" (into-array Class [String]))]
    (.invoke method nil (object-array [pid]))))

(defmacro ^:private load-agent-path
  "Call `VirtualMachine.loadAgentPath` with the given command. This macro expands
  to either reflective or non-reflective call, depending whether VirtualMachine
  class is available at compile time (on JDK9+)."
  [vm agent-so command-string]
  (let [vm-sym (if (try (resolve 'com.sun.tools.attach.VirtualMachine)
                        (catch ClassNotFoundException _))
                 (with-meta vm {:tag 'com.sun.tools.attach.VirtualMachine})
                 vm)]
    `(.loadAgentPath ~vm-sym ~agent-so ~command-string)))

(defn attach-agent [pid command-string]
  (let [pid (str pid)
        vm (or (@virtual-machines pid)
               (let [new-vm (mk-vm pid)]
                 (swap! virtual-machines assoc pid new-vm)
                 new-vm))
        agent-so (async-profiler-agent)]
    (try (load-agent-path vm agent-so command-string)
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

(defn status
  "Get profiling agent status. Available options:

  :pid - process to attach to (default: current process)"
  ([] (status {}))
  ([options]
   (let [pid (or (:pid options) (get-self-pid))
         f (tmp-internal-file "status" "txt")]
     (attach-agent pid (make-command-string "status" {:file f}))
     (slurp f))))

(defn start
  "Start the profiler. Available options:

  :pid - process to attach to (default: current process)
  :interval - sampling interval in nanoseconds (default: 1000000 - 1ms)
  :event - event to profile, see `list-event-types` (default: :cpu)"
  ([] (start {}))

  ([options]
   (let [pid (or (:pid options) (get-self-pid))
         f (tmp-internal-file "start" "txt")
         _ (attach-agent pid (make-command-string "start" (assoc options :file f)))
         msg (slurp f)]
     (if (.startsWith ^String msg "Started")
       msg
       (throw (ex-info msg {})))))

  ([pid options]
   (println "[pid options] arity is deprecated. Add :pid to options map instead.")
   (start (assoc options :pid pid))))

(defn stop
  "Stop the currently runnning profiler and and save the results into a temporary
  file. Return the file object with the results. Available options:

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
         status-msg (status options)
         _ (when-not (.contains status-msg "is running")
             (throw (ex-info status-msg {})))
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
  options. For available options, see `start` and `stop`. `:pid` option is
  ignored, current process is always profiled. Additional options:

  :return-file - if true, return the generated flamegraph file, otherwise return
                 the value returned by `body` (default: false - return value)"
  [options? & body]
  (let [[options body] (if (map? options?)
                         [(dissoc options? :pid) body]
                         [{} (cons options? body)])]
    `(let [options# ~options
           _# (println (start options#))
           f# (atom nil)
           ret# (try ~@body
                     (finally (reset! f# (stop options#))))]
       (if (:return-file options#)
         @f# ret#))))

(defn profile-for
  "Run the profiler for the specified duration. Return the generated flamegraph
  file. For available options, see `start` and `stop`."
  ([duration-in-seconds]
   (profile-for duration-in-seconds {}))

  ([duration-in-seconds options]
   (println (start options))
   (let [deadline (+ (System/currentTimeMillis) (* duration-in-seconds 1000))]
     (while (< (System/currentTimeMillis) deadline)
       (Thread/sleep 1000)))
   (stop options))

  ([pid duration-in-seconds options]
   (println "[pid duration options] arity is deprecated. Add :pid to options map instead.")
   (profile-for duration-in-seconds (assoc options :pid pid))))

(defn serve-files
  "Start a simple webserver of the results directory on the provided port."
  [port]
  (server/start-server port (io/file temp-directory "results")))

#_(serve-files 8080)
