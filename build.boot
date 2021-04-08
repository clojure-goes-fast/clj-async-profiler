(System/setProperty "os.version" "10.5") ;; Hack for MacOS

(task-options!
 pom {:project     'com.clojure-goes-fast/clj-async-profiler
      :version     "1.0.0-SNAPSHOT"
      :description "Embeddable Clojure profiler (based on async-profiler)"
      :url         "https://github.com/clojure-goes-fast/clj-async-profiler"
      :scm         {:url "https://github.com/clojure-goes-fast/clj-async-profiler"}
      :license     {"Eclipse Public License"
                    "http://www.eclipse.org/legal/epl-v10.html"}}
 push {:repo "clojars"})

(set-env! :resource-paths #{"vendor" "res" "src"}
          :source-paths   #{"src"}
          :dependencies   '[[org.clojure/clojure "1.11.1" :scope "provided"]])

(deftask build
  "Build the project."
  []
  (comp (pom) (jar)))

(ns-unmap 'boot.user 'test)
(deftask test
  "Check if agent can attach at all."
  []
  (with-pass-thru _
    (require '[clj-async-profiler.core :as prof])
    ;; Check if agent can attach at all.
    ((resolve 'prof/list-event-types))
    ;; Try profiling a little bit and verify that a file is created.
    ((resolve 'prof/start) {:event :itimer})
    (reduce *' (range 1 100000))
    (assert (.exists ((resolve 'prof/stop) {:generate-flamegraph? false})))))
