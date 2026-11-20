# Bench contract: what `Variants.java` must look like

`jfr-bench.ps1` copies your file into a scratch directory, compiles it next to a fixed harness and
runs **one JVM per variant per round**, so the JIT never profiles two candidates in the same
process.

## Shape

```java
public final class Variants {          // default package, exactly this class name

	private static int[] data;         // shared input, built once

	public static void setup() {       // optional; runs once per JVM before warmup
		data = new int[200];
		for (int i = 0; i < data.length; i++) data[i] = i;
	}

	public static long concat() {      // one method per candidate
		String text = "";
		for (int value : data) text += value;
		return text.length();          // must consume the result
	}

	public static long builder() {
		StringBuilder text = new StringBuilder(data.length * 3);
		for (int value : data) text.append(value);
		return text.length();
	}
}
```

Rules:

- `public static long <name>()` - no parameters, returns a value the harness folds into a volatile
  sink. Returning `void` is not supported on purpose: an unused result lets the JIT delete the work.
- `setup()` is optional and is **not** measured; do all I/O, parsing and fixture building there.
- Keep the measured method free of console output, logging and blocking I/O.
- Both variants must compute the same answer. Correctness first: if they disagree, the timing is
  meaningless.

## What the harness does

1. Warmup: 1.5 s of continuous calls (interpreter, C1 and C2 all compile the loop).
2. Calibration: 10 000 calls estimate ns/op, then the block size is chosen so one block lasts
   ~150 ms. Fast methods get many iterations, slow ones fewer.
3. Measurement: 7 blocks per JVM. Each block records wall time per operation
   (`System.nanoTime`) and bytes allocated by the measuring thread
   (`ThreadMXBean.getCurrentThreadAllocatedBytes` - exact, not sampled).
4. Rounds: the script repeats the whole thing `-Runs` times (default 3) with a fresh JVM per variant,
   alternating the order so machine drift penalises every candidate.

## Reading the table

```
| variant | median ns/op | best | worst | bytes/op | vs fastest | verdict |
| builder |        658.4 | 655.4| 691.1 |      616 |      1.00x | fastest |
| concat  |      6 752.9 |6570.7|6758.7 |   51 960 |     10.26x | slower  |
```

- `median ns/op` is the number to quote; `best`/`worst` are the per-round medians.
- `bytes/op` explains GC pressure and often the whole difference.
- `within-noise` means the worst round of one variant beats the best round of the other side is
  **false** - the ranges overlap. With overlapping ranges you may not claim a winner; add rounds or
  quiet the machine.
- With `-Jfr` each round gets a recording. Explain a measured difference using [jvmlens](local-analysis.md) per side; for a focused paired JFR comparison use [Jafar `jfr_compare`](expert-tools.md), without opening persistent sessions.

## Limits

- Microbenchmarks measure a method in isolation, not the application. A 2x win here is a hint, not
  a promise; confirm in the real workload.
- JIT compilation happens on the measuring thread; the warmup is time-based, not iteration-based,
  so a very slow variant may still be compiling when measurement starts. Prefer variants that run
  at least a few thousand times per block.
- Do not compare numbers across machines, JDKs or JVM flag sets.
