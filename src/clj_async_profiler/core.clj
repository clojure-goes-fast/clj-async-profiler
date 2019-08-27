(ns clj-async-profiler.core
  (:require [clj-async-profiler.post-processing :as post-proc]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str])
  (:import java.lang.management.ManagementFactory
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

(let [cnt (atom 0)]
  (defn- tmp-internal-file [prefix extension]
    (io/file temp-directory "internal"
             (format "%s-%s_%s.%s" prefix (.format date-format (Date.))
                     (swap! cnt inc) extension))))

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

(defn- ^Class get-virtualmachine-class []
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
  [in-stacks-file out-svg-file {:keys [min-width reverse? icicle? width height]
                                :or {icicle? reverse?}}]
  (let [args (flatten ["perl" (flamegraph-script) "--colors=clojure"
                       (if min-width [(str "--minwidth=" min-width)] [])
                       (if width [(str "--width=" width)] [])
                       (if height [(str "--height=" height)] [])
                       (if reverse? ["--reverse"] [])
                       (if icicle? ["--inverted"] [])
                       (str in-stacks-file)])
        p (apply sh/sh args)]
    (if (zero? (:exit p))
      (let [f (io/file out-svg-file)]
        (io/copy (:out p) f)
        f)
      (throw (ex-info (:err p) {:cmd args})))))

;;; Profiling

;; Used to assign sequential IDs to profiler runs, so that just the ID instead
;; of the full filename can be passed to regenerate flamegraphs or diffs.
(defonce ^:private next-run-id (atom 0))
(defonce ^:private run-id->stacks-file (atom {}))
(defonce ^:private flamegraph-file->metadata (atom {}))
(defonce ^:private start-options (atom nil))

(defn find-profile [run-id-or-stacks-file]
  (if (number? run-id-or-stacks-file)
    (@run-id->stacks-file run-id-or-stacks-file)
    ;; When the file was passed directly, infer information from its name.
    (let [^java.io.File f (io/file run-id-or-stacks-file)
          [_ id event] (re-matches #"(\d+)-([^-]+)-.+" (.getName f))]
      (when-not (.exists f)
        (throw (ex-info (str "File " f " does not exist.") {})))
      {:id (if id (Integer/parseInt id) -1), :stacks-file f, :event (keyword (or event :cpu))})))

(defn get-self-pid
  "Returns the process ID of the current JVM process."
  []
  (let [^String rt-name (.getName (ManagementFactory/getRuntimeMXBean))]
    (subs rt-name 0 (.indexOf rt-name "@"))))

(defn- make-command-string [command options]
  (case command
    "list" (format "%s,file=%s" command (:file options))
    "status" (format "%s,file=%s" command (:file options))
    "start" (format "%s,event=%s,file=%s,interval=%s,%scollapsed"
                    command (name (:event options :cpu)) (:file options)
                    (:interval options 1000000) (if (:threads options) "threads," ""))
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

  :pid - process to attach to (default: current process)
  :silent? - if true, only return the event types, don't print them."
  ([] (list-event-types {}))
  ([options]
   (let [pid (or (:pid options) (get-self-pid))
         f (tmp-internal-file "list" "txt")
         _ (attach-agent pid (make-command-string "list" {:file f}))
         output (slurp f)
         event-types (->> (str/split-lines output)
                          (keep #(keyword (second (re-matches #"\s+(.+)" %)))))]
     (when-not (:silent? options)
       (println output))
     event-types)))

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
  :threads - profile each thread separately
  :event - event to profile, see `list-event-types` (default: :cpu)"
  ([] (start {}))
  ([options]
   (let [pid (or (:pid options) (get-self-pid))
         f (tmp-internal-file "start" "txt")
         _ (attach-agent pid (make-command-string "start" (assoc options :file f)))
         msg (slurp f)]
     (if (.startsWith ^String msg "Started")
       (do (reset! start-options options)
           msg)
       (throw (ex-info msg {}))))))

(defn generate-flamegraph
  "Generate a flamegraph SVG file from a collapsed stacks file, identified either
  by its filename, or numerical ID. For available options, see `stop`."
  [run-id-or-stacks-file options]
  (let [{:keys [id stacks-file event]} (find-profile run-id-or-stacks-file)
        flamegraph-file (tmp-results-file (format "%02d-%s-flamegraph" id (name event)) "svg")
        [f samples] (if-let [transform (get options :transform identity)]
                      (let [tfile (tmp-internal-file "transformed-profile" "txt")]
                        [tfile (post-proc/post-process-stacks stacks-file tfile transform)])
                      [stacks-file nil])]
    (run-flamegraph-script f flamegraph-file options)
    (swap! flamegraph-file->metadata assoc flamegraph-file {:samples samples})
    flamegraph-file))

(defn generate-diffgraph
  "Generate a diff flamegraph SVG file from two profiles, identified by their IDs
  or filenames. For rendering-related options, see `stop`. Extra options:

  :normalize? - normalize the numbers so that the total number of stacks in two
                runs are the same (default: true)."
  [profile-before profile-after options]
  (let [{id1 :id, stack1 :stacks-file, ev1 :event} (find-profile profile-before)
        {id2 :id, stack2 :stacks-file, ev2 :event} (find-profile profile-after)
        _ (when-not (= ev1 ev2)
            (throw (ex-info "Profiler runs must be of the same event type."
                            {:before ev1, :after ev2})))
        diff-file (tmp-internal-file "diff-stacks" "txt")
        _ (post-proc/generate-diff-file stack1 stack2 diff-file options)
        diffgraph-file (tmp-results-file
                        (format "%02d_%02d-%s-diff" id1 id2 (name ev1)) "svg")]
    (run-flamegraph-script diff-file diffgraph-file options)
    diffgraph-file))

(defn stop
  "Stop the currently running profiler and save the results into a temporary
  file. Return the file object with the results. Available options:

  :pid - process to attach to (default: current process)
  :generate-flamegraph? - if true, generate flamegraph in the same directory as
                          the profile (default: true)
  :min-width - minimum width in pixels for a frame to be shown on a flamegraph.
               Use this if the resulting flamegraph is too big and hangs your
               browser (default: nil, recommended: 1-5)
  :width     - width of the generated flamegraph (default: 1200px, recommended to change for big screens)
  :height    - height of the generated flamegraph
  :reverse? - if true, generate the reverse flamegraph which grows from callees
              up to callers (default: false)
  :icicle? - if true, invert the flamegraph upside down (default: false for
             regular flamegraph, true for reverse)"
  ([] (stop {}))
  ([options]
   (let [pid (or (:pid options) (get-self-pid))
         ^String status-msg (status options)
         _ (when-not (.contains status-msg "is running")
             (throw (ex-info status-msg {})))
         run-id (swap! next-run-id inc)
         ;; Theoretically, we can extract the profiler event from status, but
         ;; for now it always returns "wall", so we have to rely on options.
         event (:event @start-options :cpu)
         ;; Capitalize event so that it's always above "flamegraph" in the list.
         f (tmp-results-file (format "%02d-%s" run-id (name event)) "txt")]
     (attach-agent pid (make-command-string "stop" {:file f}))
     (swap! run-id->stacks-file assoc run-id {:id run-id, :stacks-file f, :event event})
     (if (:generate-flamegraph? options true)
       (generate-flamegraph run-id options)
       f))))

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
   (stop options)))

(defn serve-files
  "Start a simple webserver of the results directory on the provided port."
  [port]
  (require 'clj-async-profiler.ui)
  ((resolve 'clj-async-profiler.ui/start-server) port (io/file temp-directory "results")))

#_(serve-files 8080)

(defn clear-results
  "Clear all results from /tmp/clj-async-profiler directory."
  []
  (doseq [f (.listFiles (io/file temp-directory "results"))]
    (.delete ^java.io.File f))
  (reset! next-run-id 0)
  (reset! run-id->stacks-file {})
  (reset! flamegraph-file->metadata {}))
