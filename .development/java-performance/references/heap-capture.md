# Heap dump capture and JVM memory triage (JDK 25)

Everything here was executed on JDK 25.0.5 with Jafar 0.29.0 and a purpose-built leaky JVM
(`C:\TEMP\heap-lab\Leak.java`: a static `HashMap` of 64 KiB arrays, grown in two phases).
Analysis of the resulting dumps lives in `recipes-heap.md`.

## Decide before you capture

- One dump answers "what is reachable **now**". A leak claim needs two dumps taken under the same
  workload and settings; see the checkpoint protocol in `recipes-heap.md` §5.
- Cost is a pause plus disk roughly equal to the live set: 30 MB live → 35 MB dump,
  55 MB live → 61 MB dump, and `-Xmx64m` OOME → 40 MB dump.
- The file is a copy of application data. Treat it as sensitive before you create it, not after.

## Verify the target, never the executable name

```powershell
jps -mlvV     # pid, main class and JVM flags of every JVM
$targetPid = 27692
Get-CimInstance Win32_Process -Filter "ProcessId=$targetPid" |
	Select-Object ProcessId, CreationDate, CommandLine
jcmd $targetPid VM.command_line
jcmd $targetPid GC.heap_info     # used/committed before the dump
```

Verified: `jcmd <pid> VM.command_line` returns the full command line, and `GC.heap_info` reports
`garbage-first heap total reserved 262144K, committed 262144K, used 30931K` plus region counts.

**Never `jcmd 0`.** `jcmd -h` states: "If the pid is 0, commands will be sent to all Java processes."

## `GC.heap_dump` options (from `jcmd <pid> help GC.heap_dump`)

| Form | Effect (JDK 25) | When |
|---|---|---|
| `GC.heap_dump <file>` | Requests a **full GC**, dumps reachable objects only | Default for retention work |
| `GC.heap_dump -all <file>` | Dumps **all** objects including unreachable, no full GC request | Only when unreachable garbage is the question |
| `GC.heap_dump -gz=1 <file>` | Gzip, 1 fastest … 9 strongest | Only with a reader for it — see below |
| `GC.heap_dump -overwrite <file>` | Allows replacing an existing file (default **false**) | Re-runs to a fixed path |
| `GC.heap_dump -parallel=<n> <file>` | Parallel dump threads | Large heaps, if the pause matters |

Measured: `-gz=1` cut 35 317 521 B to 3 035 635 B (11.6x), and the lab dump 7.90 MB → 2.56 MB.
Neither tool reads the compressed file directly — `hdump_open` fails with `Invalid HPROF format:
\u001F...` (gzip magic) and MAT 1.17 hangs or exits 13. **Decompress it first** and hand the plain
`.hprof` to either tool — verified: 2.56 MB → 7.90 MB in 37 ms, MAT then ran a suspects report in
3.6 s (exit 0) and Jafar opened it (67 671 objects):

```powershell
function Expand-HprofGz([string]$Gz, [string]$Hprof) {
	$in = [IO.File]::OpenRead($Gz); $out = [IO.File]::Create($Hprof)
	try {
		$z = New-Object IO.Compression.GZipStream($in, [IO.Compression.CompressionMode]::Decompress)
		try { $z.CopyTo($out) } finally { $z.Dispose() }
	} finally { $out.Dispose(); $in.Dispose() }
	$fs = [IO.File]::OpenRead($Hprof)
	try { $buf = New-Object byte[] 14; [void]$fs.Read($buf, 0, 14) } finally { $fs.Dispose() }
	if ([Text.Encoding]::ASCII.GetString($buf) -notlike 'JAVA PROFILE*') { throw "not an HPROF file: $Hprof" }
}
Expand-HprofGz 'C:\dumps\heap.hprof.gz' 'C:\dumps\heap.hprof'
```

Budget the disk: the decompressed file is the full size, so `-gz` saves transfer/storage, not the
peak footprint during analysis. Capturing plain remains the simpler default.

## The trap: jcmd exit code is not evidence

Verified: dumping to an existing path printed `Unable to create ...: File exists` and **jcmd still
exited 0**. A `$LASTEXITCODE`-only guard — the obvious one — passes while producing nothing.
Validate the artifact itself:

```powershell
# Out of the project on purpose: a dump must never land in a repository worktree.
$dir = Join-Path 'D:\TEMP\heap' "heap-$targetPid-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
[void][IO.Directory]::CreateDirectory($dir)
$out = Join-Path $dir "heap-$targetPid-$(Get-Date -Format 'yyyyMMdd-HHmmss').hprof"
$before = Get-Date
& jcmd $targetPid GC.heap_dump $out
$code = $LASTEXITCODE
$fi = Get-Item -LiteralPath $out -ErrorAction SilentlyContinue
if (-not $fi) { throw "heap dump missing: $out (jcmd exit $code)" }
if ($fi.LastWriteTime -lt $before) { throw "heap dump not rewritten: $out (jcmd exit $code)" }
if ((Get-Content -LiteralPath $out -TotalCount 1 -Encoding ascii) -notlike 'JAVA PROFILE*') {
	throw "not an HPROF file: $out"
}
'{0} MiB, jcmd exit {1}' -f [math]::Round($fi.Length / 1MB, 2), $code
```

`scripts/jfr-capture.ps1 -Artifact heap` already applies two of the three checks (unique run
directory, `JAVA PROFILE` header) and records `artifactVerdict` in its manifest.

## A look without a dump

```powershell
jcmd $targetPid GC.class_histogram | Select-Object -First 12
```

Verified output shape — `num`, `#instances`, `#bytes`, class name:

```
   1:         18024       27154448  [B (java.base@25.0.5)
   2:          3489         449248  java.lang.Class
```

Shallow sizes only: no retained size, no GC-root paths, no allocation site. It is a two-second
sanity check ("is the population even here?"), not an analysis.

## OOME: capture before the incident

```powershell
java -Xmx2g -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=C:\jvm-dumps -jar app.jar
```

Verified: `HeapDumpPath` may be a **directory**, in which case the file is auto-named
`java_pid<pid>.hprof` (40 058 067 B from a `-Xmx64m` heap); the process then died with exit 1.
It covers JVM heap OOME only — not native exhaustion, not other crash paths. Check free disk,
retention/cleanup policy and permissions before enabling it, and never let the directory grow
unbounded (one dump per incident, not per hour).

## Native memory when the heap is flat

If RSS or private bytes grow while `GC.heap_info` stays stable, the dump will not explain it.
This needs a flag at startup, so it costs a restart:

```powershell
# start with -XX:NativeMemoryTracking=summary
jcmd $targetPid VM.native_memory baseline
# ... run the representative workload ...
jcmd $targetPid VM.native_memory summary.diff
```

Verified mechanics on 25.0.5: `baseline` → `Baseline taken`; `summary.diff` → category table
("Omitting categories weighting less than 1KB"). NMT tracks HotSpot's own native allocations, not
arbitrary third-party JNI libraries, and it adds overhead — do not switch it on casually.

## Privacy of a dump (and of MCP results)

- Strings, ids, tokens, messages and payload fragments survive in `.hprof`. Keep dumps local, never
  commit them, never upload them to an online analyzer, never attach them to a ticket, delete them
  when the investigation ends.
- **MCP boundary:** an on-device server does not make its *answers* local — tool results enter the
  model context. Query counts, class names, sizes and reference graphs first; reach for `find_strings`,
  raw array dumps and broad field inspection only when the question needs them and the owner agreed.
- Jafar prints sizes with a comma decimal separator (`"heapSize": "29,5 MB"`) — parse with care, or
  use `objectCount`/`shallowSize` numbers instead.

## Eclipse MAT: installed, and when to use it

Start with [MAT batch/OQL](mat-oql.md) for retention, comparison, or a large dump. Preflight the uncompressed HPROF header, use a private workspace, and check the generated payload, not only the exit status. The [on-demand Jafar launcher](expert-tools.md) is for specific HdumpPath questions or heuristics on a small, local dump; it needs no session mount.
