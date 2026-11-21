import java.nio.file.Path;
import java.time.Duration;
import jdk.jfr.Recording;

/** Synthetic JDK 25 JFR fixture generator: one CPU-heavy method, no external inputs. */
public final class SyntheticCpu {
  private static volatile long sink;

  public static void main(String[] args) throws Exception {
    Path output = Path.of(args[0]);
    try (Recording recording = new Recording()) {
      recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
      recording.start();
      long deadline = System.nanoTime() + 3_000_000_000L;
      int calls = 0;
      while (System.nanoTime() < deadline) {
        sink = burnSyntheticCpu(200_000);
        calls++;
      }
      recording.stop();
      recording.dump(output);
      System.out.println("calls=" + calls + " sink=" + sink);
    }
  }

  /** Distinct named method for the positive CPU smoke check. */
  static long burnSyntheticCpu(int iterations) {
    long value = 0x9E3779B97F4A7C15L;
    for (int i = 0; i < iterations; i++) {
      value = (value * 6364136223846793005L + 1442695040888963407L) ^ (value >>> 17);
      value += Integer.rotateLeft(i, 7);
    }
    return value;
  }
}
