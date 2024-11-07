(ns clj-async-profiler.flamebin
  "Code responsible for uploading profiling results to flamebin.dev."
  (:require [clj-async-profiler.post-processing :as proc]
            [clj-async-profiler.results :as results]
            [clojure.java.io :as io]
            [clojure.pprint :refer [cl-format]])
  (:import (java.net HttpURLConnection URL)))

(def flamebin-host (atom "https://flamebin.dev"))
#_(def flamebin-host (atom "http://localhost:8086"))

(defn- make-upload-url [type public?]
  (format "%s/api/v1/upload-profile?format=dense-edn&type=%s%s"
          @flamebin-host (name type)
          (if public? "&public=true" "")))

(defn- gzip-dense-profile [dense-profile]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (with-open [w (io/writer (java.util.zip.GZIPOutputStream. baos))]
      (binding [*print-length* nil
                *print-level* nil
                *out* w]
        (pr dense-profile)))
    (.toByteArray baos)))

(defn- report-successful-upload [stacks-file {:keys [location deletion-link read-token]}]
  (cl-format *out* "~%~%Uploaded ~A to Flamebin.~%Share URL: ~A~%Deletion URL: ~A~
~@[~%Private uploads don't show on the index page. Private profiles can only be decrypted ~
by providing read-token. The server doesn't store read-token for private uploads.~]"
             (str stacks-file) location deletion-link read-token))

(defn- upload-dense-profile [dense-profile event public?]
  (let [^bytes gzipped (gzip-dense-profile dense-profile)
        url (URL. (make-upload-url event public?))
        ^HttpURLConnection connection
        (doto ^HttpURLConnection (.openConnection url)
          (.setDoOutput true)
          (.setRequestMethod "POST")
          (.setRequestProperty "Content-Type" "application/edn")
          (.setRequestProperty "Content-Encoding" "gzip")
          (.setRequestProperty "Content-Length" (str (alength gzipped))))]
    (with-open [output-stream (.getOutputStream connection)]
      (io/copy gzipped output-stream))
    (let [status (.getResponseCode connection)
          msg (.getResponseMessage connection)
          body (slurp (.getInputStream connection))]
      (if (< status 400)
        (let [location (.getHeaderField connection "Location")
              id (.getHeaderField connection "X-Created-ID")
              read-token (.getHeaderField connection "X-Read-Token")
              edit-token (.getHeaderField connection "X-Edit-Token")
              deletion-link (.getHeaderField connection "X-Deletion-Link")]
          {:location location, :id id, :body body, :message msg,
           :read-token read-token, :edit-token edit-token, :deletion-link deletion-link})
        (throw (ex-info (str "Failed to upload profile: " msg)
                        {:status status, :body body}))))))

(defn upload-to-flamebin
  "Generate flamegraph from a collapsed stacks file, identified either by its file
  path or numerical ID, and upload it to flamebin.dev. Options:

  :public? - if true, flamegraph will be displayed on the main page and publicly
             accessible to everyone; otherwise, will requite a token to view."
  [run-id-or-stacks-file options]
  (let [{:keys [public?]} options
        {:keys [stacks-file event]} (results/find-profile run-id-or-stacks-file)
        dense-profile (proc/read-raw-profile-file-to-dense-profile stacks-file)
        {:keys [location] :as resp} (upload-dense-profile dense-profile event public?)]
    (report-successful-upload stacks-file resp)
    location))

#_(upload-to-flamebin 1 {:public? true})
#_(upload-to-flamebin "../flamebin/test/res/normal.txt" {})
