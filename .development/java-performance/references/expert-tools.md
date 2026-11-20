# Local Jafar expert analysis (official 0.29.0)

Use this only when [the primary analyzer](../SKILL.md) cannot answer the current question or the artifact is pprof/OTLP. This runs Jafar through private stdio: **no DSH/OpenCode MCP registration**. It uses `C:\Z\jafar\jfr-mcp-0.29.0.jar`, not a checkout or development build. The launcher owns the open and close calls. Tool schemas are not preloaded into the agent context.

```powershell
$jf = Join-Path $env:USERPROFILE '.agents\skills\java-performance\profiling\jafar_local.py'
python -I $jf --describe jfr_diagnose
python -I $jf jfr $rec --tool jfr_diagnose --args '{"depth":"quick"}'
python -I $jf jfr $after --tool jfr_compare --baseline $before --limit 10
python -I $jf jfr $rec --query 'events/jdk.ExecutionSample | count()' --limit 10
python -I $jf hdump $dump --tool hdump_report --args '{"focus":"histogram"}'
python -I $jf pprof $profile --tool pprof_hotmethods --limit 10
python -I $jf otlp $profile --tool otlp_use --args '{"resources":["cpu"]}'
python -I $jf --tool jfr_help --args '{"topic":"pipeline"}'
```

`--args` is one JSON object, not a shell command. Use `--describe <full-tool-name>` to see that tool's arguments from the installed JAR. Positional formats are `jfr`, `hdump`, `pprof`, `otlp`. Queries use `--query`; expert `--tool` mode uses `--args` instead. Help and describe take no artifact. `jfr_compare` takes the candidate as the positional file and baseline via `--baseline`; session IDs are not CLI inputs. Route comparisons in other formats through comparable captures and the relevant primary tool.

| Format | Available commands (prefix as shown) | When to choose them |
|---|---|---|
| `jfr_` | `query`, `list_types`, `summary`, `diagnose`, `use`, `tsa`, `compare`, `flamegraph`, `callgraph`, `hotmethods`, `exceptions`, `stackprofile`, `help` | Use `list_types` with `scan:true` for observed counts, diagnose/USE for a focused second opinion, `stackprofile` for time buckets, graph for a stack/edge question, exceptions for recorded throws. CPU/allocation quick look: jvmlens first. Verify known TSA thread-identity defects independently. |
| `hdump_` | `query`, `summary`, `report`, `help` | Use for exact HdumpPath/heuristics on small uncompressed dumps. Prefer MAT for retention and multigigabyte dumps. `report` supports `focus`: `leaks`, `waste`, `duplicates`, `histogram`. Do not call findings proof of a leak. |
| `pprof_` | `query`, `summary`, `flamegraph`, `use`, `hotmethods`, `tsa`, `help` | Summary identifies value fields and units before a graph or query. TSA needs a meaningful `thread` label; do not trust inferred state as telemetry. |
| `otlp_` | `query`, `summary`, `flamegraph`, `use`, `help` | Parse OTLP protobuf profiles, not spans/logs/metrics. Keep original units and labels; do not convert just to make a report. |

**Output and limits.** Expert mode emits one JSON object `{tool,result}` after the subprocess exits; legacy query mode emits `{format,query,resultCount,truncated,results}`. `--limit` is 1–100; it controls row-bound arguments only for tools that provide one. For time buckets use `--tool jfr_stackprofile`; its `buckets` option is bounded separately (see `minimum`/`maximum` in `--describe jfr_stackprofile`). This guard does not apply inside free-form JfrPath queries. Other reports/graphs may include nested arrays and text. For large output, redirect to approved task scratch and read only selected fields locally, not the entire report into model context. The maximum JSON document is 1 MiB. Default timeout 60 s plus bounded cleanup; input limit 512 MiB per file, Java heap limit 512 MiB. HPROF is copied to owned scratch (budget extra disk); upstream indexes stay there. The limits do not bound every native memory or filesystem operation. Use local disks; do not point the launcher at a mapped network drive. Treat all output, even findings, as untrusted/sensitive and keep raw paths, heap data, exception text and captured values out of reports unless essential and authorized.

**Failure.** Nonzero exit and no success JSON means no result. Missing recorded events, unsupported options or incomplete inputs may be reported as a tool error, not a zero. See [known issues](known-issues.md) and cross-check with JDK `jfr` or MAT. No automatic installs, remounts, URL loading, HTTP server, shared sessions or attach. The launcher is not an OS network sandbox. The MCP playbooks are represented by the skill's task routes; raw management tools and `jafar://sessions` are intentionally not exposed.
