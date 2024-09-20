(ns clj-async-profiler.util
  (:require [clojure.java.io :as io]))

(defmacro define-jdk-specific-functions []
  (if (resolve 'java.lang.ProcessHandle)
    ;; JDK11+-specific tools for attaching the native agent.
    '(do
       (import 'com.sun.tools.attach.VirtualMachine
               'java.lang.ProcessHandle)

       (defn load-agent-path [^VirtualMachine vm, agent-so, command-string]
         (.loadAgentPath vm agent-so command-string))

       (defn vm-attach [^String pid]
         (VirtualMachine/attach pid))

       (defn get-self-pid []
         (.pid (java.lang.ProcessHandle/current))))

    ;; JDK8-specific tools for attaching the native agent.
    '(do
       (import 'java.net.URLClassLoader
               'java.io.File
               'java.lang.management.ManagementFactory
               'clojure.lang.DynamicClassLoader)

       (defn- tools-jar-url []
         (let [^File file (io/file (System/getProperty "java.home"))
               file (if (.equalsIgnoreCase (.getName file) "jre")
                      (.getParentFile file)
                      file)
               file (io/file file "lib" "tools.jar")]
           (io/as-url file)))

       (defn- add-url-to-classloader-reflective
         "This is needed for cases when there is no DynamicClassLoader in the classloader
  chain (i.e., the env is not a REPL)."
         [^URLClassLoader loader, url]
         (doto (.getDeclaredMethod URLClassLoader "addURL" (into-array Class [java.net.URL]))
           (.setAccessible true)
           (.invoke loader (object-array [url]))))

       (defn- get-classloader
         "Find the uppermost DynamicClassLoader in the chain. However, if the immediate
  context classloader is not a DynamicClassLoader, it means we are not run in
  the REPL environment, and have to use reflection to patch this classloader.

  Return a tuple of [classloader is-it-dynamic?]."
         []
         (let [dynamic-cl? #(instance? DynamicClassLoader %)
               ctx-loader (.getContextClassLoader (Thread/currentThread))]
           (if (dynamic-cl? ctx-loader)
             ;; The chain starts with a dynamic classloader, walk the chain up to find
             ;; the uppermost one.
             (loop [loader ctx-loader]
               (let [parent (.getParent loader)]
                 (if (dynamic-cl? parent)
                   (recur parent)
                   [loader true])))

             ;; Otherwise, return the immediate classloader and tell it's not dynamic.
             [ctx-loader false])))

       (def ^:private tools-jar-classloader
         (delay
           (let [tools-jar (tools-jar-url)
                 [loader dynamic?] (get-classloader)]
             ((resolve 'clj-async-profiler.core/dbg-println) "Top level dynamic classloader:" loader)
             (if dynamic?
               (.addURL ^clojure.lang.DynamicClassLoader loader tools-jar)
               (add-url-to-classloader-reflective loader tools-jar))
             loader)))

       (defn load-agent-path [vm agent-so command-string]
         (.loadAgentPath vm agent-so command-string))

       (defn vm-attach [^String pid]
         (let [vm-class (Class/forName "com.sun.tools.attach.VirtualMachine"
                                       false @tools-jar-classloader)
               method (.getDeclaredMethod vm-class "attach" (into-array Class [String]))]
           (.invoke method nil (object-array [pid]))))

       (defn get-self-pid []
         ;; So complicated because of https://stackoverflow.com/questions/41512483/runtimemxbean-getname-hangs-on-mac-os-x-sierra-how-to-fix
         ;; Maybe can be removed now.
         (let [runtime (ManagementFactory/getRuntimeMXBean)
               jvm (.get (doto (.getDeclaredField (class runtime) "jvm")
                           (.setAccessible true))
                         runtime)]
           (.invoke (doto (.getDeclaredMethod (class jvm) "getProcessId"
                                              (into-array Class []))
                      (.setAccessible true))
                    jvm (object-array [])))))))

(define-jdk-specific-functions)
