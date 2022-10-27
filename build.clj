(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.string :as str]
            [clojure.tools.build.api :as b]
            clojure.tools.build.tasks.write-pom
            [org.corfield.build :as bb]))

(defmacro opts+ []
  `(assoc ~'opts
          :lib 'com.clojure-goes-fast/clj-async-profiler
          :version "1.0.1-SNAPSHOT"
          :resource-dirs ["res" "vendor"]
          :src-pom "res/pom-template.xml"))

(defn javac [opts]
  (b/javac (assoc (#'bb/jar-opts (opts+))
                  :javac-opts ["-source" "8" "-target" "8"])))

;; Hack to propagate scope into pom.
(alter-var-root
 #'clojure.tools.build.tasks.write-pom/to-dep
 (fn [f]
   (fn [[_ {:keys [mvn/scope]} :as arg]]
     (let [res (f arg)
           alias (some-> res first namespace)]
       (cond-> res
         (and alias scope) (conj [(keyword alias "scope") scope]))))))

(defn test "Run all the tests." [opts]
  (javac opts)
  (bb/run-tests (cond-> opts
                  (:clj opts) (assoc :aliases [(:clj opts)])))
  opts)

(defn jar
  "Compile and package the JAR."
  [opts]
  (bb/clean opts)
  (javac opts)
  (let [{:keys [class-dir src+dirs] :as opts} (#'bb/jar-opts (opts+))]
    (b/write-pom opts)
    (b/copy-dir {:src-dirs   src+dirs
                 :target-dir class-dir
                 :include    "**"
                 :ignores    [#"pom-template.xml" #".+\.java"]})
    (println "Building jar...")
    (b/jar opts)))

(defn deploy "Deploy the JAR to Clojars." [opts]
  (bb/deploy (opts+)))

;; To recompile Java class at runtime:
;; ((requiring-resolve 'virgil.compile/compile-all-java) ["src"])
