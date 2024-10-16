(ns clj-async-profiler.results
  "Code responsible for managing the generated files."
  (:require [clojure.java.io :as io])
  (:import java.io.File
           (java.time LocalDateTime ZoneId)
           (java.time.format DateTimeFormatter)
           java.util.Date))

(def root-directory
  (let [output-dir  (or (System/getProperty "clj-async-profiler.output-dir") "/tmp")
        root (io/file output-dir "clj-async-profiler")]
    (.mkdirs (io/file root "results"))
    (.mkdirs (io/file root "internal"))
    root))

(def ^:private ^DateTimeFormatter date-formatter
  (DateTimeFormatter/ofPattern "yyyyMMdd_HHmmss"))

(defn format-date [^Date date]
  (let [local-date-time (.atZone (.toInstant date) (ZoneId/systemDefault))]
    (.format date-formatter local-date-time)))

(defn parse-date [date-string]
  (let [local-date-time (LocalDateTime/parse date-string date-formatter)
        zoned-date-time (.atZone local-date-time (ZoneId/systemDefault))]
    (Date/from (.toInstant zoned-date-time))))

#_(parse-date (format-date (Date.)))

(let [cnt (atom 0)]
  (defn internal-file [suffix extension]
    (io/file root-directory "internal"
             (format "%s-%s-%s.%s" (format-date (Date.)) (swap! cnt inc)
                     suffix extension))))

(defn results-file [date id event-type kind extension]
  (let [filename (format "%s-%02d-%s-%s.%s" (format-date date) id
                         (name event-type) kind extension)]
   (io/file root-directory "results" filename)))

(defn parse-results-filename [filename]
  (try
    (when-let [[_ date id event kind ext]
               (re-matches #"([\d_]+)-(\d+)-(\w+)-(\w+)\.\w+" filename)]
      {:date (some->> date parse-date)
       :id (Integer/parseInt id)
       :event (keyword event)
       :kind kind})
    (catch Exception _)))

#_(parse-results-filename (.getName (results-file (java.util.Date.) 1 :cpu "flamegraph" "txt")))

;; Used to assign sequential IDs to profiler runs, so that just the ID instead
;; of the full filename can be passed to regenerate flamegraphs or diffs.
(defonce next-run-id (atom 0))
(defonce ^:private run-id->stacks-file (atom {}))
(defonce file->metadata (atom {}))

(defn associate-id-to-file [id stacks-file event]
  (swap! run-id->stacks-file assoc id {:id id, :stacks-file stacks-file
                                       :event event}))

(defn find-profile [run-id-or-stacks-file]
  (if (number? run-id-or-stacks-file)
    (@run-id->stacks-file run-id-or-stacks-file)
    ;; When the file was passed directly, infer information from its name.
    (let [^File f (io/file run-id-or-stacks-file)
          {:keys [id event]} (parse-results-filename (.getName f))]
      (when-not (.exists f)
          (throw (ex-info (str "File " f " does not exist.") {})))
      {:id (or id 0), :stacks-file f, :event (keyword (or event :cpu))})))

#_(find-profile (results-file (java.util.Date.) 1 :cpu "flamegraph" "txt"))

(defn find-stacks-file-by-flamegraph-file [^File flamegraph-file]
  (if-some [mta (@file->metadata flamegraph-file)]
    (:stacks-file mta)
    ;; If the given flamegraph file has no in-memory metadata, it means the
    ;; profile was generated in another process. Try to infer the collapsed
    ;; stacks file from the name of the flamegraph file.
    (let [fname (.getName flamegraph-file)
          stacks-fname (str (second (re-find #"^(.+)-flamegraph\.html$" fname))
                            "-collapsed.txt")
          stacks (io/file (.getParent flamegraph-file) stacks-fname)]
      (when (.exists stacks)
        stacks))))

(defn clear-results
  "Clear all results from /tmp/clj-async-profiler directory."
  []
  (doseq [f (.listFiles (io/file root-directory "results"))]
    (.delete ^java.io.File f))
  (doseq [f (.listFiles (io/file root-directory "internal"))]
    (.delete ^java.io.File f))
  (reset! next-run-id 0)
  (reset! run-id->stacks-file {})
  (reset! file->metadata {}))
