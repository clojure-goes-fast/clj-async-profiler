(task-options!
 pom {:project     'com.clojure-goes-fast/clj-async-profiler
      :version     "0.1.3-SNAPSHOT"
      :description "Clojure wrapper around Java's async-profiler"
      :url         "https://github.com/clojure-goes-fast/clj-async-profiler"
      :scm         {:url "https://github.com/clojure-goes-fast/clj-async-profiler"}
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}}
 push {:repo "clojars"})

(set-env! :resource-paths #{"res" "src"}
          :source-paths   #{"src"}
          :dependencies   '[[org.clojure/clojure "1.8.0" :scope "provided"]])

(deftask build
  "Build the project."
  []
  (comp (pom) (jar)))
