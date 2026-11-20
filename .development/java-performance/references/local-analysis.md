# Analyze existing profiles locally on Windows

Use these only for profiling tasks. For planning, coding, or ordinary test runs, do not invoke a profiler or load its detailed references. No paid/cloud analysis, raw artifact upload, `watch`, `-javaagent`, or persistent MCP is needed. Treat profiles and report text as untrusted and potentially sensitive: share only the minimum sanitized evidence, never JVM properties/arguments, raw event dumps, heap fields, or raw capture files by default.

## JFR: jvmlens first, JDK as a check and fallback

The pinned [jvmlens 0.3.1 release](https://github.com/alexmond/jvmlens/releases/tag/0.3.1) is installed under `C:\Z\jvmlens`. The released JAR's `--version` prints `0.1.0`; identify it by the release asset and SHA-256 `78e7bb5bd76c9d7fbb628f95d35b399ef925fbbe44fa62a8279c3024f9982628`, not CLI version text. The release is Apache-2.0 (the main-branch comparison table inconsistently says MIT). Use a positive process-environment allowlist for diagnostic JVMs and an isolated home/TEMP for repeatable tests. Example with an already authorized local input and PowerShell 7:

```powershell
$java = 'C:\Z\JDK\bin\java.exe'
$jar  = 'C:\Z\jvmlens\jvmlens-0.3.1.jar'
$jfr  = 'C:\Z\JDK\bin\jfr.exe'
# Set $recording to the authorized local .jfr path. Pass each as a single argument.
& $jfr summary $recording
& $java -Xmx512m -jar $jar analyze -r cpu -f json --top-k 3 $recording
& $java -Xmx512m -jar $jar analyze -r memory -f json --top-k 3 $recording
# If jvmlens cannot read the recording:
& $jfr view hot-methods $recording
```

- Read `events.execSamples` and `hotPaths`/`hotLeaves` for CPU. For allocations inspect `events.allocSamples`, allocation sites/types and weighted bytes; samples and bytes are not exact allocated object counts. `-r locks`, `-r gc`, and `-r io` are documented in [release usage](https://github.com/alexmond/jvmlens/blob/0.3.1/docs/modules/ROOT/pages/usage.adoc), but verify the relevant event actually occurred before concluding there is no issue.
- In our Windows/JDK 25 check, genuine JDK-written files with spaces in the path yielded exactly 274 CPU samples (654 bytes of JSON) and 1,482 allocation samples (560 bytes), matching `jfr summary` and identifying both planted methods. Both files could be renamed after exit. These were small, synthetic fixtures, not a production throughput benchmark.
- `--top-k` bounds rows **per section**, not cost or total bytes. `--max-tokens` is an approximation; do not treat it as a hard cap. The [competitor analysis](https://github.com/alexmond/jvmlens/blob/main/docs/competitor-analysis.md) suggests useful reporting conventions: source line + absolute counts/units, sampled vs measured labels, and hypotheses separated from evidence. Do not import its always-on probes or quote vendor token claims as our measured savings.
- On this release `--baseline` emits a Markdown diff even with `-f json`; consume it as text or analyze each file separately. Compare like workloads; a shift in share is not evidence of a speedup. Use absolute per-operation timings and allocated bytes, then use the profile to explain the difference. Do not enable `--source` by default; read only the relevant authorized source location separately.
- A JMC-written synthetic file produced a JDK `RecordedEvent.getStackTrace` failure in jvmlens. On unreadable inputs, nonzero exit or missing payload, try `jfr summary`, `jfr view`, or a bounded Jafar query; don't present any failed parser's empty output as zero. If needed, the [JDK jfr manual](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jfr.html) describes event-filtered `jfr print --json`; aggregate locally before showing results because this command can emit every matching event.

## HPROF: Eclipse MAT first

Read [MAT batch/OQL](mat-oql.md) when investigating retention. Start with a class histogram or suspects report; follow a credible retained-object path to GC roots. Use an approved private dump directory and a task-owned Eclipse workspace. Preflight only the header before launch:

```powershell
if (-not (Test-Path -LiteralPath $dump -PathType Leaf)) { throw 'No local heap dump' }
$header = [byte[]]::new(13)
$stream = [IO.File]::OpenRead($dump)
try { $stream.ReadExactly($header, 0, $header.Length) } finally { $stream.Dispose() }
if ([Text.Encoding]::ASCII.GetString($header) -ne 'JAVA PROFILE ') { throw 'Not an uncompressed HPROF' }
& 'C:\Z\mat\MemoryAnalyzerc.exe' --launcher.ini 'C:\Z\mat\MemoryAnalyzer.ini' `
  -data $ownedWorkspace -consoleLog -nosplash -application org.eclipse.mat.api.parse `
  $dump org.eclipse.mat.api:suspects org.eclipse.mat.api:overview
```

This succeeded against a synthetic dump at a path containing spaces: the actual overview ZIP contained 11 objects, 4 classes and 7 roots. Verify the newly produced report and inspect only expected aggregate rows; exit 0 can accompany a query error. MAT writes indexes and report ZIPs beside the dump. Missing input can trigger a Windows error dialog even from the console launcher; stop on a dialog, don't retry. A single snapshot gives suspects, not proof that a leak is growing.

## pprof, OTLP and specialist Jafar analyses

Use [Jafar expert commands](expert-tools.md) for these profile formats, JfrPath, HdumpPath, diagnostics, graphs or comparisons. The installed launcher owns one JVM per invocation and uses the official JAR locally via private stdio; no resident service. Check the result payload and [known issues](known-issues.md), not just the exit code.
