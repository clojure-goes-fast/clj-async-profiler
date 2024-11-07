(ns clj-async-profiler.core-test
  (:require [clj-async-profiler.core :as sut]
            [clj-async-profiler.flamebin :as flamebin]
            [clj-async-profiler.wwws :as wwws]
            [clj-async-profiler.ui :as ui]
            [clojure.string :as str]
            [clojure.test :refer :all])
  (:import java.net.URL))

;; Sanity check we run the Clojure version which we think we do.
(is (let [v (System/getenv "CLOJURE_VERSION")]
      (println "Running on Clojure" (clojure-version))
      (or (nil? v) (.startsWith (clojure-version) v))))

(deftest basic-test
  ;; Check if agent can attach at all.
  (sut/list-event-types)
  ;; Try profiling a little bit and verify that a file is created.
  (sut/start {:event :itimer, :features :all})
  (reduce *' (range 1 100000))
  (let [stacks-file (sut/stop {:generate-flamegraph? false})]
    (is (.exists stacks-file))
    (is (> (.length stacks-file) 10000))

    (let [fg-file (sut/generate-flamegraph stacks-file {})]
      (is (.exists fg-file))
      (is (> (.length fg-file) 10000)))

    ;; Test uploads to Flamebin
    (when (= (System/getenv "TEST_FLAMEBIN") "true")
      (reset! flamebin/flamebin-host "https://qa.flamebin.dev")
      (flamebin/upload-to-flamebin stacks-file {})

      (testing "private (default) uploads contain a read-token"
        (is (str/includes? (flamebin/upload-to-flamebin stacks-file {})
                           "read-token=")))

      (testing "public uploads don't have a read-token")
      (is (not (str/includes? (flamebin/upload-to-flamebin stacks-file {:public? true})
                              "read-token="))))))

(defn curl-ui [port]
  (let [conn (.openConnection (URL. (str "http://localhost:" port)))]
    (.setRequestMethod conn "GET")
    (.getResponseCode conn)))

(deftest web-ui-test
  (sut/serve-ui 8085)
  (is (= 200 (curl-ui 8085)))
  (ui/stop-server)

  (let [port (wwws/get-port (sut/serve-ui 0))]
    (is (= 200 (curl-ui port))))
  (ui/stop-server)

  (sut/serve-ui 8086)
  (is (= 200 (curl-ui 8086)))
  (ui/stop-server))

(deftest startup-opt-test
  (is (re-matches #"-agentpath.+collapsed" (sut/print-jvm-opt-for-startup-profiling {:features [:vtable :comptask]}))))
