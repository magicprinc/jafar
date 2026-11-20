# Check the installed profiling tools

Default route: [java-performance](../SKILL.md). The toolchain is separate from the project under test; no changes to Java/Gradle target versions, dependencies or the project build are required for analysis.

| Component | Installed path |
|---|---|
| BellSoft JDK 25 tools | `C:\Z\JDK\bin` |
| jvmlens release 0.3.1 | `C:\Z\jvmlens\jvmlens-0.3.1.jar` |
| Official Jafar 0.29.0 engine | `C:\Z\jafar\jfr-mcp-0.29.0.jar` |
| Eclipse MAT 1.17 | `C:\Z\mat\MemoryAnalyzerc.exe` |
| Python launcher, tests and synthetic fixtures | `~/.agents/skills/java-performance/profiling/` |
| Capture and bench scripts | `~/.agents/skills/java-performance/scripts/` |

Check only what the task needs. `& 'C:\Z\JDK\bin\java.exe' -version` verifies the analysis JVM, not the profiled project's JVM. Use `python -I $jf --describe jfr_summary` with `$jf` from [expert-tools](expert-tools.md) to verify the official engine; parse the JSON and check `tool` and `inputSchema`. To verify jvmlens use the pinned digest and [a bounded report](local-analysis.md), not its stale `--version`. MAT needs a real local dump/header and report-content verification; don't deliberately launch MAT on a missing file.

Do not run a `java -jar jfr-mcp…` probe without `--stdio`: the default starts an HTTP server. Do not run the legacy shell to check setup: it can install a backend without asking ([#235](https://github.com/btraceio/jafar/issues/235)). DSH web's dedicated `mcp-jafar` is disabled; other clients have separate settings. If the required tool or release is absent, report that gap and use the documented fallback; do not auto-install or modify client configs. For an authorized move to another machine, verify official artifacts and explicitly adapt the launcher's two installed paths; the scripts are not a portable installer.
