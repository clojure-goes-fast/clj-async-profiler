# clj-async-profiler

_You can read the motivation behind clj-async-profiler and the usage example in
the
[blog post](http://clojure-goes-fast.com/blog/profiling-tool-async-profiler/)._

This library is a thin Clojure wrapper around the
excellent
[async-profiler](https://github.com/jvm-profiling-tools/async-profiler)
and [FlameGraph](https://github.com/brendangregg/FlameGraph).

async-profiler is a low overhead sampling profiler for JVM that does not suffer
from the safepoint bias problem. It operates by attaching a native Java agent to
a running JVM process and collecting the stack traces samples by using
HotSpot-specific APIs. Current version of async-profiler that is used by
clj-async-profiler is
[1.4](https://github.com/jvm-profiling-tools/async-profiler/blob/master/CHANGELOG.md#14---2018-06-24).

FlameGraph is a set of scripts to
generate [flame graphs](http://www.brendangregg.com/flamegraphs.html). Flame
graphs are a visualization of profiled software, allowing the most frequent
code-paths to be identified quickly and accurately.

## Preparation

clj-async-profiler is only supported on GNU/Linux and MacOS. See
https://github.com/jvm-profiling-tools/async-profiler#supported-platforms.

If you run clj-async-profiler on MacOS, no extra steps are required.

If you are running GNU/Linux, you need to do the following:

1. Ensure that `perf_event_paranoid` is set to 1:

```
$ cat /proc/sys/kernel/perf_event_paranoid
1
```

If it prints 2, it means you are running a newer Linux kernel. You have to set
it to 1 to allow async-profiler to use kernel profiling data.

```
$ echo 1 | sudo tee /proc/sys/kernel/perf_event_paranoid
```

[See also](https://github.com/jvm-profiling-tools/async-profiler#basic-usage).

2. If you see stackframes like `/usr/lib/.../libjvm.so`, it means that you have
to install JDK debug symbols. E.g., on Ubuntu that would be the package
`openjdk-8-dbg`.

## Usage

Add `com.clojure-goes-fast/clj-async-profiler` to your dependencies:

[![](https://clojars.org/com.clojure-goes-fast/clj-async-profiler/latest-version.svg)](https://clojars.org/com.clojure-goes-fast/clj-async-profiler)

clj-async-profiler exposes an all-in-one facade for generating profiling flame
graphs. The most common usage scenario looks like this:

```clojure
(require '[clj-async-profiler.core :as prof])

;; something CPU-heavy is already running

(prof/profile-for 10 {}) ; Run profiler for 10 seconds

;; The resulting flamegraph will be stored in /tmp/clj-async-profiler/results/
;; You can view the SVG directly from there or start a local webserver:

(prof/serve-files 8080) ; Serve on port 8080
```

You can also start and stop the profiler manually with `prof/start` and
`prof/stop`.

Each profiling command accepts a map of options. See docstrings for each command
for the list of supported options.

Each profiling command optionnally accepts a PID as a first argument. If PID is
provided, an external JVM process will be sampled, otherwise the current process
is targeted.

## License

async-profiler is distributed under Apache-2.0.
See [APACHE_PUBLIC_LICENSE](license/APACHE_PUBLIC_LICENSE) file. The location of the original
repository
is
[https://github.com/jvm-profiling-tools/async-profiler](https://github.com/jvm-profiling-tools/async-profiler).

Copyright 2017 Andrei Pangin

---

[flamegraph.pl](flamegraph.pl) is distributed under CDDL-1.0.
See [COMMON_DEVELOPMENT_AND_DISTRIBUTION_LICENSE](license/COMMON_DEVELOPMENT_AND_DISTRIBUTION_LICENSE) file. The original file location
is
[https://github.com/brendangregg/FlameGraph/blob/master/flamegraph.pl](https://github.com/brendangregg/FlameGraph/blob/master/flamegraph.pl).

Copyright 2016 Netflix, Inc.  
Copyright 2011 Joyent, Inc.  All rights reserved.  
Copyright 2011 Brendan Gregg.  All rights reserved.

---

[lein-simpleton](https://github.com/tailrecursion/lein-simpleton) is distributed
under the Eclipse Public License.
See [ECLIPSE_PUBLIC_LICENSE](license/ECLIPSE_PUBLIC_LICENSE).

Copyright 2013 Fogus and contributors.

---

clj-async-profiler is distributed under the Eclipse Public License.
See [ECLIPSE_PUBLIC_LICENSE](license/ECLIPSE_PUBLIC_LICENSE).

Copyright 2017 Alexander Yakushev
