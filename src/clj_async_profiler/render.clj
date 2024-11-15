(ns clj-async-profiler.render
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import java.io.ByteArrayOutputStream
           java.util.regex.Pattern
           java.util.zip.GZIPOutputStream))

;;;; Minimal probably-incorrect templating engine in the name of 0-deps gods.

(defn- parse-template [^String template]
  (loop [i 0, parts [], open? false]
    (let [next-i (.indexOf template (if open? ">>>" "<<<") i)]
      (if (= next-i -1)
        (conj parts (subs template i))
        (recur (+ next-i 3)
               (conj parts (cond-> (subs template i next-i)
                             open? keyword))
               (not open?))))))

#_(parse-template "<<<a>>>regular text <<<b>>> and then also<<<c>>> end")

(defn render-template [template variables-map]
  (let [sb (StringBuilder.)]
    (doseq [part (parse-template template)]
      (.append sb (if (keyword? part)
                    (if-some [val (get variables-map part)]
                      (str val)
                      (throw (ex-info (str "Missing value for" part) {})))
                    part)))
    (str sb)))

#_(render-template "<<<a>>>regular text <<<b>>> and then also<<<c>>>"
                   {:a 1, :b "two", :c []})

;;;; JSON-GZIP-BASE64 encoding for serializing config

(defn- edn->json
  "Implements a very crude \"json\" serializer. Don't use it for anything else!"
  [config]
  (letfn [(->json [obj]
            (cond (map? obj) (str "{" (->> obj
                                           (map (fn [[k v]]
                                                  (str (->json k) ":" (->json v))))
                                           (str/join ","))
                                  "}")
                  (sequential? obj) (str "[" (str/join "," (map ->json obj)) "]")
                  (instance? Pattern obj) (->json (str "/" (str/replace obj "/" "\\/") "/"))
                  (keyword? obj) (pr-str (name obj))
                  (string? obj) (pr-str obj)
                  (nil? obj) "null"
                  :else (str obj)))]
    (->json config)))

#_(edn->json {:transforms [{:type :replace, :what #"abc.+" :number 42}]})

(defn- gzip-string [^String s]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (with-open [zip (GZIPOutputStream. baos)]
      (io/copy (.getBytes s) zip))
    (.toByteArray baos)))

(defn- base64 [bytes]
  (.encodeToString (java.util.Base64/getUrlEncoder) bytes))

;;;; Flamegraph rendering

(defn- validate-transform [transform]
  (assert (#{:filter :remove :replace} (:type transform)))
  (assert (contains? #{nil true false} (:enabled transform)))
  (assert (or (string? (:what transform))
              (instance? java.util.regex.Pattern (:what transform))))
  (assert (or (not (= (:type transform) :replace))
              (string? (:replacement transform)))))

(defn- print-id-to-frame [id->frame]
  (let [sb (StringBuilder.)]
    (doseq [frame id->frame]
      (.append sb "\"")
      (.append sb frame)
      (.append sb "\",\n"))
    (str sb)))

(defn- print-add-stacks [stacks diffgraph?]
  (let [sb (StringBuilder.)
        prefix (if diffgraph? "d([" "a([")]
    (doseq [[stack value] stacks]
      (.append sb prefix)
      (doseq [frame stack]
        (.append sb frame)
        (.append sb ","))
      (.append sb "],")
      (if diffgraph?
        (let [{:keys [samples-a samples-b delta]} value]
          (.append sb (str samples-a))
          (.append sb ",")
          (.append sb (str samples-b)))
        (.append sb (str value)))
      (.append sb ");\n"))
    (str sb)))

(defn render-html-flamegraph [dense-profile options diffgraph?]
  (let [{:keys [stacks id->frame]} dense-profile
        idToFrame (print-id-to-frame id->frame)
        data (print-add-stacks stacks diffgraph?)
        config (merge {:transforms (:predefined-transforms options)} ; deprecated
                      (:config options))
        _ (run! validate-transform (:transforms config))
        packed-config (base64 (gzip-string (edn->json config)))
        full-js (-> (slurp (io/resource "flamegraph-rendering/script.js"))
                    (render-template
                     {:graphTitle     (pr-str (or (:title options) ""))
                      :isDiffgraph    (str (boolean diffgraph?))
                      :config         (str "\"" packed-config "\"")
                      :idToFrame      idToFrame
                      :stacks         data}))]
    (-> (slurp (io/resource "flamegraph-rendering/template.html"))
        (render-template {:script full-js}))))
