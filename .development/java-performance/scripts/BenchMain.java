import com.sun.management.ThreadMXBean;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.management.ManagementFactory;
import java.util.Locale;

/// Measurement loop for `jfr-bench.ps1`. One JVM measures exactly one variant, so the
/// call site stays monomorphic and the JIT can inline the variant into the loop.
///
/// Contract with the user-supplied `Variants` class (same directory, default package):
/// - any number of `public static long <name>()` methods, one per candidate; the method
///   returns a value that is folded into a volatile sink so the JIT cannot delete it;
/// - optional `public static void setup()`, called once before warmup (build input data there).
///
/// The harness reports wall time per operation and bytes allocated by the measuring thread.
/// Bytes are exact (thread-local counter), time is sampled per block, not per operation.
public final class BenchMain {

	/// Number of invocations per warmup chunk; small enough to keep the deadline responsive.
	private static final int WARMUP_CHUNK = 4096;

	/// Iteration count used to estimate per-operation cost before sizing the measured blocks.
	private static final int CALIBRATION_ITERS = 10_000;

	private static volatile long sink;

	private BenchMain() {
	}

	public static void main(String[] args) throws Throwable {
		String variant = null;
		long warmupMs = 1_500;
		long blockMs = 150;
		int blocks = 7;
		for (int i = 0; i < args.length; i++) {
			switch (args[i]) {
				case "--variant" -> variant = args[++i];
				case "--warmup-ms" -> warmupMs = Long.parseLong(args[++i]);
				case "--block-ms" -> blockMs = Long.parseLong(args[++i]);
				case "--blocks" -> blocks = Integer.parseInt(args[++i]);
				default -> throw new IllegalArgumentException("unknown argument: " + args[i]);
			}
		}
		if (variant == null) {
			throw new IllegalArgumentException("--variant <methodName> is required");
		}

		MethodHandle target = resolve(variant);
		try {
			MethodHandles.publicLookup()
				.findStatic(Variants.class, "setup", MethodType.methodType(void.class))
				.invokeExact();
		} catch (NoSuchMethodException noSetup) {
			// setup() is optional: without it the variant must not need prepared input.
		}

		ThreadMXBean threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();

		// Warmup is time-bounded: interpreter, C1 and C2 all get a chance to compile the loop.
		long warmupDeadline = System.nanoTime() + warmupMs * 1_000_000L;
		while (System.nanoTime() < warmupDeadline) {
			for (int i = 0; i < WARMUP_CHUNK; i++) {
				sink += (long) target.invokeExact();
			}
		}

		// Calibrate: choose an iteration count so one block lasts about blockMs, which keeps
		// timer resolution and scheduler noise small relative to the measured work.
		long calibStart = System.nanoTime();
		for (int i = 0; i < CALIBRATION_ITERS; i++) {
			sink += (long) target.invokeExact();
		}
		long calibNanos = System.nanoTime() - calibStart;
		double nsPerOp = Math.max(calibNanos / (double) CALIBRATION_ITERS, 0.1);
		long iters = clamp((long) (blockMs * 1_000_000L / nsPerOp), 1_000L, 50_000_000L);

		for (int block = 0; block < blocks; block++) {
			long allocBefore = threads.getCurrentThreadAllocatedBytes();
			long start = System.nanoTime();
			for (long i = 0; i < iters; i++) {
				sink += (long) target.invokeExact();
			}
			long elapsed = System.nanoTime() - start;
			long allocated = threads.getCurrentThreadAllocatedBytes() - allocBefore;
			// Locale.ROOT keeps the decimal separator a dot: the consumer parses JSON, and a
			// ru_RU default locale would otherwise emit commas.
			System.out.printf(
				Locale.ROOT,
				"{\"variant\":\"%s\",\"block\":%d,\"iters\":%d,\"nsPerOp\":%.4f,\"bytesPerOp\":%.2f}%n",
				variant, block, iters, elapsed / (double) iters, allocated / (double) iters);
		}
	}

	private static MethodHandle resolve(String variant) throws NoSuchMethodException, IllegalAccessException {
		return MethodHandles.publicLookup()
			.findStatic(Variants.class, variant, MethodType.methodType(long.class));
	}

	private static long clamp(long value, long min, long max) {
		return Math.max(min, Math.min(max, value));
	}
}
