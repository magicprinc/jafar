import java.nio.file.Path;
import jdk.jfr.Recording;

class SyntheticExceptions {
    public static void main(String[] args) throws Exception {
        try (Recording rec = new Recording()) {
            rec.enable("jdk.JavaExceptionThrow").withStackTrace();
            rec.start();
            for (int i = 0; i < 3; i++) {
                try { throw new IllegalStateException("synthetic"); }
                catch (IllegalStateException expected) { if (!"synthetic".equals(expected.getMessage())) throw expected; }
            }
            rec.stop();
            rec.dump(Path.of(args[0]));
        }
    }
}
