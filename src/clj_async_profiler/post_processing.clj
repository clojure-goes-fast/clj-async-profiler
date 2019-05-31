(ns clj-async-profiler.post-processing
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import clojure.lang.Compiler
           java.io.BufferedReader
           java.util.HashMap))

(defn- safe-subs [^String s, ^long start, ^long end]
  (let [lng (.length s)]
    (if (and (>= start 0)
             (<= end lng)
             (<= start end))
      (.substring  s start end)
      "")))

(defn demunge-java-clojure-frames
  "Transform that demunges Java and Clojure stackframes."
  [^String s]
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
                      new-frame (let [uscore (.indexOf new-frame "_")
                                      ;; Clojure demunger is slow. If there are
                                      ;; no special characters munged in the
                                      ;; frame, take a faster path.
                                      next-char-idx (inc uscore)]
                                  (if (and (< next-char-idx (.length ^String new-frame))
                                           (Character/isUpperCase (.charAt frame next-char-idx)))
                                    (Compiler/demunge new-frame)
                                    (-> ^String new-frame
                                        (.replace \_ \-)
                                        (.replace \$ \/))))
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
                (recur (unchecked-inc-int frame-end)))))))))

(defn remove-lambda-ids
  "Transform that removes numeric IDs next to anonymous functionas and `eval`
  blocks. This is necessary to render a useful diffgraph if the diffed runs were
  launched from the REPL."
  [^String s]
  (str/replace s #"(eval|__)\d+" "$1"))

(defn post-process-stacks
  "Perform post-processing of the profiling result with the given `transform`
  function and the default processors (demunging). Write the result to
  `out-file`. Return the total number of samples in the file."
  [stacks-file out-file transform]
  (let [acc (HashMap.)]
    (with-open [f (io/reader stacks-file)]
      (loop []
        (when-let [line (.readLine ^BufferedReader f)]
          (let [sep (.lastIndexOf line " ")
                stack (subs line 0 sep)
                count (Long. (subs line (inc sep)))
                xstack (-> stack demunge-java-clojure-frames transform)]
            (when xstack
              (let [value (get acc xstack 0)]
                (.put acc xstack (+ value count))))
            (recur)))))

    (with-open [out (io/writer out-file)]
      (binding [*out* out]
        (run! (fn [[stack count]]
                (println stack count))
              acc))
      (reduce + (vals acc)))))

(defn generate-diff-file
  "Create a renderable diff stacks file from two provided stacks files."
  [in-stacks-file1 in-stacks-file2 out-diff-file options]
  (let [stacks (HashMap.)
        normalize? (:normalize? options true)
        transform (:transform options identity)
        process-file
        (fn [file tuple-idx]
          (with-open [f (io/reader file)]
            (loop [total-count 0]
              (if-let [line (.readLine ^BufferedReader f)]
                (let [sep (.lastIndexOf line " ")
                      stack (-> (subs line 0 sep)
                                remove-lambda-ids
                                demunge-java-clojure-frames
                                transform)]
                  (if stack
                    (let [count (Long. (subs line (inc sep)))]
                      (when-not (.containsKey stacks stack)
                        (.put stacks stack (long-array 2)))
                      (aset ^longs (.get stacks stack) tuple-idx count)
                      (recur (+ total-count count)))
                    (recur total-count)))
                total-count))))
        total-count1 (process-file in-stacks-file1 0)
        total-count2 (process-file in-stacks-file2 1)
        scale (if normalize?
                (/ (float total-count1) total-count2)
                1)]
    (with-open [f (io/writer out-diff-file)]
      (doseq [[stack ^longs tuple] stacks
              :let [c1 (aget tuple 0)
                    ;; Normalize
                    c2 (Math/round (double (* (aget tuple 1) scale)))]]
        (binding [*out* f]
          (println stack c2 c1))))))
