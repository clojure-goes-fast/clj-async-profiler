(ns clj-async-profiler.post-processing
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import clojure.lang.Compiler
           java.io.BufferedReader
           (java.util HashMap HashMap$Node)
           (java.util.function Consumer Function)))

(defn- safe-subs [^String s, ^long start, ^long end]
  (let [lng (.length s)]
    (if (and (>= start 0)
             (<= end lng)
             (<= start end))
      (.substring s start end)
      "")))

(defn- frame-has-special-char?
  "Clojure demunger is slow. If there are no special characters munged in the
  frame, we can take a faster path."
  [^String frame]
  (loop [i 0]
    (let [uscore (.indexOf frame "_" i)
          next-char-idx (inc uscore)]
      (if (= uscore -1)
        false
        (if (and (< next-char-idx (.length ^String frame))
                 (Character/isUpperCase (.charAt frame next-char-idx)))
          true
          (recur next-char-idx))))))

(defn demunge-java-clojure-frames
  "Transform that demunges Java and Clojure stackframes."
  ([s] (demunge-java-clojure-frames s nil))
  ([^String s, ^HashMap demunge-cache]
   ;; Hand-rolling "regexps" here for better performance.
   (let [lng (.length s)
         sb (StringBuilder.)]
     (loop [frame-beg 0]
       (if (>= frame-beg lng)
         (.toString sb)
         (let [frame-end (.indexOf s ";" frame-beg)
               frame-end (if (= frame-end -1) lng frame-end)
               dot (.lastIndexOf s "." frame-end)
               slash (.indexOf s "/" frame-beg)]
           (when-not (= frame-beg 0)
             (.append sb ";"))
           (if (and (> slash -1) (< slash frame-end)
                    (> dot -1) (> dot frame-beg))
             ;; Java or Clojure frame
             (let [^String method (.substring s (unchecked-inc-int dot) frame-end)]
               (if (and (or (.equals method "invoke")
                            (.equals method "doInvoke")
                            (.equals method "invokeStatic")
                            (.equals method "invokePrim"))
                        ;; Exclude things like clojure/lang/Var.invoke
                        (not (.equals "clojure/lang/" (safe-subs s frame-beg (+ frame-beg 13)))))

                 ;; Clojure frame
                 (let [^String frame (.substring s frame-beg dot)
                       new-frame (.replace frame \/ \.)
                       new-frame (if (frame-has-special-char? new-frame)
                                   (if demunge-cache
                                     (.computeIfAbsent
                                      demunge-cache new-frame
                                      (reify Function
                                        (apply [_ _]
                                          (Compiler/demunge new-frame))))

                                     (Compiler/demunge new-frame))

                                   (-> ^String new-frame
                                       (.replace \_ \-)
                                       (.replace \$ \/)))
                       ;; Check if next frame has the same name, and if so, drop it.
                       ^String possible-next-frame (.concat frame ".invokeStatic")
                       next-frame-end (min (+ frame-end (.length possible-next-frame) 1)
                                           lng)]
                   (.append sb new-frame)
                   (if (.equals possible-next-frame
                                (safe-subs s (inc frame-end) next-frame-end))
                     (recur (min (unchecked-inc-int next-frame-end) lng))
                     (recur (unchecked-inc-int frame-end))))

                 ;; Java frame
                 (let [ ;; start (unchecked-inc-int (.lastIndexOf s ";" dot))
                       ^String frame (.substring s frame-beg frame-end)
                       new-frame (.replace frame \/ \.)]
                   (.append sb new-frame)
                   (recur (unchecked-inc-int frame-end)))))

             ;; Other frame
             (do (.append sb (.substring s frame-beg frame-end))
                 (recur (unchecked-inc-int frame-end))))))))))

(defn read-raw-profile-file
  "Read raw TXT file generated by async-profiler and return a HashMap
  `stack->samples` where `stack` is a list of stackframes. Performs demunging
  and an optional custom transform."
  ([file] (read-raw-profile-file file identity))
  ([file transform]
   (with-open [^java.io.BufferedReader f (io/reader file)]
     (let [acc (HashMap.)
           demunge-cache (HashMap.)]
       (loop []
         (when-let [line (.readLine f)]
           (let [sep (.lastIndexOf line " ")
                 stack (.substring line 0 sep)
                 samples (Long/parseLong (.substring line (inc sep)))
                 xstack (-> stack
                            (demunge-java-clojure-frames demunge-cache)
                            transform)
                 value (.getOrDefault acc xstack 0)]
             (.put acc xstack (+ value samples))
             (recur))))
       acc))))

(defn- count-same [frames-a frames-b]
  (loop [i 0]
    (let [frame-a (nth frames-a i nil)
          frame-b (nth frames-b i nil)]
      (if (and frame-a frame-b (= frame-a frame-b))
        (recur (inc i))
        i))))

(defn- efficient-split-by-semicolon [^String s]
  (let [l (java.util.ArrayList.)]
    (loop [last-idx 0]
      (let [idx (.indexOf s ";" last-idx)]
        (if (= idx -1)
          (do (.add l (.substring s last-idx))
              (vec l))
          (do (.add l (.substring s last-idx idx))
              (recur (inc idx))))))))

(defn raw-profile->compact-profile
  "Transform a split profile into a \"compact profile\" structure which reuses
  stack frame strings and thus occupies much less space when serialized."
  [^HashMap raw-profile, count-total-samples?]
  (let [frame->id-map (HashMap.)
        frame->id (fn [frame]
                    (or (.get frame->id-map frame)
                        (let [cnt (.size frame->id-map)]
                          (.put frame->id-map frame cnt)
                          cnt)))
        last-stack (object-array [nil])
        total-samples (long-array [0])
        acc (java.util.ArrayList. (.size raw-profile))
        ;; Quite unconventional way to iterate over the map, but we want to sort
        ;; by the key without creating intermediate sequences.
        _ (-> (.entrySet raw-profile)
              .stream
              (.sorted (java.util.Map$Entry/comparingByKey))
              (.forEach
               (reify Consumer
                 (accept [_ entry]
                   (let [stack (->> (.getKey ^HashMap$Node entry)
                                    efficient-split-by-semicolon
                                    (mapv frame->id))
                         value (.getValue ^HashMap$Node entry)
                         same (count-same stack (aget last-stack 0))
                         compact-stack (into [same] (drop same stack))]
                     (.add acc [compact-stack value])
                     (aset last-stack 0 stack)
                     (when count-total-samples?
                       (aset total-samples 0 (+ (aget total-samples 0) ^long value))))))))
        id->frame-arr (object-array (.size frame->id-map))]
    (run! (fn [[k v]] (aset id->frame-arr v k)) frame->id-map)
    (cond-> {:stacks (vec acc)
             :id->frame (vec id->frame-arr)}
      count-total-samples? (with-meta {:total-samples (aget total-samples 0)}))))

(defn read-raw-profile-file-to-compact-profile
  ([file] (read-raw-profile-file-to-compact-profile file identity))
  ([file transform]
   (raw-profile->compact-profile (read-raw-profile-file file transform) true)))


;;;; Diff-related code

(defn remove-lambda-ids
  "Transform that removes numeric IDs next to anonymous functions and `eval`
  blocks. This is necessary to render a useful diffgraph between two separate
  program executions where these IDs may change."
  [^String s]
  ;; Terrible emulation of (str/replace s #"(eval|--)\d+" "$1") to be faster.
  (let [lng (.length s)
        sb (StringBuilder.)
        final-append (fn [beg]
                       (if (= beg 0)
                         s
                         (do (.append sb (.substring s beg))
                             (.toString sb))))]
    (loop [beg 0]
      (let [digit-idx (long
                       (loop [i (long beg)]
                         (if (< i lng)
                           (let [ch (.charAt s i)]
                             (if (Character/isDigit ch)
                               i
                               (recur (unchecked-inc i))))
                           -1)))]
        (if (> digit-idx -1)
          (let [non-digit-idx (long
                               (loop [i (inc digit-idx)]
                                 (if (< i lng)
                                   (let [ch (.charAt s i)]
                                     (if (Character/isDigit ch)
                                       (recur (unchecked-inc i))
                                       i))
                                   -1)))]
            (if (or (and (>= digit-idx 2)
                         (= (.charAt s (- digit-idx 2)) \-)
                         (= (.charAt s (- digit-idx 1)) \-))
                    (and (>= digit-idx 4)
                         (= (.charAt s (- digit-idx 4)) \e)
                         (= (.charAt s (- digit-idx 3)) \v)
                         (= (.charAt s (- digit-idx 2)) \a)
                         (= (.charAt s (- digit-idx 1)) \l)))
              (do (.append sb (.substring s beg digit-idx))
                  (if (> non-digit-idx -1)
                    (recur non-digit-idx)
                    (.toString sb)))

              (if (> non-digit-idx -1)
                (do (.append sb (.substring s beg non-digit-idx))
                    (recur non-digit-idx))
                (final-append beg))))

          (final-append beg))))))


(defn merge-two-profiles [profile1 profile2]
  (let [res (HashMap.)]
    (run! (fn [[k v]]
            (.put res k {:samples-a v
                         :samples-b 0}))
          profile1)
    (run! (fn [[k v]]
            (let [current (.get res k)]
              (.put res k (if current
                            (assoc current :samples-b v)
                            {:samples-a 0, :samples-b v}))))
          profile2)
    res))

(defn generate-compact-diff-profile
  [raw-profile-file1 raw-profile-file2 transform]
  (let [raw-profile1 (read-raw-profile-file raw-profile-file1
                                            (comp transform remove-lambda-ids))
        raw-profile2 (read-raw-profile-file raw-profile-file2
                                            (comp transform remove-lambda-ids))]
    (raw-profile->compact-profile
     (merge-two-profiles raw-profile1 raw-profile2) false)))
