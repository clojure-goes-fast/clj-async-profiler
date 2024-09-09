(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [clojure.tools.build.tasks.write-pom]
            [deps-deploy.deps-deploy :as dd]))

(defn default-opts [version]
  (let [url "https://github.com/clojure-goes-fast/clj-async-profiler"]
    {;; Pom section
     :lib 'com.clojure-goes-fast/clj-async-profiler
     :version version
     :scm {:url url, :tag version}
     :pom-data [[:description "Embedded high-precision Clojure profiler (based on async-profiler)"]
                [:url url]
                [:licenses
                 [:license
                  [:name "Eclipse Public License"]
                  [:url "http://www.eclipse.org/legal/epl-v10.html"]]]]

     ;; Build section
     :basis (b/create-basis {})
     :target "target"
     :class-dir "target/classes"}))

(defmacro opts+ [& body]
  `(let [~'opts (merge (default-opts (:version ~'opts)) ~'opts)]
     ~@body
     ~'opts))

(defn log [fmt & args] (println (apply format fmt args)))

(defn- jar-file [{:keys [target lib version]}]
  (format "%s/%s-%s.jar" target (name lib) version))

(defn clean [opts] (b/delete {:path (:target (opts+))}))

(defn javac [opts]
  (opts+
    (clean opts)
    (b/javac (assoc opts
                    :src-dirs ["src"]
                    :javac-opts ["-source" "8" "-target" "8"]))))

;; Hack to propagate scope into pom.
(alter-var-root
 #'clojure.tools.build.tasks.write-pom/to-dep
 (fn [f]
   (fn [[_ {:keys [mvn/scope]} :as arg]]
     (let [res (f arg)
           alias (some-> res first namespace)]
       (cond-> res
         (and alias scope) (conj [(keyword alias "scope") scope]))))))

(defn jar
  "Compile and package the JAR."
  [opts]
  (opts+
    (doto opts clean javac b/write-pom)
    (let [{:keys [class-dir basis]} opts
          jar (jar-file opts)]
      (println (format "Building %s..." jar))
      (b/copy-dir {:src-dirs   (:paths basis)
                   :target-dir class-dir
                   :include    "**"
                   :ignores    [#_#".+\.java"]})
      (b/jar (assoc opts :jar-file jar)))))

(defn deploy "Deploy the JAR to Clojars." [{:keys [version] :as opts}]
  (assert (and version (re-matches #"\d+\.\d+\.\d+.*" version)))
  (opts+
   (jar opts)
   (log "Deploying %s to Clojars..." version)
   (dd/deploy {:installer :remote
               :artifact (b/resolve-path (jar-file opts))
               :pom-file (b/pom-path opts)})))

(defn install [opts]
  (let [opts (update opts :version #(or % "99.99"))]
   (opts+
    (jar opts)
    (log "Installing %s to local Maven repository..." (:version opts))
    (b/install (assoc opts :jar-file (jar-file opts))))))

;; To recompile Java class at runtime:
;; ((requiring-resolve 'virgil/compile-java) ["src"])
