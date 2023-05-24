(ns clj-async-profiler.post-processing-test
  (:require [clj-async-profiler.post-processing :as sut]
            [clojure.string :as str]
            [clojure.test :refer :all]))

(deftest demunge-clojure-java-frames-test
  (is (= ["java.lang.Thread.run" "io.aleph.dirigiste.Executor$Worker$1.run" "io.aleph.dirigiste.Executor$3.run"
          "clojure.lang.AFn.run" "manifold.deferred.Deferred/fn--10121/fn--10122" "manifold.deferred.Listener.onSuccess"
          "manifold.deferred/eval10275/subscribe--10276/fn--10281" "manifold.deferred/eval10275/chain'---10296"
          "manifold.deferred/success!" "manifold.deferred.Deferred.success" "manifold.deferred.Deferred/fn--10121"
          "manifold.deferred.Listener.onSuccess" "manifold.deferred/eval10275/subscribe--10276/fn--10281"
          "manifold.deferred/eval10275/chain'---10296" "myapp.api/process-request/fn--28350/f--10858--auto----28351/fn--28370"
          "clojure.core/keep" "myapp.api/process-request/fn--28350/f--10858--auto----28351/->upstream-widget--28352"
          "myapp.widget.upstream/widget->upstream-widget" "myapp.widget.upstream/widget->upstream-widget*"
          "myapp.widget/to-gizmo-level" "myapp.widget/token-changes->gizmo-level" "clojure.core/map"
          "myapp.widget/token-changes->gizmo-level/fn--17221" "myapp.widget.widget-transforms/adapt-changes-to-use-case"
          "myapp.widget.widget-transforms/fix-endianness" "myapp.widget.widget-transforms/get-data-offsets" "user/eval3454/time*--3455"
          "clojure.lang.Reflector.invokeInstanceMethod" "clojure.lang.Reflector.invokeMatchingMethod" "java.lang.reflect.Method"
          "sun.reflect.DelegatingMethodAccessorImpl" "sun.reflect.NativeMethodAccessorImpl"
          "sun.reflect.NativeMethodAccessorImpl.invoke0" "myapp.disk.DiskImpl.getOrCompute" "myapp.disk.Cache.getOrComputeValue"
          "myapp.widget.widget-transforms/get-data-offsets/fn--16663" "clojure.core/keep" "clojure.core/transient"
          "clojure.lang.PersistentVector.asTransient" "clojure.lang.PersistentVector.asTransient"
          "clojure.lang.PersistentVector$TransientVector.<init>" "java.lang.Object[]_[i] 1"]
         (->
          (sut/demunge-java-clojure-frames "java/lang/Thread.run;io/aleph/dirigiste/Executor$Worker$1.run;io/aleph/dirigiste/Executor$3.run;clojure/lang/AFn.run;manifold/deferred/Deferred$fn__10121$fn__10122.invoke;manifold/deferred/Listener.onSuccess;manifold/deferred$eval10275$subscribe__10276$fn__10281.invoke;manifold/deferred$eval10275$chain_SINGLEQUOTE____10296.invoke;manifold/deferred$success_BANG_.invoke;manifold/deferred$success_BANG_.invokeStatic;manifold/deferred/Deferred.success;manifold/deferred/Deferred$fn__10121.invoke;manifold/deferred/Listener.onSuccess;manifold/deferred$eval10275$subscribe__10276$fn__10281.invoke;manifold/deferred$eval10275$chain_SINGLEQUOTE____10296.invoke;myapp/api$process_request$fn__28350$f__10858__auto____28351$fn__28370.invoke;clojure/core$keep.invoke;clojure/core$keep.invokeStatic;myapp/api$process_request$fn__28350$f__10858__auto____28351$__GT_upstream_widget__28352.invoke;myapp/widget/upstream$widget__GT_upstream_widget.invoke;myapp/widget/upstream$widget__GT_upstream_widget.invokeStatic;myapp/widget/upstream$widget__GT_upstream_widget_STAR_.invoke;myapp/widget/upstream$widget__GT_upstream_widget_STAR_.invokeStatic;myapp/widget$to_gizmo_level.invoke;myapp/widget$to_gizmo_level.invokeStatic;myapp/widget$token_changes__GT_gizmo_level.invoke;myapp/widget$token_changes__GT_gizmo_level.invokeStatic;clojure/core$map.invoke;clojure/core$map.invokeStatic;myapp/widget$token_changes__GT_gizmo_level$fn__17221.invoke;myapp/widget/widget_transforms$adapt_changes_to_use_case.invoke;myapp/widget/widget_transforms$adapt_changes_to_use_case.invokeStatic;myapp/widget/widget_transforms$fix_endianness.invoke;myapp/widget/widget_transforms$fix_endianness.invokeStatic;myapp/widget/widget_transforms$get_data_offsets.invoke;myapp/widget/widget_transforms$get_data_offsets.invokeStatic;user$eval3454$time_STAR___3455.invoke;user$eval3454$time_STAR___3455.invokePrim;clojure/lang/Reflector.invokeInstanceMethod;clojure/lang/Reflector.invokeMatchingMethod;java/lang/reflect/Method.invoke;sun/reflect/DelegatingMethodAccessorImpl.invoke;sun/reflect/NativeMethodAccessorImpl.invoke;sun/reflect/NativeMethodAccessorImpl.invoke0;myapp/disk/DiskImpl.getOrCompute;myapp/disk/Cache.getOrComputeValue;myapp/widget/widget_transforms$get_data_offsets$fn__16663.invoke;clojure/core$keep.invoke;clojure/core$keep.invokeStatic;clojure/core$transient.invoke;clojure/core$transient.invokeStatic;clojure/lang/PersistentVector.asTransient;clojure/lang/PersistentVector.asTransient;clojure/lang/PersistentVector$TransientVector.<init>;java.lang.Object[]_[i] 1")
          (str/split #";")))))

(deftest remove-lambda-ids-test
  (is (= "foo;bar;eval;baz" (sut/remove-lambda-ids "foo;bar;eval;baz")))
  (is (= "foo;bar;eval;baz" (sut/remove-lambda-ids "foo;bar;eval1234;baz")))
  (is (= "foo;bar;fn--;baz" (sut/remove-lambda-ids "foo;bar;fn--1234;baz")))
  (is (= "foo;bar;fn__;baz" (sut/remove-lambda-ids "foo;bar;fn__1234;baz")))
  (is (= "foo.bar.Lambda$..invoke;qux" (sut/remove-lambda-ids "foo.bar.Lambda$123.45678.invoke;qux")))
  (is (= "foo;bar;eval" (sut/remove-lambda-ids "foo;bar;eval1234")))
  (is (= "foo;bar;legal1234.invoke;fn__;qux90" (sut/remove-lambda-ids "foo;bar;legal1234.invoke;fn__5678;qux90"))))
