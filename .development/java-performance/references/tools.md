# JVM diagnostics: tool boundaries

The priority table is in [java-performance](../SKILL.md). For existing files, choose one entry point: [JDK/jvmlens/MAT](local-analysis.md) or [the Jafar expert catalog](expert-tools.md). The latter is the only catalog of Jafar commands in this skill. No resident integration is necessary.

| Need | Tool or reference |
|---|---|
| Record a reproducible launch or test | `scripts/jfr-capture.ps1` (`java`, `gradle`, `maven`); existing `jfr-test.init.gradle` is passed to Gradle only for capture |
| Profile an authorized existing JVM | `scripts/jfr-capture.ps1 -Mode attach -TargetPid <pid> -Seconds <n>`; confirm PID, start time and capture scope first |
| Compare isolated Java snippets | `scripts/jfr-bench.ps1` and [bench contract](bench-contract.md) |
| Confirm capture options, NMT, heap dump pause or gzip | [Heap capture guide](heap-capture.md); `jcmd <pid> help <command>` for the target JVM's actual surface |
| Retention or MAT batch/OQL | [MAT reference](mat-oql.md) |
| Existing hang or deadlock | `jstall` / `jcmd <pid> Thread.print`, not a new recording by default |
| Exact live state | `jetbrains-debugger` where permitted; no debugger to estimate historical CPU or allocations |
| Code coverage or mutation | `cribro`; not a performance measure |

JVM listings can include command arguments and secrets. Inspect only the needed PID identity locally; do not output full listings. Do not force a GC merely to unlock a shared analyzer's files. High CPU is not proof that CPU explains latency, and CPU-only data cannot rule out remote waits.
