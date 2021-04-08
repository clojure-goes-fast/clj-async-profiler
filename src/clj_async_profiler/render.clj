(ns clj-async-profiler.render
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

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

(defn render-html-flamegraph [compact-profile options]
  (let [{:keys [stacks id->frame]} compact-profile
        sb (StringBuilder.)
        _ (doseq [frame id->frame]
            (.append sb "\"")
            (.append sb frame)
            (.append sb "\",\n"))
        idToFrame (str sb)

        sb (StringBuilder.)
        _ (doseq [[stack cnt] stacks]
            (.append sb "a([")
            (doseq [frame stack]
              (.append sb frame)
              (.append sb ","))
            (.append sb "],")
            (.append sb (str cnt))
            (.append sb ");\n"))
        data (str sb)

        user-transforms (render-predefined-transforms
                         (:predefined-transforms options))

        full-js (-> (slurp (io/resource "flamegraph-rendering/script.js"))
                    (str/replace "<<<graphTitle>>>" (or (:title options) ""))
                    (str/replace "<<<isDiffgraph>>>" "false")
                    (str/replace "<<<userTransforms>>>" user-transforms)
                    (str/replace "<<<idToFrame>>>" idToFrame)
                    (str/replace "<<<stacks>>>" data))]
    (-> (slurp (io/resource "flamegraph-rendering/template.html"))
        (str/replace "{{script}}" full-js))))

(defn render-html-diffgraph [compact-diff-profile options]
  (let [{:keys [stacks id->frame]} compact-diff-profile
        sb (StringBuilder.)
        _ (doseq [frame id->frame]
            (.append sb "\"")
            (.append sb frame)
            (.append sb "\",\n"))
        idToFrame (str sb)

        sb (StringBuilder.)
        _ (doseq [[stack {:keys [samples-a samples-b delta]}] stacks]
            (.append sb "d([")
            (doseq [frame stack]
              (.append sb frame)
              (.append sb ","))
            (.append sb "],")
            (.append sb (str samples-a))
            (.append sb ",")
            (.append sb (str samples-b))
            (.append sb ");\n"))
        data (str sb)

        user-transforms (render-predefined-transforms
                         (:predefined-transforms options))

        full-js (-> (slurp (io/resource "flamegraph-rendering/script.js"))
                    (str/replace "<<<graphTitle>>>" (or (:title options) ""))
                    (str/replace "<<<isDiffgraph>>>" "true")
                    (str/replace "<<<userTransforms>>>" user-transforms)
                    (str/replace "<<<idToFrame>>>" idToFrame)
                    (str/replace "<<<stacks>>>" data))]
    (-> (slurp (io/resource "flamegraph-rendering/template.html"))
        (str/replace "{{script}}" full-js))))
