# Heap dump recipes (HPROF, Jafar 0.29.0)

Prefer [MAT](mat-oql.md) for retention and comparisons. Use these HdumpPath recipes for selected heuristics on a small dump through [the expert launcher](expert-tools.md): `python -I $jf hdump $dump --query '<query>'` or `--tool hdump_report --args '{"focus":"leaks,waste"}'`. `tool(args)` below is historical tool notation: omit explicit opens, closes and session IDs. Cross-session heap joins are not exposed; use MAT's comparison workflow. The original recipes were exercised on a 35 MiB service dump; this release wrapper was tested with a synthetic dump.

## 0. Open and orient

```
hdump_open(path="C:\runs\heap-live.hprof", alias="live")
  -> { objectCount: 108655, classCount: 2926, heapSize: "31,4 MB" }
hdump_summary(sessionId="live")
hdump_report(sessionId="live", focus="leaks,waste")
```

`hdump_report` returns severity-ranked `findings[]` (CRITICAL / WARNING / INFO), each with a ready
`suggested query`, plus a text/markdown `report` body. It triggers retained-size computation, so on
a multi-GB heap it is slow - narrow it with `focus` first.

## 1. What actually holds the memory

```
objects | top(5, retained)
```

Verified output: objects with `className`, `shallowSize`, `retainedSize` and their fields, e.g.
`java.util.HashMap{size=159, table=HashMap$Node[]}` with `retainedSize: 1167344`. Retained size is
approximate but decisive for ranking.

**Trap:** a predicate on the computed field fails. `objects[retained > 1MB] | top(5, retained)`
returns `Expected operator at position 17`. Sort with `top(n, retained)` instead and filter
afterwards in your own code.

## 2. Which classes are numerous or fat

```
classes | sortBy(instanceCount desc) | top(5, by=instanceCount)
classes | sortBy(instanceSize desc) | top(5, by=instanceSize)
objects/byte[] | stats(shallow)
objects/java.lang.String | top(10, shallow)
```

Verified: `classes` rows carry `instanceCount` and `instanceSize`; `instanceSize` is 0 for arrays,
so use `stats(shallow)` on the array type itself. Real numbers from the lab dump: 28 386 `byte[]`
with `shallow_max: 65556` and `shallow_sum: 28 048 628` - that is the 400 x 64 KiB cache entries.

## 3. Leak and waste detectors

```
checkLeaks(detector="growing-collections")
checkLeaks(detector="duplicate-strings")
objects/java.util.HashMap | waste() | top(10, wastedBytes)
duplicates | sortBy(wastedBytes desc) | top(20)
```

Verified `checkLeaks(detector="growing-collections")`:

```
class java/util/HashMap, elementCount 400, retained 26 277 382,
suggestion "Review collection growth strategy. Consider size limits, eviction policies, or weak references."
```

That is the whole leak story in one row: element count, retained bytes, and the action. Detectors
are heuristics - a hit is a lead, not a verdict.

## 4. Who keeps the object alive

```
pathToRoot()                    # on a filtered object: objects/java.util.HashMap[...] | pathToRoot()
retentionPaths()                # merged class-level retention paths
dominators(groupBy="package", minRetained=1MB)
gcroots/THREAD_OBJ | dominatedSize() | sortBy(dominated desc)
```

These are documented by Jafar but were **not exercised** on this machine; treat the first result as
unverified and say so if you rely on it.

## 5. Two dumps = growth, not size

Use [MAT's comparison report](mat-oql.md#reports-verified-93-s-35-mb-dump), then check retained paths. Do not try `join(session=...)` through the single-dump launcher. The measurements below are historical evidence from two dumps of the same JVM 25 s apart, with 400 × 64 KiB arrays added in between:

| class | before | after | delta |
|---|---|---|---|
| `byte[]` | 18 024 | 18 426 | **+402** |
| `java.util.HashMap$Node` | 5 213 | 5 613 | **+400** |
| `java.lang.Integer` | 541 | 941 | **+400** |

`instanceCountDelta` is the number to read; `instanceSizeDelta` stays 0 for arrays because
`instanceSize` is 0 there (§7). For scale, `hdump_summary` moved 78 420 objects / 29,5 MB to
79 639 / 54,6 MB - object count barely moved while retained memory doubled, which is exactly why a
single dump cannot show a leak.

Compare dumps from the same JVM configuration and comparable workload checkpoints. Comparing post-GC and pre-GC snapshots can look like growth even without a leak.

## 6. Reading a dump honestly

- `heapSize` in `summary` is total shallow size of parsed objects; the file on disk is larger.
  It is also printed with a comma decimal separator (`"heapSize": "29,5 MB"`) - do not feed it to
  `[double]` or comparisons without parsing.
- Arrays: per-object shallow sizes exist, but `classes.instanceSize` is 0 for array classes.
- Retained sizes are approximate: they are good for ranking, not for accounting.
- The dump pauses the JVM (`jcmd GC.heap_dump` requests a full GC unless `-all`): on a 4 GB heap
  expect seconds of stop-the-world.
- `-gz=1` dumps must be **unpacked first**: `hdump_open` rejects the gzip container
  (`Invalid HPROF format: \u001F...`), while the decompressed `.hprof` opens normally (67 671 objects
  verified; MAT reads it too). Snippet: `heap-capture.md`.
- `jcmd GC.heap_dump` **exits 0 even when it writes nothing** (`Unable to create ...: File exists`).
  Validate size, mtime and the `JAVA PROFILE` header before analysing, or you will analyse the
  previous run's dump.
- A heap dump contains customer data (strings, ids, payloads). Keep it local, never commit it,
  and delete it when the investigation ends. MCP answers are not local either: start with counts,
  class names and sizes.
- The file stays locked on Windows after `hdump_close` until the server process releases the
  mapping (issue #227). `jcmd <server-pid> GC.run` releases heap dumps; recordings it does not.

## 7. Workarounds for 0.29.0 query defects (verified on a 4.1M-object heap)

| Intended query | What actually happens | Use instead |
|---|---|---|
| `objects/instanceof/java.util.Map \| count` | `0` - interfaces are never matched | `objects/instanceof/java.util.AbstractMap \| count` (67 728 on the same heap) |
| `objects/<type> \| groupBy(class) \| stats(shallow)` | `count: 0`, `min: Infinity`, `max: -Infinity` | `objects \| groupBy(className) \| top(n, by=count)` |
| `objects/instanceof/java.util.Map \| waste()` | empty (nothing matched) | `objects/java.util.HashMap \| waste() \| top(10, wastedBytes)` |
| `waste()` over subclasses | rows with all-null metrics | filter `wastedBytes != null` before sorting |

`hdump_report` prints several of the broken forms in its own `query` fields, so a report finding is
a hint, not a runnable command. Full table with upstream links: `known-issues.md`.

Sanity check before believing a negative result: if `count` is 0 for a type you expect, ask for an
abstract superclass and for the exact class. A genuinely absent class returns 0 for all three.
