# Brief for an agent profiling a Java project

Use [java-performance](../SKILL.md) to choose exactly one initial route; it also defines the evidence and reporting requirements. Reuse a suitable authorized local artifact; use JDK capture scripts if capture is necessary and approved. Do not change the project build or start a persistent profiler just to collect evidence.

For existing artifacts: jvmlens for JFR CPU/allocation, JDK `jfr` to verify, Eclipse MAT for HPROF retention, [Jafar expert launcher](expert-tools.md) for pprof/OTLP and advanced analysis. Compare like workloads with repeat timings, verify correctness, name source locations, and separate measured results from guesses. Report only sanitized aggregates and retain raw captures privately. No MCP mount is required.
