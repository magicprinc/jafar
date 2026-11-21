import io.jafar.otlp.MinimalOtlpBuilder;
import io.jafar.pprof.MinimalPprofBuilder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

class BuildLocalProfiles {
    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        MinimalPprofBuilder pprof = new MinimalPprofBuilder();
        pprof.addSampleType(pprof.addString("cpu"), pprof.addString("nanoseconds"));
        long fn = pprof.addFunction(pprof.addString("example.Synthetic.hot"), pprof.addString("Synthetic.java"));
        long loc = pprof.addLocation(fn, 42);
        pprof.addSample(List.of(loc), List.of(7L), List.of());
        pprof.addSample(List.of(loc), List.of(11L), List.of());
        Files.copy(pprof.write(dir), dir.resolve("synthetic profile.pb.gz"));

        MinimalOtlpBuilder otlp = new MinimalOtlpBuilder();
        otlp.setSampleType(otlp.addString("cpu"), otlp.addString("nanoseconds"));
        int ofn = otlp.addFunction(otlp.addString("example.Synthetic.hot"), otlp.addString("Synthetic.java"));
        int oloc = otlp.addLocation(ofn, 42);
        int stack = otlp.addStack(List.of(oloc));
        otlp.addSample(stack, List.of(), List.of(13L));
        otlp.addSample(stack, List.of(), List.of(17L));
        otlp.addSample(stack, List.of(), List.of(19L));
        Files.copy(otlp.write(dir), dir.resolve("synthetic profile.otlp"));
    }
}
