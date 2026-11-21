# JVM diagnostics: CPU, process RAM, and dump triage

Use [the routing table](../SKILL.md) for the first tool. This page fills in the JDK and Windows diagnostics between captures. Use only the steps that answer the question; an existing JFR or dump does not require live attach. Commands below use PowerShell 7. Set `$jdkBin` to the target's matching JDK `bin` for live tools (`C:\Z\JDK\bin` for the local JDK 25), `$targetPid` to the **authorized** process, and `$recording` to an authorized existing `.jfr` or a new absolute path in private scratch. Never use PID `0` or a process name with `jcmd`.

## Start with the metric, not the profiler

| Symptom | First evidence | Follow-up |
|---|---|---|
| JVM consumes CPU | Measure **process CPU time over wall time** and completed work; correlate the same interval with JFR | jvmlens CPU hot **paths**; confirm the event and source/first application caller. CPU samples represent sampled activity, not milliseconds or an exact share of all process CPU. |
| High latency without high process CPU | Reproduce latency/throughput; check recorded GC pauses, monitor contention, parks and I/O in the same window | [JFR recipes](recipes-jfr.md); correlate the caller and request/DB timings. Missing samples do not exclude waits. |
| Allocation churn / frequent GC | `GC.heap_info` or bounded `jstat`; compare JFR weighted allocations and pause time | `memory` in [jvmlens](local-analysis.md); normalize allocated bytes by time or completed work before comparing. |
| Java heap grows after comparable collections | Track used heap at comparable GC/workload checkpoints | [Heap capture](heap-capture.md) and [MAT](mat-oql.md): histogram → dominators → paths to GC roots → comparable snapshots. |
| Process RAM grows but used heap is flat | Track working set and private bytes separately alongside heap use/commit | [NMT summary/diff](heap-capture.md#native-memory-when-the-heap-is-flat); check threads/stacks, metaspace, code cache or direct buffers based on evidence. |
| Hung now, thread dump already exists, or exact live state | `jstall` or `jcmd Thread.print` for a hang; `jetbrains-debugger` for a breakpoint | Do not attach a debugger just to find hot paths. `jstall` analyzes thread dumps, not HPROF. |

## Bounded live checks

Check PID, executable identity and start time against the owner's target without printing a command line or listing unrelated JVMs. CPU is cumulative in `Get-Process`; take a delta. A result near `1.0` below means one logical CPU was busy on average; it is not 100% of a multi-core host.

```powershell
$jdkBin = 'C:\Z\JDK\bin'
$p0 = Get-Process -Id $targetPid -ErrorAction Stop
$started = $p0.StartTime
$cpu0 = $p0.TotalProcessorTime.TotalSeconds
$timer = [Diagnostics.Stopwatch]::StartNew()
Start-Sleep -Seconds 2
$p1 = Get-Process -Id $targetPid -ErrorAction Stop
if ($p1.StartTime -ne $started) { throw 'Target JVM identity changed' }
[pscustomobject]@{
    CpuCoreEquivalents = [math]::Round(($p1.TotalProcessorTime.TotalSeconds - $cpu0) / $timer.Elapsed.TotalSeconds, 3)
    WorkingSetMiB      = [math]::Round($p1.WorkingSet64 / 1MB, 1)
    PrivateBytesMiB    = [math]::Round($p1.PrivateMemorySize64 / 1MB, 1)
}

# Read only the relevant diagnostic, after checking the target and attach permission:
& (Join-Path $jdkBin 'jcmd.exe') $targetPid GC.heap_info
& (Join-Path $jdkBin 'jstat.exe') -gcutil -t $targetPid 1000 5
```

- **Used heap** is not **committed heap** or `-Xmx`. **Working set** includes resident shared pages and can change under OS pressure; **private bytes** measure private committed memory, not resident bytes. Never subtract the heap from the working set to compute "native memory". Compare trends and record GC phase, process lifetime and units.
- `-gcutil` is an optional live trend: pool percentages depend on pool capacities and the collector; use deltas for cumulative GC counts/time. `jstat` needs accessible HotSpot perf data; in an isolated TEMP on this Windows host it failed to map the JVM to a user. If unavailable, use `GC.heap_info` and recorded GC events instead; do not disable isolation or interpret failure as zero GC. `jstat` output is not a stable machine API.
- NMT must be enabled at JVM startup and can change overhead; do not restart just to get it without approval. NMT committed/reserved are not resident memory, and NMT does not account for all native/JNI allocations. If exposed already, correlate direct buffer pool `MemoryUsed` (`java.nio:type=BufferPool,name=direct`); keep separate from RSS and do not enable a remote JMX endpoint.

## Capture a short window when no suitable artifact exists

- **New launch/test:** prefer `scripts/jfr-capture.ps1` (Java/Gradle/Maven) or use `-XX:StartFlightRecording` in an approved launch; no changes to the project build. Keep duration, storage, flags, JDK and workload explicit. Use the same settings on both sides of a comparison. For A/B snippets see [bench contract](bench-contract.md).
- **Running JVM:** attach only with explicit authorization. The current capture wrapper's `attach` mode prints the target command line and saves it in its manifest. **Do not use the wrapper if targets might carry secrets in arguments.** The following direct `jcmd` route does not retrieve those arguments; the JFR itself is still sensitive. Use a new task-owned capture path and an approved positive environment allowlist for any JVM you launch for testing or analysis.

```powershell
$jcmd = Join-Path $jdkBin 'jcmd.exe'
$jfr = Join-Path $jdkBin 'jfr.exe'
# Preflight: set $recording to a NEW private .jfr path; confirm the parent exists.
$name = 'agent_' + [guid]::NewGuid().ToString('N')
& $jcmd $targetPid JFR.start "name=$name" 'settings=profile' 'duration=30s' 'maxsize=128m' ('filename="' + $recording + '"')
if ($LASTEXITCODE -ne 0) { throw 'JFR.start failed' }
& $jcmd $targetPid JFR.check "name=$name"
# Run the workload during the agreed window. Confirm this named recording has ended
# before analyzing: the destination can exist at size zero during recording.
# Then verify the new artifact (offline):
if (-not (Test-Path -LiteralPath $recording -PathType Leaf) -or
    (Get-Item -LiteralPath $recording).Length -eq 0) { throw 'Recording missing or empty' }
& $jfr summary $recording
if ($LASTEXITCODE -ne 0) { throw 'Recording is not readable' }
```

Use `JFR.dump name=… filename=…` for an approved snapshot of an ongoing recording without stopping it; `JFR.stop name=… filename=…` can end a recording **you started** early. Verify the target JDK's options with `jcmd $targetPid help JFR.start`. Do not stop a borrowed recording. `settings=profile` is for a short diagnostic window; `default` is lower overhead. Check *actual* observed counts, settings/thresholds, workload coverage and recency before interpreting an empty or small result.

## Pick the narrowest tool

| Need | Tool and boundary |
|---|---|
| Inspect a JFR locally | `jfr summary` (counts/chunks), `jfr metadata --events jdk.ExecutionSample $recording` (schema, not observations), `jfr view hot-methods $recording` (bounded attribution), `jfr print --events jdk.ExecutionSample --json $recording` on **small** files or bounded exports; aggregate locally, don't put raw events into context. `jfr help configure` explains custom `.jfc`; capture only additional events relevant to the hypothesis. |
| Explain CPU, allocations, GC, locks or I/O | [jvmlens](local-analysis.md) for one focused report; compare event definitions and filters before expecting exact agreement with JDK counts. [Jafar](expert-tools.md) for precise JfrPath, comparison, graphs and existing pprof/OTLP Profiles. |
| Inspect heap, class loaders or native subsystems | `jcmd` first: `GC.heap_info`, `VM.classloader_stats`, `Compiler.codecache` or `VM.native_memory`; request `help <command>` against the target. `GC.class_histogram` and `GC.heap_dump` can be high-impact: see [heap capture](heap-capture.md). Do not force GC for convenience. |
| Analyze HPROF | [MAT](mat-oql.md) for histogram, dominators, retained size, paths to roots and comparisons. [HdumpPath](recipes-heap.md) for a narrow single-dump query or heuristic. A histogram is **shallow**; an allocation site is not a **retaining** path, and one dump cannot prove ongoing growth. |
| Fallback or post-mortem | `jps` discovers local JVMs but its arguments can expose secrets; prefer owner-supplied PID. Prefer `jcmd` to experimental `jmap`/`jstack`/`jinfo` when both can answer the question. `jhsdb` is for specialist core-dump work with matching JDK binaries and OS support, not routine live diagnostics; live use can hang or crash the target. |

Reference: [JDK 25 `jcmd`](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jcmd.html), [`jfr`](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jfr.html), [`jstat`](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jstat.html). Use [setup](setup.md) to verify installed components; no resident server is needed.
