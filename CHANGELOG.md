# Changelog

### master (unreleased)

- Add `print-jvm-opt-for-startup-profiling` function to simplify setting up
  startup profiling with async-profiler and then render the results with
  clj-async-profiler.
- [#28](https://github.com/clojure-goes-fast/clj-async-profiler/pull/28)
  Customize output dir via a Java property.

### 1.0.5 (2023-08-15)

- Update vendored async-profiler libraries to
  [2.9](https://github.com/jvm-profiling-tools/async-profiler/releases/tag/v2.9).

### 1.0.4 (2023-05-29)

- Diffgraphs are now non-normalized by default (which is, probably, a less
  surprising behavior).
- Add a button to copy the title of the currently selected frame to make writing
  transforms easier.

### 1.0.3 (2022-11-16)

- Add `clj-async-profiler.ui/stop-server` function for shutting down the Web UI
  HTTP server.

### 1.0.2 (2022-11-04)

- Fix diffgraphs incorrectly calculating deltas for topmost frames.
- Add ability to toggle sidebar visibility using query params.
- Sort frames by width by default.

### 1.0.1 (2022-10-27)

- Update vendored async-profiler libraries to
  [2.8.3](https://github.com/jvm-profiling-tools/async-profiler/releases/tag/v2.8.3).
- Add a new function `clj-async-profiler.core/serve-ui` which supersedes
  `serve-files`. `serve-ui` takes an explicit `host` argument. If `host` is not
  provided, `localhost` is used by default (used to be `0.0.0.0`).
- The now deprecated `serve-files` still binds to `0.0.0.0` to retain
  compatibility.

### 1.0.0 (2022-07-22)

- **BREAKING:** Replace SVG flamegraphs with HTML flamegraphs. It is not
  possible to generate SVG flamegraphs anymore.
- Add dynamic configuration into the flamegraph. User can now change the
  following without regenerating a flamegraph:
  - minimal frame width
  - frame sort order
  - normal/inverse orientation (for icicles graphs)
  - stack transforms
- Remove the following options passed to `generate-flamegraph`: `:reverse?`,
  `:icicle?`, `:width`, `:height`, `:min-width`. These options are no longer
  needed at generation time since they are configurable within the HTML
  flamegraph itself.
- Introduce `:predefined-transforms` option that bakes the provided transforms
  into the generated HTML (see docstring for `clj-async-profiler.core/stop`).
- Enable browser caching of flamegraph files. Repeated opening of the same
  flamegraph in browser will no longer refetch the file from the server.
- Rewrite stack post-processing code into Java for faster generation of
  flamegraphs.
- Fix the 10-something second delay on MacOS when starting the profiler for the
  first time.

### 0.5.2 (2022-07-15)

- Update vendored async-profiler libraries to
  [2.8.2](https://github.com/jvm-profiling-tools/async-profiler/releases/tag/v2.8.2).
- Add prepackaged binary for Linux x64 MUSL libc.
- **BREAKING:** Remove prepackaged binaries for Linux x86 and Linux ARMv7
  (arm32) since async-profiler no longer ships them. These platforms continue to
  be supported but you'll have to build binaries for them manually.

### 0.5.1 (2021-07-30)

- Update vendored async-profiler libraries to
  [1.8.6](https://github.com/jvm-profiling-tools/async-profiler/releases/tag/v1.8.6).
- Add prepackaged binaries for Linux x86 and Linux AArch64.
- Add early access prepackaged binary for MacOS AArch64 (M1). Note that this is
  not an experimental version.

### 0.5.0 (2020-12-16)

- Update vendored async-profiler libraries to
  [1.8.2](https://github.com/jvm-profiling-tools/async-profiler/blob/master/CHANGELOG.md#182---2020-11-02).
- Add ability to customize the frame buffer size with `:framebuf` option to
  `start` and `profile`.
- Add ability to set default profiling/rendering options.

### 0.4.1 (2020-03-23)

- Update vendored async-profiler libraries to
  [1.6](https://github.com/jvm-profiling-tools/async-profiler/blob/master/CHANGELOG.md#16-2019-09-09).
- Fix the bug with Clojure special characters not being properly demunged in stackframes.

### 0.4.0 (2019-06-02)

- Add support for differential profiles.
- Refine browser UI.
- Allow to pass profiler run IDs instead of full filenames to `generate-*`
  functions.
- Change the format of generated filenames. They now include a run ID and event
  type.
- [#10](https://github.com/clojure-goes-fast/clj-async-profiler/issues/10): Add
  support for SVG's `width` and `height` options.

### 0.3.1 (2019-03-15)

- [#9](https://github.com/clojure-goes-fast/clj-async-profiler/issues/9): Fix
  out-of-bounds string access when demunging stackframes.

### 0.3.0 (2019-02-04)

- Add custom color scheme for generated flamegraphs.
- Automatically demunge Java and Clojure frames in the output.
- Add `:transform <fn>` capability to specify a custom transform function to
  post-process the collected stacks.
- Add `:threads true` option to generate stacks for each thread separately.
- BREAKING: remove arities that take an explicit PID argument. If you want to
  profile another process, pass `:pid <PID>` argument to any profiling function.

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
