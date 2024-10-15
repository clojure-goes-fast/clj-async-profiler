(ns clj-async-profiler.render
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

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

;;;; Flamegraph rendering

(defn- validate-predefined-transform [transform]
  (assert (#{:filter :remove :replace} (:type transform)))
  (assert (contains? #{nil true false} (:enabled transform)))
  (assert (or (string? (:what transform))
              (instance? java.util.regex.Pattern (:what transform))))
  (assert (or (not (= (:type transform) :replace))
              (string? (:replacement transform)))))

(defn- render-predefined-transforms [predefined-transforms]
  (if predefined-transforms
    (->> (map (fn [{:keys [type enabled what replacement] :as t
                    :or {enabled true}}]
                (validate-predefined-transform t)
                (format "_makeTransform('%s', %s, %s, %s)"
                        (name type) enabled
                        (if (string? what)
                          (str "\"" what "\"")
                          (str "/" (str/replace what "/" "\\/") "/g"))
                        (if replacement
                          (format "'%s'" replacement) "null")))
              predefined-transforms)
         (str/join ",\n"))
    ""))

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

(defn render-html-flamegraph [compact-profile options diffgraph?]
  (let [{:keys [stacks id->frame]} compact-profile
        idToFrame (print-id-to-frame id->frame)
        data (print-add-stacks stacks diffgraph?)
        user-transforms (render-predefined-transforms
                         (:predefined-transforms options))

        full-js (-> (slurp (io/resource "flamegraph-rendering/script.js"))
                    (render-template
                     {:graphTitle     (pr-str (or (:title options) ""))
                      :isDiffgraph    (str (boolean diffgraph?))
                      :userTransforms user-transforms
                      :idToFrame      idToFrame
                      :stacks         data}))]
    (-> (slurp (io/resource "flamegraph-rendering/template.html"))
        (render-template {:script full-js}))))
