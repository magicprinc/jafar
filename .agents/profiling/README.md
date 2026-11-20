# On-demand profiling for Windows agents

Load `java-performance` only when profiling is needed. Prefer jvmlens for compact CPU/allocation reports from native JFR, JDK `jfr` to cross-check, and [Eclipse MAT](../../doc/agents/LocalProfiling.md) for HPROF retention. The official Jafar adapter covers expert operations and pprof/OTLP. Keep captures and raw reports local. No change to the profiled project's build is required.

## Official engine, no persistent MCP

`jafar_local.py` uses installed JDK 25 and `jfr-mcp-0.29.0.jar` in private stdio. One invocation owns the JVM, isolated scratch and session IDs; success is one JSON document after exit. These commands never register an MCP, start HTTP, attach to a live target or use the development checkout as their engine.

```powershell
python -I .agents/profiling/jafar_local.py --describe jfr_compare
python -I .agents/profiling/jafar_local.py jfr $recording --tool jfr_diagnose --args '{"depth":"quick"}'
python -I .agents/profiling/jafar_local.py jfr $candidate --tool jfr_compare --baseline $baseline --limit 10
python -I .agents/profiling/jafar_local.py hdump $dump --tool hdump_report --args '{"focus":"histogram"}'
python -I .agents/profiling/jafar_local.py pprof $profile --query 'samples | count()' --limit 10
python -I .agents/profiling/jafar_local.py otlp $profile --tool otlp_use --args '{"resources":["cpu"]}'
python -I .agents/profiling/jafar_local.py --tool jfr_help --args '{"topic":"tools"}'
```

The adapter maps all 29 release analysis/help tools, using the official runtime schema for any expert command. The other eight tools (`*_open`, `*_close`) are internal; no manual session IDs or MCP resources. The profile is required except for help/schema. Use `--query` for direct queries (legacy result envelope); with `--tool` use `--args` for arguments and read `{tool,result}`. `--baseline` is only for JFR compare; cross-session query joins are not supported. Use [bug reports](../bug_report.md) and [feature requests](../feature_request.md) without raw artifacts or private paths.

Use authorized local regular files, not network drives or URLs. Default deadline 60 s plus bounded termination; 512 MiB per input and Java heap, 1 MiB result. HPROF is copied to scratch to contain possible index side effects; budget disk for that copy and prefer MAT for larger heaps. `--limit` bounds rows only where the upstream tool supports it. Check `truncated`; do not interpret failure as zero. Known upstream semantic defects are not fixed by the adapter; in particular, verify TSA thread identities independently.

## Release-artifact verification

Run `python -m pytest -q -p no:cacheprovider .agents/profiling` on Windows with the installed Python and official JAR. Supply synthetic fixtures via `JAFAR_LOCAL_TEST_JFR` (274 execution samples with `burnSyntheticCpu`), `JAFAR_LOCAL_TEST_EXCEPTIONS` (three JDK `IllegalStateException` throws), `JAFAR_LOCAL_TEST_PPROF` (cpu values 7, 11), `JAFAR_LOCAL_TEST_OTLP` (cpu values 13, 17, 19), and `JAFAR_LOCAL_TEST_HDUMP` (five `agent.Probe` instances). Use the repository's minimal proto builders and a JDK-authored JFR, not real captures. The local task ledger retains the verified fixtures. Additional cases exercise invalid query/file, compare partial startup, output limits, explicit truncation, invalid schema arguments, timeout, and Windows file release. A missing installed JAR skips the original six installed-artifact query cases; the expert suite requires it and the synthetic fixtures.
