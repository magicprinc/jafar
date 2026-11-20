# Known defects in Jafar 0.29.0 and the workaround for each

Every entry below was reproduced on this machine (JDK 25.0.5, Windows 10, `jfr-mcp-0.29.0.jar`
sha1 `8f6fc22841af080acfb992a4ff912667a9219759`) and reported upstream. Read this before blaming your
own build: three of these produce a *confident wrong answer*, not an error.

| Symptom | Issue | Workaround |
|---|---|---|
| `jfr_tsa` reports `totalThreads: 1`, `threadName: "unknown"`, "healthy" | [#222](https://github.com/btraceio/jafar/issues/222) | use `jfr_stackprofile` or `jfr_query ... groupBy(sampledThread/javaName)` for per-thread data |
| `jfr_open` returns `totalEvents: 0` for a non-empty recording | [#223](https://github.com/btraceio/jafar/issues/223) | take counts from `jfr_summary` |
| `pct`/`heapSize`/`detail` strings use the JVM default locale (`47,8%`) | [#224](https://github.com/btraceio/jafar/issues/224) | parse defensively (replace `,` with `.`), never feed them to `Double.parseDouble` directly |
| `objects/instanceof/<interface> | count` -> 0 | [#225](https://github.com/btraceio/jafar/issues/225) | filter by a concrete or abstract superclass (`java.util.AbstractMap`, `java.util.AbstractList`) or by exact class |
| `waste()` returns rows whose `capacity/size/loadFactor/wastedBytes` are `null` | [#225](https://github.com/btraceio/jafar/issues/225) | run `waste()` per exact class (`objects/java.util.HashMap`) and drop rows with `wastedBytes == null` |
| `groupBy(class)` yields zero groups | [#226](https://github.com/btraceio/jafar/issues/226) | use `groupBy(className)` |
| `stats()` on an empty result returns `Infinity` / `-Infinity` | [#226](https://github.com/btraceio/jafar/issues/226) | check `count == 0` before using `min`/`max` |
| Shared server can hold `.jfr` / `.hprof` locks after close | [#227](https://github.com/btraceio/jafar/issues/227) | Use the [task-scoped launcher](expert-tools.md); verified file release after its JVM exits. Do not stop a borrowed server. |
| Build fails on Gradle newer than the 9.4.1 wrapper | [#228](https://github.com/btraceio/jafar/issues/228) | No build required for these workflows: use the pinned official JAR. |
| Direct shell splits paths containing spaces; emits banners around JSON | [#233](https://github.com/btraceio/jafar/issues/233), [#234](https://github.com/btraceio/jafar/issues/234) | Use the verified launcher, not `jafar show` or `script` as a machine API. |
| Direct shell may install a backend from Maven even with remote discovery disabled | [#235](https://github.com/btraceio/jafar/issues/235) | Use official stdio through the local launcher; do not let the shell bootstrap plugins. |

## Why this matters for a performance investigation

The first four produce plausible-looking output:

- TSA prints a healthy thread-state distribution for a recording with real contention, because it
  attributes every sample to one pseudo-thread.
- `instanceof/java.util.Map` returning 0 reads as "this heap has no maps", while
  `instanceof/java.util.AbstractMap` finds 67 728 of them.
- `stats()` over an empty group returns infinities, so a report line reads
  `shallow_max - shallow_min = -Infinity` instead of "no data".
- `jfr_open.totalEvents: 0` reads as "the recording is empty".

Rule of thumb: when a Jafar result contradicts something you can check cheaply (a `count` of the
same objects, `jfr_summary`, or the JDK's own `jfr summary <file>`), check it before building a
conclusion on it.

## Operational notes (not defects)

- MCP calls are dispatched concurrently. A script that writes `jfr_open` and `jfr_summary` into one
  stdin burst can receive `Session not found` because the second call overtakes the first. Await each
  response, as a normal MCP client does.
- `jfr_tsa` also reports `threadId: "unknown"` in `topThreadsByState` while `correlations.blockedOn`
  in the same response counts 4 distinct blocked threads - that internal contradiction is the
  cheapest way to recognise #222 without reading this file.
- `hdump_report` suggests queries in its findings; at least two of them (`groupBy(class)`,
  `instanceof/java.util.Map | waste()`) do not work in 0.29.0 (#225, #226). Verify a suggestion
  before trusting it.

## When the fixes land

Re-run the checks in this file after upgrading Jafar:

```powershell
jfr_tsa(sessionId=...)                                  # expect totalThreads > 1
hdump_query("objects/instanceof/java.util.Map | count") # expect > 0 on a heap with maps
hdump_query("objects/java.lang.String | groupBy(class) | stats(shallow)") # expect count > 0
```

The patches attached to #222 and #228 were verified on this machine; the heap-query fixes are
reported but not implemented here.
