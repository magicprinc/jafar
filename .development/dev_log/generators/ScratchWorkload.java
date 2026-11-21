public class ScratchWorkload {
  static long compute(int n) {
    long s = 0;
    for (int i = 0; i < n; i++) { s += (long) i * i; }
    return s;
  }
  static byte[] allocate(int size) { return new byte[size]; }
  public static void main(String[] args) {
    long total = 0;
    for (int i = 0; i < args.length + 4000; i++) {
      total += compute(200_000);
      byte[] b = allocate(4096);
      b[0] = (byte) total;
    }
    for (int i = 0; i < 64; i++) { total += allocate(64 * 1024).length; }
    System.out.println("total=" + total);
  }
}
