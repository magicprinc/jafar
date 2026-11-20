# Local profiling: JDK `jfr` and Eclipse MAT

Local recipes for the JDK's `jfr` tool and Eclipse MAT. Prefer these for independent JFR checks and HPROF retention analysis. The `java-performance` skill routes agents to the installed tools and keeps detailed guidance out of routine work. The examples were exercised on synthetic local artifacts unless noted. For capabilities specific to Jafar see [Mcp.md](Mcp.md). Report which analyzer produced each result; do not treat different analyzers' counts or retention models as interchangeable.

## What is installed (verified 2026-11-20)

| Piece | Path | Version |
|---|---|---|
| JDK | `C:\Z\JDK` | `25.0.5.1` (BellSoft) |
| `jfr` tool | `C:\Z\JDK\bin\jfr.exe` | `25.0.5.1` |
| Eclipse MAT | `C:\Z\mat\MemoryAnalyzerc.exe` + `C:\Z\mat\MemoryAnalyzer.ini` | `1.17.0.202606011933` |

MAT ships no JRE: `JAVA_HOME`/`PATH` must resolve a JDK 21+ (25 was used here); its ini sets
`-Xmx26G`. The build id is the one in `.eclipseproduct` (`version=1.17.0`) and the installed
`org.eclipse.mat.ui.rcp.feature_1.17.0.202606011933` feature. If a path is absent, report the missing
tool instead of improvising or installing anything ([Verification.md](Verification.md), R3).

## Ground rules

- **Local artifacts only.** Do not attach to a JVM, run `GC.heap_dump`, or open a real dump/recording
  you were not given authorization for. Recordings and dumps contain strings, thread names, system
  properties and — under the default JDK settings — environment variables. Capture only what the
  question needs, keep files in task-owned scratch, and report aggregates rather than field dumps.
- **No changes to the project being profiled.** Keep profiler settings and captured artifacts in owned scratch. A JVM you start for a smoke test must receive a positive allowlist of environment variables; the stock JFR settings can record its environment and properties.
- **Exit status is not evidence** (R9). Three verified ways to get exit 0 with no usable result:
  `jfr print --events <unknown event>` prints nothing; `jfr view <view>` on a recording without that
  event prints `Missing event found`; MAT with a mis-quoted `-command=oql` writes a report whose
  `index.html` says `Problem reported`. Check the payload.
- **The generated test recordings are JMC-writer stand-ins.** `parser-core/src/test/resources/test-ap.jfr`
  (117,226 B at this revision) summarizes fine, but the JDK reader rejects its reference/thread-typed
  events: `jfr print` exits 1 with `ClassCastException: class java.lang.Long cannot be cast to class
  [Ljava.lang.Object[]`, and `jfr view hot-methods` exits 0 with `No events found` plus ~10 MB of the
  same stack trace on stderr. Use `jfr summary`, `jfr metadata` or `jfr view cpu-load` on it, use
  Jafar for it (the suites do), or use a real recording captured below for `jfr print`. The real
  170 MiB `test-ap.jfr` is only fetched by `get_resources.sh` — see
  [test-recordings/README.md](../../test-recordings/README.md).

## JDK `jfr` recipes

```powershell
$jfr = 'C:\Z\JDK\bin\jfr.exe'
$rec = 'C:\scratch\recording.jfr'
```

### 1. Summarize first — cheap and always available

```powershell
& $jfr summary $rec
```

Observed on a self-recorded 214 KB JFR: exit 0, 1 chunk, `Version: 2.1`, and an event table
(`jdk.ExecutionSample 16`, `jdk.ObjectAllocationSample 22`, …). 120–130 ms per call on this file
(cold process each time).

### 2. Inspect one event type's fields

```powershell
& $jfr metadata --events jdk.ExecutionSample $rec
```

Exit 0, prints the annotation/field layout (`sampledThread`, `stackTrace`, `startTime`, …). Useful
before writing a filter or a JfrPath query.

### 3. Human aggregates

```powershell
& $jfr view --width 140 hot-methods $rec
& $jfr view allocation-by-class $rec
& $jfr view events-by-count $rec
```

Observed: exit 0, 161–189 ms; `hot-methods` printed `ScratchWorkload.compute(int) 16 100,00%`;
`allocation-by-class` printed `byte[] 100,00%`. Numbers are locale-formatted (comma decimal
separator, percentages can wrap as `Percen t`) — do not parse these tables as machine numbers.

### 4. JSON for machine consumption

```powershell
& $jfr print --json --events jdk.ExecutionSample --stack-depth 4 $rec
```

Observed: exit 0, 57,147 B of JSON for 16 events, 141–161 ms. `ConvertFrom-Json` yields
`$doc.recording.events`; `sampledThread.javaName` is the thread and `stackTrace.frames` honors
`--stack-depth` (2 frames at depth 4 here because the test stack was shallow). Timestamps are
strings; `jdk.ObjectAllocationSample.weight` is an integer byte count. The whole selected event set
is materialized as one JSON document — bound it with `--events`/`--categories` and `--stack-depth`.

### 5. Capture from a JVM you start (never attach)

Both `default.jfc` and `profile.jfc` enable `jdk.InitialEnvironmentVariable` (verified, line 711), so
a plain recording captures the JVM's environment. Copy the settings with that event disabled:

```powershell
$safe = 'C:\scratch\default-no-env.jfc'
$raw = Get-Content 'C:\Z\JDK\lib\jfr\default.jfc' -Raw
[regex]::Replace($raw,
  '(<event name="jdk\.InitialEnvironmentVariable">\s*<setting name="enabled">)true(</setting>)',
  '${1}false${2}') | Set-Content -Path $safe -Encoding utf8

& 'C:\Z\JDK\bin\java.exe' `
  "-XX:StartFlightRecording=filename=C:\scratch\recording.jfr,settings=$safe,dumponexit=true" `
  -cp C:\scratch\classes ScratchWorkload
```

Observed: exit 0 after 488–768 ms, stderr empty, JFR startup lines on stdout, recording written on
exit; `jfr summary` then shows `Version: 2.1` and the events. `-XX:StartFlightRecording` is
documented in the [`java` launcher page](https://docs.oracle.com/en/java/javase/25/docs/specs/man/java.html);
subcommands in the [`jfr` man page](https://docs.oracle.com/en/java/javase/25/docs/specs/man/jfr.html).

### 6. Failure contract (verified)

| Input | Observed |
|---|---|
| `jfr summary <missing file>` | exit 1, message localized to the OS locale (Russian here) |
| `jfr view no-such-view` | exit 1, `Could not find a view or an event type named ...` |
| `jfr print --events jdk.NoSuchEvent` | **exit 0, empty output** — payload check required |
| `jfr view gc` / `system-information` without those events | exit 0, `Can't find event type ... Missing event found` |
| `jfr print` on the generated `test-ap.jfr` | exit 1, `ClassCastException` (see ground rules) |

## Eclipse MAT recipes

### Preflight — gate the input before launching

```powershell
$dump = 'C:\scratch\fixtures with spaces\synthetic heap.hprof'
if (-not (Test-Path -LiteralPath $dump -PathType Leaf)) { throw "no dump: $dump" }
$header = [byte[]]::new(13)
$stream = [IO.File]::OpenRead($dump)
try { $stream.ReadExactly($header, 0, $header.Length) } finally { $stream.Dispose() }
if ([Text.Encoding]::ASCII.GetString($header) -ne 'JAVA PROFILE ') {
  throw 'not an uncompressed HPROF file'
}
```

A missing dump still starts MAT, logs `java.io.FileNotFoundException`, writes no report and exits 13;
the console launcher can additionally surface a native error dialog even though the process exits, so
the `Test-Path -PathType Leaf` gate is what prevents that missing-input popup. Never assume MAT is
unattended-safe: run it where a dialog can be closed, and if an error dialog appears, stop and have it
identified and closed before launching again. Do not retry in a loop.

### Success argv — batch leak-suspects + overview reports (verified, exact)

```powershell
$mat = 'C:\Z\mat\MemoryAnalyzerc.exe'
$ini = 'C:\Z\mat\MemoryAnalyzer.ini'
$dump = 'C:\scratch\fixtures with spaces\synthetic heap.hprof'
& $mat --launcher.ini $ini -data 'C:\scratch\mat workspace' -consoleLog -nosplash `
  -application org.eclipse.mat.api.parse $dump `
  org.eclipse.mat.api:suspects org.eclipse.mat.api:overview
```

Direct result on a 1,607 B synthetic dump: **exit 0, 7.58 s**, stdout 13,737 B, stderr 66 B
(`WARNING: package jdk.internal.org.objectweb.asm not in java.base`). This is **not** a quiet mode:
Eclipse writes parse progress to stdout even with `-consoleLog`; redirect it if noisy, but never
treat an empty log or a zero exit as proof that a report was produced. See the
[MAT batch-mode documentation](https://help.eclipse.org/latest/topic/org.eclipse.mat.ui.help/tasks/batch.html).

Outputs are written next to the dump, with the `.hprof` extension replaced:

| File | Size observed |
|---|---|
| `synthetic heap_Leak_Suspects.zip` | 75,646 B |
| `synthetic heap_System_Overview.zip` | 60,290 B |
| `synthetic heap.index` and the `.idx`/`.o2c`/`.inbound`/`.domOut`… sidecars | 31–3,097 B each |

Expected payload — `_System_Overview.zip`'s `index.html`: `Number of objects 11`,
`Number of classes 4`, `Number of GC roots 7`, `Format hprof`, `File length 1.607`, and no
`Problem reported`.

### Query report (histogram)

```powershell
& $mat --launcher.ini $ini -data 'C:\scratch\mat workspace' -consoleLog -nosplash `
  -application org.eclipse.mat.api.parse $dump '-command=histogram' 'org.eclipse.mat.api:query'
```

Exit 0, 1.20 s (warm, index already built), writes `synthetic heap_Query.zip` (10,275 B). Payload:
`agent.Probe` 5 objects / 80 B shallow, `byte[]` 1 object / 1,048 B, `java.lang.Class` 4 objects,
total 4 entries / 11 objects.

### OQL — one argument, literal double quotes inside

```powershell
& $mat --launcher.ini $ini -data 'C:\scratch\mat workspace' -consoleLog -nosplash `
  -application org.eclipse.mat.api.parse $dump `
  '-command=oql "SELECT * FROM agent.Probe p"' '-limit=5' 'org.eclipse.mat.api:query'
```

Exit 0, 1.25 s, 5 rows `agent.Probe @ 0x3e8` … `0x3ec`, shallow and retained 16 each. OQL reference:
[OQL syntax](https://help.eclipse.org/latest/topic/org.eclipse.mat.ui.help/reference/oqlsyntax.html).
There is no aggregate in batch OQL: `SELECT count(*) FROM ...` is a parse error.

### Verify the report, not the exit code

```powershell
$base = [IO.Path]::Combine([IO.Path]::GetDirectoryName($dump),
                           [IO.Path]::GetFileNameWithoutExtension($dump))
$report = "$base`_System_Overview.zip"   # or _Query.zip, _Leak_Suspects.zip
if (-not (Test-Path -LiteralPath $report -PathType Leaf)) { throw "MAT wrote no report: $report" }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::OpenRead($report)
try {
  $entry = $z.GetEntry('index.html')
  $reader = New-Object IO.StreamReader($entry.Open())
  try { $html = $reader.ReadToEnd() } finally { $reader.Dispose() }
} finally { $z.Dispose() }
if ($html -match 'Problem reported') { throw "MAT report contains a problem: $report" }
if ($html -notmatch 'Number of objects') { throw "unexpected report payload: $report" }  # match expected row
```

Verified to pass on the overview report (11 objects / 4 classes / 7 roots) and to throw on a
mis-quoted OQL report. A suspects report is a lead, not a verdict.

### Failure and trap contract (verified)

| Case | Observed |
|---|---|
| dump path missing | preflight guard; without it: exit 13, no report, `FileNotFoundException` in the config log, possible error dialog |
| non-HPROF (a `.jfr` passed as dump) | exit 13, no report |
| `-command=oql` and statement as **separate** arguments | **exit 0**, `_Query.zip` written, `index.html` says `Problem reported: Encountered ...`, 0 rows |
| `SELECT count(*) ...` in batch OQL | **exit 0**, `Problem reported` — aggregates are not OQL |
| `-limit=<n>` | accepted; this fixture had exactly 5 matching rows, so truncation was not exercised |

### Config and workspace writes

- `-data '<scratch>\mat workspace'` keeps the Eclipse workspace in task scratch. On the verified run
  the installed `C:\Z\mat\workspace` mtime stayed unchanged, and the scratch `home/`, `appdata/` and
  `localappdata/` directories stayed empty; `TEMP` got only JVM `hsperfdata`.
- `-data` does **not** redirect the installation's configuration area: MAT wrote three
  `C:\Z\mat\configuration\<epoch-ms>.log` session logs (1,924 B each) during these runs, and no other
  installed file changed (the ini was not modified). Do not delete or edit those logs without the
  owner; treat them as MAT's own state.
- The dump directory gains `.index` files and report zips. They contain object data: keep them with
  the dump, hand them to no one, and delete them together.
- One MAT process at a time (shared install/workspace). `MemoryAnalyzer.ini` sets `-Xmx26G` by hand;
  see `java-performance` `references/jvm-flags.md` before changing it.

## Synthetic HPROF fixtures (no `dumpHeap`)

For a shell-level MAT smoke test, do not dump a real JVM heap. Generate a synthetic dump instead:

- from a test, the repo generator is
  [SyntheticHeapDumpGenerator.java](../../hdump-parser/src/testFixtures/java/io/jafar/hdump/test/SyntheticHeapDumpGenerator.java)
  (suites A1–A7, B1–B4);
- from a shell, write the minimal HPROF 1.0.2 records yourself — header, `UTF8`, `LOAD_CLASS`,
  `HEAP_DUMP_SEGMENT` with `CLASS_DUMP`/`INSTANCE_DUMP`/`PRIM_ARRAY_DUMP`, `HEAP_DUMP_END`; the
  format is tracked in [hprof-verification-matrix.md](../hprof-verification-matrix.md) and
  [HprofReader.java](../../hdump-parser/src/main/java/io/jafar/hdump/internal/HprofReader.java). A
  1,607 B file with two classes, five instances, one `byte[1024]` and GC roots was enough for MAT to
  index and report (payload above).

Always gate the file with the magic check above before giving it to MAT.

## Cost, timeouts and limits

- `jfr` tool: summary ~0.13 s; `view`/`print --json` ~0.15–0.19 s on a 214 KB recording. Selection
  is per event: bound large recordings with `--events`/`--categories` and a small `--stack-depth`.
  The real 170 MiB `test-ap.jfr` was not present here, so large-file timings are **unverified**.
- MAT: first launch on a new dump ~7.6 s for two reports including the index build; a warm single
  query ~1.2 s; the negative runs 4.6–8.2 s. Use a >=120 s per-invocation timeout, run one at a time,
  and prefer a task-owned `-data` workspace. Index build and retained-size work grow with dump size;
  use the skill's multi-GB guidance rather than long ad-hoc runs.

## Bridge to the global `java-performance` skill

The canonical capture/benchmark/OQL guidance lives in the global skill at
`~/.agents/skills/java-performance/`; this page does not replace it:

- capture a test, Gradle/Maven run or authorized live JVM: `scripts/jfr-capture.ps1`;
- A/B snippets: `scripts/jfr-bench.ps1` and `references/bench-contract.md`;
- MAT batch/OQL details and the exit-0 trap: `references/mat-oql.md`;
- JFR query recipes and known defects: `references/recipes-jfr.md`, `references/known-issues.md`;
- heap-capture privacy and OOME flags: `references/heap-capture.md`; JVM flags:
  `references/jvm-flags.md`.

Skills supplement, not replace, [Verification.md](Verification.md).

## What was verified here

2026-11-20, Windows `ru-RU`, JDK `25.0.5.1` (BellSoft), `jfr` `25.0.5.1`, MAT `1.17.0.202606011933`.
The runs used task-owned scratch and fixture filenames containing spaces. Fixture digests (SHA-256): generated `test-ap.jfr` copy
`117,226 B / 61D7826C…94AE`; synthetic `synthetic heap.hprof` `1,607 B / 9B01D7A2…D0FE`;
self-recorded `recording long.jfr` `213,601 B / B5873AF4…5C7D`. The `jfr`, scratch-JVM and MAT
invocations in this receipt ran with a positively allowlisted child environment (only `PATH`,
`JAVA_HOME`, `TEMP`, `TMP`, `HOME`, `USERPROFILE`, `APPDATA`, `LOCALAPPDATA`, `SystemRoot`/`windir`,
`PATHEXT`, `OS`, `COMSPEC`, `PROCESSOR_ARCHITECTURE`, `NUMBER_OF_PROCESSORS`), so no ambient
credentials reached them; tool-version/help probes and `javac` ran directly.

Not verified: MAT GUI use, real or multi-GB heap dumps, `jfr` on the real 170 MiB recordings,
non-Windows hosts, MAT compare/redaction reports, `jfr scrub/assemble`, and concurrent MAT runs.
