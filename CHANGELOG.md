# Changelog

### 0.2.2 (2019-01-16)

- Update vendored async-profiler libraries to
  [1.5](https://github.com/jvm-profiling-tools/async-profiler/blob/master/CHANGELOG.md#15---2019-01-08).
  Big features of the new async-profiler:
  + Native stack traces on macOS.
  + Wall-clock profiler: pass `:event :wall` to the profiling command.
- Ship prebuilt library for Linux ARM platform.
- Fix `profile` generating flamegraph twice.
- Avoid illegal access warning on JDK9+.
- [#1](https://github.com/clojure-goes-fast/clj-async-profiler/issues/1): Don't
  create flamegraph file if profiler was not running.

### ~0.2.1 - botched release~

### 0.2.0 (2018-11-21)

- Update vendored async-profiler libraries to
  [1.4](https://github.com/jvm-profiling-tools/async-profiler/blob/master/CHANGELOG.md#14---2018-06-24).
  From now on, clj-async-profiler ships binaries prebuilt by async-profiler
  maintainers themselves, no longer building them manually.
- Deprecate arities in user-facing functions that take explicit `pid` argument.
  Instead, they now retrieve `:pid` value from the option map. If the value is
  not present, use the current process as profiler target.
- Add default arity without `options` map for each user-facing function.
- Add `profile` macro which runs the profiler exactly for the duration of
  `body`.
- Make it possible to use clj-async-profiler outside of REPL environment (see
  [clojure-goes-fast/clj-memory-meter#2](https://github.com/clojure-goes-fast/clj-memory-meter/issues/2)
  for context).
- Make the profiled event configurable through options.
- BREAKING: `profile-for` no longer returns a future, instead it blocks the
  current thread for the specified duration. You can still wrap this call in
  `(future ...)` manually if needed.

### 0.1.3 (2018-04-13)

- [#1](https://github.com/clojure-goes-fast/clj-async-profiler/issues/1): Fix
  problems with starting the profiler with Leiningen on Linux.
- [#3](https://github.com/clojure-goes-fast/clj-async-profiler/issues/3): Add a
  more informative message in case of missing agent dependencies.

### 0.1.2 (2018-02-09)

New options for `stop` and `profile-for`:

- `:min-width` - a number in pixels to limit the minimal width of a stackframe.
  Use this if the resulting flamegraph is too big and hangs your browser.
  Recommended value is from 1 to 5.
- `:reverse?` - if true, generate the reverse flamegraph which grows from
  callees up to callers.
- `:icicle?` - if true, invert the flamegraph upside down.



### 0.1.0 (2017-12-11)

The initial release of clj-async-profiler. Includes the ability to start and
stop the profiler manually, profile for a period of time, and generate
flamegraphs from the profiled data.
