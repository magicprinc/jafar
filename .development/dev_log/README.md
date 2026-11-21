# Development log: local JVM profiling work (2026-11-19 .. 2026-11-21)

d:\REPO\github\jafar\.development

Durable record copied from `~/.dsh/reviews/jafar-resume-20261120` before that working
directory was deleted. It documents decisions, evidence and fixture provenance; it is not
an active toolkit and nothing here is needed to run a profile.

| File | What it records |
|---|---|
| `HANDOFF.md` | Continuation state for the next agent: current commit, verified toolchain, tests, copy-sync procedure, backlog and profiling-session intake workflow. |
| `task.md` | Reconstructed task ledger: owner decisions and constraints, completed commits and their evidence, verification results and open questions. |
| `portable-cutover.md` | Scope and decisions of the cutover that moved the launcher out of this checkout into the installed skill. |
| `indexed-heap-report.md` | Analysis and test design for the HPROF indexed-mode cleanup fix (production change is in commits `9a900dc0` and `b4d13107`). |
| `audit_outgoing.py` | Reusable scan of a commit range for credential-like strings and private machine paths: `python audit_outgoing.py <base> <target>`. |
| `generators/` | Sources that produced the synthetic fixtures and probe workloads. |

## Where the live artifacts are

- Installed toolkit (launcher, tests, synthetic fixtures, references):
  `~/.agents/skills/java-performance/`, mirrored in `../java-performance/`.
- Handoff copy: `../TUNE_Profile.md` (the original `C:\DOC_APR\home` file was removed);
  curated backup: `C:\DOC_APR\home\TUNE_Profile-tools.zip`.
- The bundled fixtures under `../java-performance/profiling/fixtures/` are byte-identical
  to the recordings these generators produced.

## Regenerating fixtures

`generators/SyntheticCpu.java`, `SyntheticExceptions.java` and `ScratchWorkload.java` are
standalone and need only JDK 25. `MakeSyntheticHprof.java` writes an HPROF directly.
`BuildLocalProfiles.java` imports the pprof/OTLP builders from the Jafar test fixtures, so
it needs the repository to compile.

## Deliberately not kept

- Reproductions for upstream Jafar issues #233 and #234: the owner decided upstream would
  handle them, so those captures were dropped rather than archived here.
- Gradle/JDTLS caches, temporary test directories and intermediate launcher copies:
  disposable working state, deleted with the working directory.
- JUnit XML dumps of known failures: reproducible from the repository's own test tasks; the
  observed results are summarized in `task.md`.
