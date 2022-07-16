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

(set-env! :resource-paths #{"vendor" "res"}
          :source-paths   #{"src" "test"}
          :dependencies   '[[org.clojure/clojure "1.11.1" :scope "provided"]])

(deftask build
  "Build the project."
  []
  (comp (javac)
        (sift :to-resource [#"clj_async_profiler/.+\.clj$"])
        (pom) (jar)))

(ns-unmap 'boot.user 'test)
(deftask test
  "Check if agent can attach at all."
  []
  (comp (javac)
        (with-pass-thru _
          (require '[clj-async-profiler.core :as prof]
                   '[clojure.test :as test])
          ;; Check if agent can attach at all.
          ((resolve 'prof/list-event-types))
          ;; Try profiling a little bit and verify that a file is created.
          ((resolve 'prof/start) {:event :itimer})
          (reduce *' (range 1 100000))
          (let [stacks-file ((resolve 'prof/stop) {:generate-flamegraph? false})]
            (assert (.exists stacks-file))
            (assert (> (.length stacks-file) 10000)))

          (require 'clj-async-profiler.post-processing-test)
          (assert ((resolve 'test/successful?) ((resolve 'test/run-all-tests)
                                                #"clj-async-profiler\..+test$"))))))

(comment ;; Development
  (set-env! :dependencies #(conj % '[virgil "LATEST"]))
  (require '[virgil.boot :as virgil])
  (boot (virgil/javac* :verbose true))

  ;; Extra dependencies for example app.
  (set-env! :dependencies #(into % '[[aleph "0.4.7-alpha10"]
                                     [ring/ring-core "1.9.4"]
                                     [ring/ring-defaults "0.3.3"]
                                     [compojure "1.6.2"]
                                     [cheshire "5.10.1"]
                                     [instaparse "1.4.10"]])))
