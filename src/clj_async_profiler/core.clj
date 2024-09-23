(ns clj-async-profiler.core
  (:require [clj-async-profiler.post-processing :as post-proc]
            [clj-async-profiler.render :as render]
            [clj-async-profiler.util :as util]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import java.io.File
           java.text.SimpleDateFormat
           java.util.Date))

(defn- dbg-println [& more]
  (when (System/getProperty "clj-async-profiler.debug")
    (apply println "[clj-async-profiler]" more)))

;;; Temp file machinery

(defonce ^:private temp-directory
  (let [output-dir  (or (System/getProperty "clj-async-profiler.output-dir") "/tmp")
        root (io/file output-dir "clj-async-profiler")]
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

;;; Agent unpacking

(def async-profiler-agent-path (atom nil))

(defn- macos? []
  (re-find #"(?i)mac" (System/getProperty "os.name")))

(defn- aarch64? []
  (re-find #"(?i)aarch64" (System/getProperty "os.arch")))

(defn- unpack-from-jar [resource-name]
  (let [path (io/file temp-directory resource-name)]
    (when-not (.exists path)
      (if-let [resource (io/resource resource-name)]
        (io/copy (io/input-stream resource) path)
        (throw (ex-info (str "Could not find " resource-name " in resources.") {}))))
    (.getAbsolutePath path)))

(def ^:private inferred-agent-path
  (delay
    (let [lib (cond (macos?)   "libasyncProfiler-darwin-universal.so"
                    (aarch64?) "libasyncProfiler-linux-aarch64.so"
                    :else      "libasyncProfiler-linux-x64.so")]
      (dbg-println "Inferred native library:" lib)
      (unpack-from-jar lib))))

(defn- async-profiler-agent
  "Get the async profiler agent file. If the value of `async-profiler-agent-path`
  is not `nil`, return it, otherwise extract the .so from the JAR."
  []
  (or @async-profiler-agent-path @inferred-agent-path))

;;; Profiling

;; Used to assign sequential IDs to profiler runs, so that just the ID instead
;; of the full filename can be passed to regenerate flamegraphs or diffs.
(defonce ^:private next-run-id (atom 0))
(defonce ^:private run-id->stacks-file (atom {}))
(defonce ^:private flamegraph-file->metadata (atom {}))
(defonce ^:private default-options (atom {}))
(defonce ^:private start-options (atom nil))

(defn find-profile [run-id-or-stacks-file]
  (if (number? run-id-or-stacks-file)
    (@run-id->stacks-file run-id-or-stacks-file)
    ;; When the file was passed directly, infer information from its name.
    (let [^File f (io/file run-id-or-stacks-file)
          [_ id event] (re-matches #"(\d+)-([^-]+)-.+" (.getName f))]
      (when-not (.exists f)
        (throw (ex-info (str "File " f " does not exist.") {})))
      {:id (if id (Integer/parseInt id) -1), :stacks-file f, :event (keyword (or event :cpu))})))

(defn- make-command-string
  [command {:keys [file event interval framebuf threads features]
            :or {event :cpu, interval 1000000}}]
  (case command
    "list" (format "%s,file=%s" command file)
    "status" (format "%s,file=%s" command file)
    "start" (format "%s,event=%s,file=%s,interval=%s,%s%s%scollapsed"
                    command (name event) file interval
                    (if (seq features)
                      (str "features=" (str/join "+" (map name features)) ",")
                      "")
                    (if framebuf (str "framebuf=" framebuf ",") "")
                    (if threads "threads," ""))
    "stop" (format "%s,file=%s,collapsed" command file)))

(defonce ^:private virtual-machines (atom {}))

(defn attach-agent [pid command-string]
  (let [pid (str pid)
        vm (or (@virtual-machines pid)
               (get (swap! virtual-machines assoc pid (util/vm-attach pid)) pid))
        agent-so (async-profiler-agent)]
    (try (util/load-agent-path vm agent-so command-string)
         (catch Exception ex
           ;; If agent failed to load, try to load the library with System/load
           ;; which hopefully throws a more informative exception.
           (System/load agent-so)
           ;; But if it didn't, then throw the original one.
           (throw ex)))))

(defn list-event-types
  "Print all event types that can be sampled by the profiler. Options:

  :pid - process to attach to (default: current process)
  :silent? - if true, only return the event types, don't print them."
  ([] (list-event-types {}))
  ([options]
   (let [options (merge @default-options options)
         pid (or (:pid options) (util/get-self-pid))
         f (tmp-internal-file "list" "txt")
         _ (attach-agent pid (make-command-string "list" {:file f}))
         output (slurp f)
         event-types (->> (str/split-lines output)
                          (keep #(keyword (second (re-matches #"\s+(.+)" %)))))]
     (when-not (:silent? options)
       (println output))
     event-types)))

(defn status
  "Get profiling agent status. Options:

  :pid - process to attach to (default: current process)"
  ([] (status {}))
  ([options]
   (let [options (merge @default-options options)
         pid (or (:pid options) (util/get-self-pid))
         f (tmp-internal-file "status" "txt")]
     (attach-agent pid (make-command-string "status" {:file f}))
     (slurp f))))

(def ^:private start-options-docstring
  ":event - event to profile, see `list-event-types` (default: :cpu)
  :interval - sampling interval in nanoseconds (default: 1000000 - 1ms)
  :threads - profile each thread separately
  :features - a list of extra features to enable. Supported features:
    :vtable - show targets of vtable/itable calls
    :comptask - show JIT compilation task
  :framebuf - size of the buffer for stack frames (default: 1000000 - 1MB)")

(defn start
  "Start the profiler. Options:

  :pid - process to attach to (default: current process)
  %s"
  ([] (start {}))
  ([options]
   (let [options (merge @default-options options)
         pid (or (:pid options) (util/get-self-pid))
         f (tmp-internal-file "start" "txt")
         _ (attach-agent pid (make-command-string "start" (assoc options :file f)))
         msg (try (slurp f)
                  ;; On some platforms (seemingly, on M1), start file does not exist.
                  (catch java.io.FileNotFoundException _))]
     (if (or (nil? msg) (.startsWith ^String msg "Started"))
       (do (reset! start-options options)
           msg)
       (throw (ex-info msg {}))))))
(alter-meta! #'start update :doc format start-options-docstring)

(def ^:private stop-options-docstring
  ":title - title of the generated flamegraph (optional)
  :predefined-transforms - a list of maps that specify the dynamic transforms to
                           bake into the flamegraph. For example:

  ...
  :predefined-transforms [{:type :remove
                           :what \"frame_buffer_overflow\"}
                          {:type :replace
                           :what #\"(;manifold.deferred[^;]+)+\"
                           :replacement \";manifold.deferred/...\"}
  ...")

(defn generate-flamegraph
  "Generate flamegraph from a collapsed stacks file, identified either by its file
  path or numerical ID. Options:

  %s"
  [run-id-or-stacks-file options]
  (let [options (merge @default-options options)
        {:keys [id stacks-file event]} (find-profile run-id-or-stacks-file)
        flamegraph-file (tmp-results-file (format "%02d-%s-flamegraph"
                                                  id (name event)) "html")
        compact-profile (post-proc/read-raw-profile-file-to-compact-profile
                         stacks-file (get options :transform identity))]
    (spit flamegraph-file (render/render-html-flamegraph compact-profile options))
    (swap! flamegraph-file->metadata assoc flamegraph-file
           {:samples (:total-samples (meta compact-profile))})
    flamegraph-file))
(alter-meta! #'generate-flamegraph update :doc format stop-options-docstring)

(defn generate-diffgraph
  "Generate a diff flamegraph from two profiles, identified either by their file
  paths or IDs. Options:

  %s"
  [profile-before profile-after options]
  (let [options (merge @default-options options)
        {id1 :id, stack1 :stacks-file, ev1 :event} (find-profile profile-before)
        {id2 :id, stack2 :stacks-file, ev2 :event} (find-profile profile-after)
        _ (when-not (= ev1 ev2)
            (throw (ex-info "Profiler runs must be of the same event type."
                            {:before ev1, :after ev2})))
        diff-profile (post-proc/generate-compact-diff-profile
                      stack1 stack2 (get options :transform identity))
        diffgraph-file (tmp-results-file
                        (format "%02d_%02d-%s-diff" id1 id2 (name ev1)) "html")
        title (str (.getName ^File stack1) " vs " (.getName ^File stack2))]
    (spit diffgraph-file (render/render-html-diffgraph
                          diff-profile (assoc options :title title)))
    diffgraph-file))
(alter-meta! #'generate-diffgraph update :doc format stop-options-docstring)

(defn stop
  "Stop the currently running profiler and save the results into a text file.
  If flamegraph is generated next, the flamegraph file will be returned,
  otherwise the text file is returned. Available options:

  :pid - process to attach to (default: current process)
  :generate-flamegraph? - if true, generate flamegraph in the same directory as
                          the profile (default: true)
  %s"
  ([] (stop {}))
  ([options]
   (let [options (merge @default-options options)
         pid (or (:pid options) (util/get-self-pid))
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
(alter-meta! #'stop update :doc format stop-options-docstring)

(defmacro profile
  "Profile the execution of `body`. If the first argument is a map, treat it as
  options. Options:

  %s
  %s"
  [options? & body]
  (let [[options body] (if (map? options?)
                         [options? body]
                         [{} (cons options? body)])]
    `(let [options# ~options
           _# (println (start options#))
           ret# (try ~@body
                     (finally (stop options#)))]
       ret#)))
(alter-meta! #'profile update :doc format start-options-docstring stop-options-docstring)

(defn profile-for
  "Run the profiler for the specified duration. Return the generated flamegraph
  file. Options:

  :pid - process to attach to (default: current process)
  %s
  %s"
  ([duration-in-seconds]
   (profile-for duration-in-seconds {}))

  ([duration-in-seconds options]
   (println (start options))
   (let [deadline (+ (System/currentTimeMillis) (* duration-in-seconds 1000))]
     (while (< (System/currentTimeMillis) deadline)
       (Thread/sleep 1000)))
   (stop options)))
(alter-meta! #'profile-for update :doc format start-options-docstring stop-options-docstring)

(defn serve-ui
  "Start profiler web UI on the given `host` (default: localhost) and `port`."
  ([port] (serve-ui "localhost" port))
  ([host port]
   (require 'clj-async-profiler.ui)
   ((resolve 'clj-async-profiler.ui/start-server) host port
    (io/file temp-directory "results"))))

#_(serve-ui 8080)

(defn clear-results
  "Clear all results from /tmp/clj-async-profiler directory."
  []
  (doseq [f (.listFiles (io/file temp-directory "results"))]
    (.delete ^java.io.File f))
  (doseq [f (.listFiles (io/file temp-directory "internal"))]
    (.delete ^java.io.File f))
  (reset! next-run-id 0)
  (reset! run-id->stacks-file {})
  (reset! flamegraph-file->metadata {}))

(defn set-default-profiling-options
  "Set the map of default options that will be used for all subsequent profiling
  runs. Defaults will be merged with explicit options with lower priority."
  [default-options-map]
  {:pre [(map? default-options-map)]}
  (reset! default-options default-options-map))

(defn print-jvm-opt-for-startup-profiling
  "Generate a JVM option string that can be used to profile a JVM application
  completely from its start to finish. Prints further instructions to stdout.
  Options:

  %s"
  [options]
  (let [event (:event options :cpu)
        agent-path (async-profiler-agent)
        run-id (swap! next-run-id inc)
        f (tmp-results-file (format "%02d-startup-%s" run-id (name event)) "txt")
        cmd-string (make-command-string "start" (assoc options :file f))
        full-opt (format "-agentpath:%s=%s" agent-path cmd-string)]
    (println
     (format "Add this as a JVM option for the Java process you want to profile.
If you use Clojure CLI to launch, don't forget to add -J in the front:

    %s

Once the process finishes, go back to this REPL and execute this:

    (clj-async-profiler.core/generate-flamegraph \"%s\" {})"
             full-opt f))
    full-opt))
(alter-meta! #'print-jvm-opt-for-startup-profiling update :doc format start-options-docstring)
