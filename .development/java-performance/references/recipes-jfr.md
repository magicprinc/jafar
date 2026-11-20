# JFR recipes (every query below was executed on JDK 25 / Jafar 0.29.0)

Examples below use Jafar tool notation. Execute through [the expert launcher](expert-tools.md): `python -I $jf jfr $rec --tool <tool> --args '<JSON object>'`; for a bare JfrPath query use `--query`. Omit `jfr_open`, `jfr_close`, aliases and session IDs; the launcher owns them. Pass the two files explicitly for `jfr_compare` (candidate positional, `--baseline` for baseline). Treat cross-session JfrPath examples as library/interactive notes, not launcher commands. Quick CPU/alloc: prefer [jvmlens](local-analysis.md).

## 0. Triage first

```
jfr_open(path="C:\runs\live-app.jfr", alias="live")   -> { id, availableTypes, chunkCount }
jfr_summary(sessionId="live")
jfr_diagnose(sessionId="live")                        -> findings[], capabilityGaps[], detailedAnalysis
```

`jfr_diagnose` returns severity-ranked findings already merged from summary, USE and TSA, plus an
explicit `capabilityGaps` list. Read the gaps: they say which question the recording cannot answer.

`jfr_open` always answers `"totalEvents": 0`, even for a recording with 38 507 events (issue #223).
Ignore that field; `jfr_summary` on the same session reports the real count.

`jfr_list_types(filter="Socket", scan=true)` answers "does this recording contain the event at
all, and how many". Metadata presence without events proves nothing - always count:

```
jdk.SocketRead   count 0     <- type exists, zero events recorded
jdk.SocketWrite  count 0
```

## 1. CPU

```
jfr_hotmethods(sessionId="live", limit=10)          -> leaf frames with sample counts and %
query: events/jdk.ExecutionSample | groupBy(sampledThread/javaName) | top(5, by=count)
jfr_flamegraph(eventType="jdk.ExecutionSample", direction="bottom-up", minSamples=15)
jfr_stackprofile(direction="top-down", buckets=10, minPct=1)
```

Verified output shapes: `hotmethods` returns `{method, samples, pct, type}`; `flamegraph` returns
folded strings `frame;frame;...;frame N` (root last for bottom-up). `pct` may use a comma decimal.

Reading rule: a JDK frame (`StringConcatHelper`, `DecimalDigits`) is the effect. Walk down the
folded stack until the first frame from the application package - that frame is the cause and the
place to optimise.

## 2. Allocation

```
query: events/jdk.ObjectAllocationSample | groupBy(objectClass/name, agg=sum, value=weight) | top(6, by=sum)
query: events/jdk.ObjectAllocationSample | groupBy(stackTrace/frames/0/method/name/string, agg=sum, value=weight) | top(6, by=sum)
query: events/jdk.ObjectAllocationSample | groupBy(stackTrace/frames/1/method/name/string, agg=sum, value=weight) | top(8, by=sum)
```

Verified: `weight` is a byte-weighted sum, so `double` values like `1.5929364152E10` mean ~15.9 GB
allocated during the recording. `frames/0` is the allocating leaf (usually JDK internals such as
`allocateUninitializedArray` or `newArray`); `frames/1`, `frames/2`, ... walk up towards your code.
Predicates on nested type names do **not** work (`[stackTrace/frames/1/method/type/name="lab"]`
returned 0 rows); filter with `[stackTrace/frames/N/method/name/string ~ ".*render.*"]` instead or
group first and read the names.

Aggregation keys for nested paths come back as objects (`{"string": "[B"}`) while flat fields come
back as plain strings - handle both when parsing.

## 3. GC and heap pressure

```
jfr_use(sessionId="live", resources=["memory"])
query: events/jdk.GCPhasePause | stats(duration)
query: events/jdk.GCPhasePause | count
```

`jfr_use` memory section reports heap used/committed after GC, GC pause time and count, and the top
allocating classes. Treat pause time, not pause count, as the cost.

## 4. Locks, parking, blocking

```
jfr_use(sessionId="live", resources=["threads"])
jfr_tsa(sessionId="live", correlateBlocking=true, topThreads=10)
query: events/jdk.ThreadPark | groupBy(parkedClass/name, agg=sum, value=duration) | top(5, by=sum)
query: events/jdk.JavaMonitorEnter | groupBy(monitorClass/name, agg=sum, value=duration) | top(5, by=sum)
```

**`jfr_tsa` is not trustworthy in 0.29.0**: it reads `eventThread`, which `jdk.ExecutionSample`
does not have, so every sample lands in one `threadName: "unknown"` bucket and the tool then calls
the distribution healthy (issue #222, patch verified). Recognition pattern: `totalThreads: 1` together
with `correlations.blockedOn[...].threads: 4` in the same response. Take per-thread evidence from
`jfr_stackprofile` (`threadCounts`) or `groupBy(sampledThread/javaName)`.

Verified `ThreadPark` output on a live app: `SynchronousQueue$Transferer` ~28.3 s, AQS
`ConditionObject` ~15.9 s, `CompletableFuture$Signaller` ~14.1 s of parked time. That is where a
queue or an async client actually waits.

Caveat: `jdk.JavaMonitorEnter` fires only after the configured threshold (tens of ms in the
`profile` settings). A low count means "little *long* contention", not "no contention".

## 5. I/O and remote calls - the trap that matters

Blocking socket I/O (`Socket`, `HttpURLConnection`, classic JDBC drivers) emits `jdk.SocketRead` /
`jdk.SocketWrite` above the threshold. **NIO-based clients do not**: with `java.net.http.HttpClient`
against a server that slept 50 ms per request, `jdk.SocketRead` had **0 events** while
`jdk.ThreadPark` on `CompletableFuture$Signaller` accumulated ~14 s.

So:
1. `jfr_list_types(filter="Socket", scan=true)` - if the count is 0, the client is NIO/non-blocking.
2. Fall back to `jdk.ThreadPark` grouped by `parkedClass/name`, plus wall-clock: the parked time on
   the client's future class is the remote-call wait.
3. For a blocking driver, `events/jdk.SocketRead | groupBy(host, agg=sum, value=duration)` and the
   same shape on `bytesRead` gives host-level attribution (field names `host`, `address`, `port`
   exist in `jdk.SocketRead`; verify with `jfr_list_types` before relying on one).

Consequence for the diagnosis "database or code?": wall-clock parked/socket time attributed to the
caller frame answers it; a purely CPU-bound recording with no such events rules the remote call out.

## 6. Exceptions

```
jfr_exceptions(sessionId="live", minCount=1)
query: events/jdk.JavaErrorThrow | groupBy(thrownClass/name) | top(5, by=count)
```

Verified: a healthy run still showed 4 `jdk.JavaErrorThrow` events, so a handful of exceptions is
not automatically a finding; judge by rate and by throw site.

## 7. Compare two runs

```
jfr_open(path="...\baseline.jfr", alias="base")
jfr_open(path="...\candidate.jfr", alias="cand")
jfr_compare(baselineSessionId="base", candidateSessionId="cand", limit=10)
```

Verified response: `metrics[]` with per-second rates and `deltaPct`, `frames[]` with
`baselineSelfPct`/`candidateSelfPct`, `findings[]`, and `comparability[]`. A real example of the
warning it emits: *"Low sample count (baseline 233, candidate 237): per-frame shares are noisy"*.
Never report a frame share change as a speedup - use the measured timings from the bench harness.

## 8. Run locally without a mount

```powershell
$jf = Join-Path $env:USERPROFILE '.agents\skills\java-performance\profiling\jafar_local.py'
python -I $jf jfr $rec --query 'events/jdk.ExecutionSample | count()'
python -I $jf jfr $rec --tool jfr_list_types --args '{"scan":true,"filter":"jdk.ExecutionSample"}'
```

Verify the payload and observed event counts before interpreting it. Nonzero exit means no usable result; do not fall back to `jafar show` for machine output.
