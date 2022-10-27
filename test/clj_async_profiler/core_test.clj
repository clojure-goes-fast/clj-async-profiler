(ns clj-async-profiler.core-test
  (:require [clj-async-profiler.core :as sut]
            [clojure.test :refer :all])
  (:import java.net.URL))

(deftest basic-test
  ;; Check if agent can attach at all.
  (sut/list-event-types)
  ;; Try profiling a little bit and verify that a file is created.
  (sut/start {:event :itimer})
  (reduce *' (range 1 100000))
  (let [stacks-file (sut/stop {:generate-flamegraph? false})]
    (is (.exists stacks-file))
    (is (> (.length stacks-file) 10000))

    (let [fg-file (sut/generate-flamegraph stacks-file {})]
      (is (.exists fg-file))
      (is (> (.length fg-file) 10000)))))

(defn curl-ui [port]
  (let [conn (.openConnection (URL. (str "http://localhost:" port)))]
    (.setRequestMethod conn "GET")
    (.getResponseCode conn)))

(deftest web-ui-test
  (sut/serve-ui 8085)
  (is (= 200 (curl-ui 8085)))

  (sut/serve-files 8086)
  (is (= 200 (curl-ui 8086))))
