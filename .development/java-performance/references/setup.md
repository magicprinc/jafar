# Check the installed profiling tools

Default route: [java-performance](../SKILL.md). The toolchain is separate from the project under test; no changes to Java/Gradle target versions, dependencies or the project build are required for analysis.

| Component | Installed path |
|---|---|
| BellSoft JDK 25 tools | `C:\Z\JDK\bin` |
| PowerShell 7 | `pwsh` on `PATH` for the examples and wrappers |
| Python 3.11+ | `python` on `PATH` for `profiling/jafar_local.py` (stdlib only); pytest/Ruff/Pyright are for verification |
| jstall | local hang/thread-dump tool; see the `jstall` skill (not a CPU/heap profiler) |
| jvmlens release 0.3.1 | `C:\Z\jvmlens\jvmlens-0.3.1.jar` |
| Official Jafar 0.29.0 engine | `C:\Z\jafar\jfr-mcp-0.29.0.jar` |
| Eclipse MAT 1.17 | `C:\Z\mat\MemoryAnalyzerc.exe` |
| Python launcher, tests and synthetic fixtures | `~/.agents/skills/java-performance/profiling/` |
| Capture and bench scripts | `~/.agents/skills/java-performance/scripts/` |

Check only what the task needs. `& 'C:\Z\JDK\bin\java.exe' -version` verifies the analysis JVM, not the profiled project's JVM. Use `python -I $jf --describe jfr_summary` with `$jf` from [expert-tools](expert-tools.md) to verify the official engine; parse the JSON and check `tool` and `inputSchema`. To verify jvmlens use the pinned digest and [a bounded report](local-analysis.md), not its stale `--version`. MAT needs a real local dump/header and report-content verification; don't deliberately launch MAT on a missing file.

Do not run a `java -jar jfr-mcp…` probe without `--stdio`: the default starts an HTTP server. Do not run the legacy shell to check setup: it can install a backend without asking ([#235](https://github.com/btraceio/jafar/issues/235)). DSH web's dedicated `mcp-jafar` is disabled; other clients have separate settings. If a tool is absent, report the gap and use the documented fallback; do not auto-install or modify client configs.

## Capture preflight (no project edits)

- Java mode: `-MainClass` and `-ClassPath` run a class, not an executable JAR. For a runnable JAR,
  put an approved `-XX:StartFlightRecording=…` argument **before** `-jar` on the original launch.
- Gradle: select **one** `Test` task and a reproducible test. For example, `-Mode gradle -ProjectDir $projectDir -Task ':module:test' -TestSelector 'pkg.FooTest#method'`. The init script forces one fork at a time per task, but all test tasks in that invocation share one output path; do not use an aggregate task that runs multiple test tasks. Check the project's supported Gradle/JDK before invoking the installed build tool.
- Maven: `-Mode maven -ProjectDir $projectDir -TestSelector 'pkg.FooTest#method'` supplies `-DargLine` to the test JVM. A project with its own Surefire `argLine` or no forked test JVM may not record. Check the actual new JFR and the test result separately; do not overwrite build configuration just to make a capture work. This mode has not been verified end to end here.
- Use a new private `-OutDir` when capture output or workload logs might be sensitive. Defaults go under `%LOCALAPPDATA%\jfr-perf`. The scripts can echo application output or JVM arguments; for live capture prefer the [bounded JDK route](tools.md#capture-a-short-window-when-no-suitable-artifact-exists).

## Restore on a different workstation (owner-approved only)

1. Restore the whole skill folder, including companion scripts and fixtures. The ZIP is not an installer: it does not contain the JDK, Python, Jafar, jvmlens or MAT binaries.
2. Restore the approved JDK, [official Jafar 0.29.0 JAR](https://repo1.maven.org/maven2/io/btrace/jfr-mcp/0.29.0/), [jvmlens release and SHA-256](local-analysis.md#jfr-jvmlens-first-jdk-as-a-check-and-fallback), and [MAT installation details](mat-oql.md). The known Jafar JAR SHA-1 is `8f6fc22841af080acfb992a4ff912667a9219759`; match the pinned artifact and publisher. Do not fall back to the legacy shell or JBang.
3. Adapt the launcher’s `JAVA` and `JAR` paths and the paths in the relevant commands. Run the [synthetic fixture checks](../profiling/README.md) from the toolkit directory before using application data. Other clients do not need persistent Jafar registration.
