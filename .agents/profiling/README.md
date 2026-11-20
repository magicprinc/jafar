# On-demand profiling for Windows agents

Load the installed `java-performance` skill only when profiling is needed. Start with jvmlens for a compact CPU or allocation report from a JDK-written JFR, JDK `jfr` to cross-check, and [Eclipse MAT](../../doc/agents/LocalProfiling.md) for HPROF retention. Keep captures and detailed reports local. No profiler is needed for ordinary coding or tests.

## Jafar specialist queries

`jafar_local.py` uses the installed JDK 25 and `jfr-mcp-0.29.0.jar` with private session state and one child process per invocation. Neither a DSH MCP mount nor an HTTP listener is required. It does not expose live attachment, HPROF, or interactive/multi-session commands.

```powershell
python -I .agents/profiling/jafar_local.py jfr $recording --query 'events/jdk.ExecutionSample | count()' --limit 10
python -I .agents/profiling/jafar_local.py pprof $profile --query 'samples | count()' --limit 10
python -I .agents/profiling/jafar_local.py otlp $otlpFile --query 'samples | count()' --limit 10
```

Use an authorized local file, not a network drive or URI. Default deadline 60 seconds plus bounded cleanup; maximum input file and JVM heap 512 MiB each; at most 100 rows and 1 MiB of JSON output. Those limits are not guarantees that every query fits. Check `truncated`; a tool failure must not be interpreted as a zero result. See [rollout plan and known limitations](../jafar-agent-pilot.md), [bug reports](../bug_report.md), and [feature requests](../feature_request.md).

## Artifact regression tests

Run `python -m pytest -q -p no:cacheprovider .agents/profiling` with the pinned JAR installed. Supply synthetic fixtures through `JAFAR_LOCAL_TEST_JFR` (a JDK capture with exactly 274 execution samples), `JAFAR_LOCAL_TEST_PPROF` (two samples), and `JAFAR_LOCAL_TEST_OTLP` (three samples). The original fixtures and commands remain local in the task ledger; see the repo's `MinimalPprofBuilder` and `MinimalOtlpBuilder` for constructing the two protobuf fixtures. Do not substitute production captures. The tests exercise exact counts, explicit truncation, invalid file/query, timeout, and Windows rename after successful termination. Missing fixtures fail with an explicit setup error; a missing installed JAR skips the installed-artifact tests.
