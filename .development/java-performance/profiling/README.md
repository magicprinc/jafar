# Local Jafar adapter and regression suite

This directory is the source of truth for the Windows launcher, its regression tests, and local feedback. It works without the Jafar Git checkout. For operation and the tool list see [expert tools](../references/expert-tools.md); for setup and checksums see [setup](../references/setup.md). Run `python -I <this-directory>/jafar_local.py --describe jfr_compare` to check the engine. Report reproducible problems in [bug_report.md](bug_report.md) and real gaps in [feature_request.md](feature_request.md). Keep work data private.

## Verify the installed toolkit

Run the checks from the toolkit directory so Ruff classifies the sibling test imports consistently. The launcher itself works from any directory.

```powershell
Set-Location -LiteralPath (Join-Path $env:USERPROFILE '.agents\skills\java-performance\profiling')
ruff check --no-cache .
ruff format --check --no-cache .
pyright .
$env:PYTHONDONTWRITEBYTECODE = '1'
python -m pytest -q -p no:cacheprovider .
```

The tests drive the launcher as a separate process against the official 0.29.0 JAR. `test_scripts.py` smoke-checks the PowerShell wrappers' argument validation and needs `pwsh` on `PATH`; those cases skip where `pwsh` is absent. Bundled fixtures are synthetic, not project captures. No repo, build cache, live JVM, or fixture generator is needed. Use the bundled files for acceptance; environment variables are optional overrides for equivalent synthetic data:

| File | Evidence | Optional override |
|---|---|---|
| `fixtures/cpu.jfr` | 274 JDK execution samples with `burnSyntheticCpu` | `JAFAR_LOCAL_TEST_JFR` |
| `fixtures/exceptions.jfr` | three JDK `IllegalStateException` throws | `JAFAR_LOCAL_TEST_EXCEPTIONS` |
| `fixtures/profile.pb.gz` | protobuf pprof, CPU weights 7 and 11 | `JAFAR_LOCAL_TEST_PPROF` |
| `fixtures/profile.otlp` | protobuf OTLP Profile, CPU weights 13, 17 and 19 | `JAFAR_LOCAL_TEST_OTLP` |
| `fixtures/heap.hprof` | synthetic dump, five `agent.Probe` objects | `JAFAR_LOCAL_TEST_HDUMP` |

For runtime only, Python and the configured tool paths suffice. Do not move capture/bench scripts from `../scripts`: their companion Java/Gradle files stay beside them. For offline recovery of the machine, keep the tool installations or verified installation media separately; the skill ZIP does not contain executables.
