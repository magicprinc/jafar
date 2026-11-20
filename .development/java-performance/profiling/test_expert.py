"""Exercise official Jafar via the actual local CLI, not a mocked server."""

from pathlib import Path

import pytest

from test_jafar_local import _fixture, _json, _run


@pytest.mark.parametrize(
    ("tool", "options", "field"),
    [
        (
            "jfr_list_types",
            '{"scan":true,"filter":"jdk.ExecutionSample"}',
            "eventTypes",
        ),
        ("jfr_summary", "{}", "totalEvents"),
        ("jfr_diagnose", '{"depth":"quick"}', "findings"),
        ("jfr_use", '{"resources":["cpu"]}', "resources"),
        ("jfr_tsa", "{}", "findings"),
        ("jfr_flamegraph", '{"eventType":"jdk.ExecutionSample"}', "totalSamples"),
        (
            "jfr_callgraph",
            '{"eventType":"jdk.ExecutionSample","format":"json"}',
            "nodes",
        ),
        ("jfr_hotmethods", "{}", "methods"),
        ("jfr_stackprofile", "{}", "frames"),
        ("pprof_summary", "{}", "topFunctions"),
        ("pprof_flamegraph", '{"valueField":"cpu"}', "rows"),
        ("pprof_use", '{"resources":["cpu"]}', "resources"),
        ("pprof_hotmethods", '{"valueField":"cpu"}', "topMethods"),
        ("pprof_tsa", "{}", "totalSamples"),
        ("otlp_summary", "{}", "topFunctions"),
        ("otlp_flamegraph", '{"valueField":"cpu"}', "rows"),
        ("otlp_use", '{"resources":["cpu"]}', "resources"),
        ("hdump_summary", "{}", "objectCount"),
        ("hdump_report", '{"focus":"histogram"}', "report"),
    ],
)
def test_expert_uses_official_payload(tool: str, options: str, field: str):
    fmt = tool.split("_", 1)[0]
    source = _fixture(f"JAFAR_LOCAL_TEST_{fmt.upper()}")
    result = _json(_run(fmt, source, "--tool", tool, "--args", options))
    assert result["tool"] == tool
    assert field in result["result"]
    if tool == "jfr_flamegraph":
        assert result["result"]["totalSamples"] == 274
        assert "burnSyntheticCpu" in result["result"]["data"]
    if tool in {"pprof_flamegraph", "otlp_flamegraph"}:
        assert result["result"]["total"] == (18 if fmt == "pprof" else 49)


def test_exceptions_counts_independently_recorded_events():
    source = _fixture("JAFAR_LOCAL_TEST_EXCEPTIONS")
    payload = _json(
        _run(
            "jfr",
            source,
            "--tool",
            "jfr_exceptions",
            "--args",
            '{"eventType":"jdk.JavaExceptionThrow"}',
        )
    )["result"]
    assert payload["totalExceptions"] == 3
    assert [(row["type"], row["count"]) for row in payload["byType"]] == [
        ("IllegalStateException", 3)
    ]


def test_two_sessions_use_distinct_inputs(tmp_path: Path):
    source = _fixture("JAFAR_LOCAL_TEST_JFR")
    candidate = tmp_path / "candidate recording.jfr"
    candidate.write_bytes(source.read_bytes())
    result = _json(
        _run("jfr", candidate, "--tool", "jfr_compare", "--baseline", source)
    )
    payload = result["result"]
    assert payload["baseline"]["recordingPath"] == str(source.resolve())
    assert payload["candidate"]["recordingPath"] == str(candidate.resolve())
    assert payload["baseline"]["samples"] == payload["candidate"]["samples"] == 274
    candidate.rename(tmp_path / "released.jfr")


def test_heap_analysis_leaves_original_directory_unchanged(tmp_path: Path):
    source = tmp_path / "private heap.hprof"
    original = _fixture("JAFAR_LOCAL_TEST_HDUMP").read_bytes()
    source.write_bytes(original)
    result = _json(_run("hdump", source, "--query", "objects/agent.Probe | count()"))
    assert result["results"] == [{"count": 5}]
    assert list(tmp_path.iterdir()) == [source]
    assert source.read_bytes() == original
    source.rename(tmp_path / "released.hprof")


@pytest.mark.parametrize("fmt", ["jfr", "hdump", "pprof", "otlp"])
def test_help_does_not_require_artifact(fmt: str):
    result = _json(_run("--tool", f"{fmt}_help"))
    assert result["tool"] == f"{fmt}_help"
    assert len(str(result["result"])) > 100


def test_describe_never_offers_session_controls():
    result = _run("--describe", "jfr_open")
    assert result.returncode == 2
    assert not result.stdout
    assert result.stderr.startswith(b"jafar-local: usage:")


def test_bad_baseline_does_not_keep_candidate_open(tmp_path: Path):
    candidate = tmp_path / "valid candidate.jfr"
    candidate.write_bytes(_fixture("JAFAR_LOCAL_TEST_JFR").read_bytes())
    broken = tmp_path / "bad baseline.jfr"
    broken.write_bytes(b"not a recording")
    result = _run("jfr", candidate, "--tool", "jfr_compare", "--baseline", broken)
    assert result.returncode == 5 and not result.stdout
    candidate.rename(tmp_path / "candidate released.jfr")


def test_schema_rejects_a_string_instead_of_scan_boolean():
    source = _fixture("JAFAR_LOCAL_TEST_JFR")
    result = _run(
        "jfr", source, "--tool", "jfr_list_types", "--args", '{"scan":"true"}'
    )
    assert result.returncode == 2 and not result.stdout
    assert result.stderr.startswith(b"jafar-local: usage:")


def test_buckets_do_not_silently_wrap_to_three():
    source = _fixture("JAFAR_LOCAL_TEST_JFR")
    result = _run(
        "jfr", source, "--tool", "jfr_stackprofile", "--args", '{"buckets":4294967299}'
    )
    assert result.returncode == 2 and not result.stdout
    assert result.stderr.startswith(b"jafar-local: usage:")


@pytest.mark.parametrize("buckets", [1, 3, 100])
def test_stackprofile_preserves_supported_bucket_counts(buckets: int):
    source = _fixture("JAFAR_LOCAL_TEST_JFR")
    result = _json(
        _run(
            "jfr",
            source,
            "--tool",
            "jfr_stackprofile",
            "--args",
            f'{{"buckets":{buckets}}}',
        )
    )
    assert result["result"]["bucketCount"] == buckets


@pytest.mark.parametrize("buckets", [0, 101])
def test_stackprofile_rejects_bucket_counts_outside_local_bounds(buckets: int):
    source = _fixture("JAFAR_LOCAL_TEST_JFR")
    result = _run(
        "jfr",
        source,
        "--tool",
        "jfr_stackprofile",
        "--args",
        f'{{"buckets":{buckets}}}',
    )
    assert result.returncode == 2 and not result.stdout
    assert b"buckets must be between 1 and 100" in result.stderr


def test_stackprofile_describe_advertises_local_bucket_bounds():
    properties = _json(_run("--describe", "jfr_stackprofile"))["inputSchema"][
        "properties"
    ]
    assert properties["buckets"]["minimum"] == 1
    assert properties["buckets"]["maximum"] == 100


def test_jfr_window_accepts_nanoseconds_beyond_int32():
    source = _fixture("JAFAR_LOCAL_TEST_JFR")
    result = _json(
        _run(
            "jfr",
            source,
            "--tool",
            "jfr_use",
            "--args",
            '{"endTime":4000000000,"resources":["cpu"]}',
        )
    )
    assert "cpu" in result["result"]["resources"]
    assert result["result"]["timeWindow"]["endTime"] == 4_000_000_000


def test_describe_hides_managed_properties():
    result = _json(_run("--describe", "jfr_compare"))
    properties = result["inputSchema"]["properties"]
    assert "eventType" in properties
    assert (
        "baselineSessionId" not in properties and "candidateSessionId" not in properties
    )


@pytest.mark.parametrize(
    ("tool", "args"),
    [
        ("jfr_open", "{}"),
        ("jfr_summary", '{"path":"elsewhere"}'),
        ("jfr_summary", '{"sessionId":"2"}'),
        ("jfr_summary", '{"unknown":true}'),
    ],
)
def test_expert_rejects_external_paths_and_session_control(tool: str, args: str):
    result = _run(
        "jfr", _fixture("JAFAR_LOCAL_TEST_JFR"), "--tool", tool, "--args", args
    )
    assert result.returncode == 2
    assert result.stdout == b""
    assert result.stderr.startswith(b"jafar-local: usage:")
