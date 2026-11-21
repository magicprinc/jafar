# Eclipse MAT 1.17: batch reports and OQL (verified on this machine)

MAT is installed at `C:\Z\mat` — a standalone Eclipse RCP application, **not** a project dependency.
Installed build **`1.17.0.202606011933`** (the 1.17.0 release), unpacked from the official
`MemoryAnalyzer-1.17.0.20260601-win32.win32.x86_64.zip`; the previous build `1.17.0.202604061449`
is kept at `C:\Z\mat-1.17.0.202604061449` for rollback. Update site:
<https://download.eclipse.org/mat/1.17.0/update-site/>. Minimum Java to run it: 21.

How the download was verified — Eclipse publishes no zip-level checksum URL, so each MAT artifact in
the archive was hashed and compared with `download.checksum.sha-256` from the official
`update-site/artifacts.jar`: 18 comparable jars matched, 0 differed (the remaining entries are
unpacked `features/` directories or artifacts the RCP does not ship, e.g. `org.eclipse.mat.jdt`).
`MemoryAnalyzer.ini` sets `-Xmx26G`; the old log records a 73 076 221-object dump opening in 2 m 36 s.

Everything below was executed against a 35 MB HPROF of a JVM with a deliberate 26 MB retained map.

## Entry points

`ParseHeapDump.bat <dump> [report|option]*` is a thin wrapper around:

```
MemoryAnalyzerc.exe --launcher.ini C:\Z\mat\MemoryAnalyzer.ini -consoleLog -nosplash \
  -application org.eclipse.mat.api.parse <dump> [report|option]*
```

Call `MemoryAnalyzerc.exe` directly from PowerShell: it skips the `cmd.exe` layer, so the quoting rules stay one level deep. First verify the local file exists (`Test-Path -LiteralPath $dump -PathType Leaf`) and check only its HPROF header. Missing or invalid inputs can display a Windows dialog even from the console launcher. Never use `ReadAllBytes` to validate a heap file.

## Reports (verified: 9.3 s, 35 MB dump)

```powershell
$mat = 'C:\Z\mat\MemoryAnalyzerc.exe'
# Set $ownedWorkspace to a task-owned directory and preflight $dump first.
$matArgs = @('--launcher.ini', 'C:\Z\mat\MemoryAnalyzer.ini', '-data', $ownedWorkspace,
             '-consoleLog', '-nosplash', '-application', 'org.eclipse.mat.api.parse')
& $mat @matArgs $dump org.eclipse.mat.api:suspects org.eclipse.mat.api:overview
# -> $dump_Leak_Suspects.zip  (98 873 B)
# -> $dump_System_Overview.zip (69 784 B)
# -> $dump.*.index files (~3.8 MB) written next to the dump
```

| Goal | Arguments |
|---|---|
| Leak suspects | `org.eclipse.mat.api:suspects` |
| Overview (histogram, top consumers, threads) | `org.eclipse.mat.api:overview` |
| Per-component memory | `org.eclipse.mat.api:top_components` |
| Compare two dumps | `$before ('-snapshot2="' + $after + '"') org.eclipse.mat.api:compare` |
| Leak comparison (upstream syntax; not exercised here) | `$after ('-baseline="' + $before + '"') org.eclipse.mat.api:suspects2` |
| Text output | add `-format=txt` (also `csv`), `-unzip`, `-limit=<n>` |
| Keep garbage | `-keep_unreachable_objects` |

For a comparison, preflight **both** plain HPROFs and use fresh report locations. Pass the second
path with literal quotes inside the parameter; quoting just the PowerShell expression is not enough
when MAT builds the nested histogram query. This PowerShell 7 form preserves the quotes:

```powershell
$second = '-snapshot2="' + $after + '"'
& $mat @matArgs $before $second org.eclipse.mat.api:compare
```

Inspect the fresh `*_Comparison.zip`: check the HTML and the CSV rows, and treat `Error opening
heap dump` or `Problem reported` as failure even with exit 0. Verify the sign before saying
"growth": on MAT 1.17, this exact invocation with five `agent.Probe` objects in `$before` and six in
`$after` reported `-1`, i.e. first minus second. Comparable workload/GC checkpoints matter more than
clock time alone. A suspects report is a **lead**, not a verdict: inspect dominators and the relevant
paths to GC roots, identify the owning reference and distinguish expected lifetime from growth.

## Query report: histogram and OQL

`-command=<query>` runs one query and writes `<dump>_Query.zip`. Verified:

```powershell
& $mat @matArgs $dump '-command=histogram' 'org.eclipse.mat.api:query'
```

For OQL the **entire command must be a single argument**, with the statement in literal double
quotes inside it:

```powershell
$oql = '-command=oql "SELECT * FROM java.util.HashMap h"'
& $mat @matArgs $dump $oql '-limit=5' 'org.eclipse.mat.api:query'
```

Verified result — 362 matching objects, 5 rows kept by `-limit`:

```
Class Name            Shallow Heap   Retained Heap
java.util.HashMap @ 0xf092e6d0   48        48
...
Total: 5 of 362 entries; 357 more
```

Passing `-command=oql` and the statement as **separate** arguments is the natural mistake and it
fails quietly: MAT receives `select * from` and the report contains
`Problem reported: Encountered ... at line 1, column 15`.

## Trap: MAT exits 0 even when the OQL did not parse

Verified twice: the process returns `0` and writes a full report whose `index.html` carries
`Problem reported: Encountered "<token>" ... Was expecting one of: ...`. A green exit code proves
only that Eclipse started, not that the query ran. Always search the report before believing a number:

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [IO.Compression.ZipFile]::OpenRead("$dump`_Query.zip")
$e = $z.Entries | Where-Object { $_.FullName -eq 'index.html' }
$sr = New-Object IO.StreamReader($e.Open()); $html = $sr.ReadToEnd(); $sr.Close(); $z.Dispose()
if ($html -match 'Problem reported') { throw 'OQL failed - see the report' }
```

## OQL forms verified here

| Query | Behaviour |
|---|---|
| `SELECT * FROM java.util.HashMap h` | One row per object: class, shallow heap, retained heap |
| `SELECT h.size FROM java.util.HashMap h` | Per-object field projection |
| `SELECT h.@retainedHeapSize FROM java.util.HashMap h` | Per-object retained size as a column |
| `SELECT * FROM java.util.HashMap h WHERE h.size > 700` | Field predicate → 1 row here |
| `SELECT count(*) FROM java.util.HashMap h` | **Parse error** — aggregates are not OQL |

Attributes start with `@` (`@objectId`, `@retainedHeapSize`, …). There is no `ORDER BY` and no
aggregate in the batch report; rank objects with Jafar `top(n, retained)` and use OQL for the
predicate that needs a real expression. OQL reference: <https://help.eclipse.org/latest/topic/org.eclipse.mat.ui.help/reference/oql.html>

## Compressed dumps: decompress, then analyse

MAT does not open JDK-produced `*.hprof.gz` directly — this build hangs and a clean retry exits 13
with no report. Unpack it first; then everything behaves normally (verified: 2.56 MB → 7.90 MB in
37 ms, suspects report in 3.6 s, exit 0, and Jafar opens the same file):

```powershell
function Expand-HprofGz([string]$Gz, [string]$Hprof) {
	$in = [IO.File]::OpenRead($Gz); $out = [IO.File]::Create($Hprof)
	try {
		$z = New-Object IO.Compression.GZipStream($in, [IO.Compression.CompressionMode]::Decompress)
		try { $z.CopyTo($out) } finally { $z.Dispose() }
	} finally { $out.Dispose(); $in.Dispose() }
}
Expand-HprofGz 'C:\dumps\heap.hprof.gz' 'C:\dumps\heap.hprof'   # then pass heap.hprof below
```

Mind the disk: the unpacked dump is the full size, so compressed dumps save storage and transfer,
not peak space during analysis.

## Engine split: Jafar vs MAT

- **MAT first**: histogram, dominators, GC roots, OQL, comparative reports and multi-GB dumps.
- **On-demand Jafar**: [small single-dump heuristics and HdumpPath](expert-tools.md), after MAT if needed. The launcher does not expose cross-session heap joins. For growth, use comparable MAT snapshots; class-count growth is not a retained-path finding.
- Never mix numbers from both in one conclusion without saying which produced them.

## Cost and privacy

- MAT writes `.index` files and report zips next to the dump: the protected folder grows, and the
  indexes contain object data. Clean them with the dump.
- Pass `-data $ownedWorkspace` to use a task-owned workspace; its log is `.metadata\.log`. MAT may also write installation configuration logs.
- OQL can return object field values into the report and then into the model context; same privacy
  rules as the dump itself: prefer counts, class names and sizes.
