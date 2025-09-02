(ns clj-async-profiler.jfr
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import clj_async_profiler.Helpers
           (clj_async_profiler.convert JfrToHeatmap)
           (clj_async_profiler.jfr JfrReader)
           (java.io BufferedReader File)
           (java.util HashMap HashMap$Node)
           (java.util.function Consumer Function)))

(defn- efficient-split-by-semicolon [^String s]
  (let [l (java.util.ArrayList.)]
    (loop [last-idx 0]
      (let [idx (.indexOf s ";" last-idx)]
        (if (= idx -1)
          (do (.add l (.substring s last-idx))
              l)
          (do (.add l (.substring s last-idx idx))
              (recur (inc idx))))))))

(defn- count-same [frames-a frames-b]
  (loop [i 0]
    (let [frame-a (nth frames-a i nil)
          frame-b (nth frames-b i nil)]
      (if (and frame-a frame-b (= frame-a frame-b))
        (recur (inc i))
        i))))

(defn jfr-to-dense-profile [input]
  (let [converter (JfrToHeatmap. (JfrReader. (if (instance? File input)
                                               (.getAbsolutePath ^File input)
                                               ^String input)))
        demunge-cache (HashMap.)]
    (.convert converter)
    (let [frame->id-map (HashMap.)
          frame->id (fn [frame]
                      (or (.get frame->id-map frame)
                          (let [cnt (.size frame->id-map)]
                            (.put frame->id-map frame cnt)
                            cnt)))
          last-stack (object-array [nil])
          total-samples (long-array [0])
          acc (java.util.ArrayList.)
          all-samples (.samples (.collector converter))
          stackToId (.stackToId (.collector converter))
          traceIdToStack (.traceIdToStack (.collector converter))
          idToStack (.idToStack (.collector converter))
          samples-map (into [] (keep-indexed (fn [i samples]
                                               (when-let [s (get idToStack i)]
                                                 [(Helpers/demungeJavaClojureFrames s demunge-cache) (vec samples)])))
                            all-samples)
          _ (->> (sort-by first samples-map)
                 (run! (fn [[key value]]
                         (let [stack (->> key
                                          efficient-split-by-semicolon
                                          (mapv frame->id))
                               ;; value (.getValue ^HashMap$Node entry)
                               same (count-same stack (aget last-stack 0))
                               dense-stack (into [same] (drop same stack))]
                           (.add acc [dense-stack value])
                           (aset last-stack 0 stack)
                           (aset total-samples 0 (+ (aget total-samples 0) ^long (reduce + value)))))))
          id->frame-arr (object-array (.size frame->id-map))]
      (run! (fn [[k v]] (aset id->frame-arr v k)) frame->id-map)
      (cond-> {:stacks (vec acc)
               :id->frame (vec id->frame-arr)}
        true (assoc :total-samples (aget total-samples 0))))))
