"""Black-box tests for the local Jafar MCP helper.

The default inputs are bundled synthetic fixtures.
"""

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

WRAPPER = Path(__file__).with_name("jafar_local.py")
JAR = Path(r"C:\Z\jafar\jfr-mcp-0.29.0.jar")
_FIXTURES = {
    "JAFAR_LOCAL_TEST_JFR": "cpu.jfr",
    "JAFAR_LOCAL_TEST_EXCEPTIONS": "exceptions.jfr",
    "JAFAR_LOCAL_TEST_PPROF": "profile.pb.gz",
    "JAFAR_LOCAL_TEST_OTLP": "profile.otlp",
    "JAFAR_LOCAL_TEST_HDUMP": "heap.hprof",
}


def _fixture(env_name: str) -> Path:
    default = WRAPPER.parent / "fixtures" / _FIXTURES[env_name]
    path = Path(os.environ.get(env_name, str(default)))
    if not path.is_file():
        pytest.fail(f"Missing expected synthetic fixture: {env_name}")
    return path


def _run(*args):
    return subprocess.run(
        [sys.executable, "-I", str(WRAPPER), *map(str, args)],
        capture_output=True,
        timeout=90,
        check=False,
    )


def _json(result):
    assert result.returncode == 0, result.stderr.decode("utf-8", "replace")
    assert result.stdout
    text = result.stdout.decode("utf-8")
    assert text.count("\n") == 1 and text.endswith("\n"), repr(text)
    return json.loads(text)


@pytest.mark.skipif(not JAR.is_file(), reason="installed Jafar jar is absent")
def test_jfr_cpu_count_uses_explicit_session_and_allows_rename(tmp_path):
    source = _fixture("JAFAR_LOCAL_TEST_JFR")
    copied = tmp_path / "space copy.jfr"
    shutil.copy2(source, copied)

    result = _run("jfr", copied)

    data = _json(result)
    assert data == {
        "format": "jfr",
        "query": "events/jdk.ExecutionSample | count()",
        "resultCount": 1,
        "truncated": False,
        "results": [{"count": 274}],
    }
    assert "sessionId" not in data and "path" not in data
    copied.rename(tmp_path / "released.jfr")


@pytest.mark.skipif(not JAR.is_file(), reason="installed Jafar jar is absent")
def test_pprof_count_and_close_payload(tmp_path):
    source = _fixture("JAFAR_LOCAL_TEST_PPROF")
    copied = tmp_path / "synthetic profile.pb.gz"
    shutil.copy2(source, copied)

    result = _run("pprof", copied)

    data = _json(result)
    assert data["format"] == "pprof"
    assert data["query"] == "samples | count()"
    assert data["results"] == [{"count": 2}]
    capped = _json(_run("pprof", copied, "--query", "samples", "--limit", "1"))
    assert capped["resultCount"] == 1 and len(capped["results"]) == 1
    assert capped["truncated"] is True
    copied.rename(tmp_path / "released.pb.gz")


@pytest.mark.skipif(not JAR.is_file(), reason="installed Jafar jar is absent")
def test_otlp_count_and_close_payload(tmp_path):
    source = _fixture("JAFAR_LOCAL_TEST_OTLP")
    copied = tmp_path / "synthetic profile.otlp"
    shutil.copy2(source, copied)

    result = _run("otlp", copied)

    data = _json(result)
    assert data["format"] == "otlp"
    assert data["query"] == "samples | count()"
    assert data["results"] == [{"count": 3}]
    copied.rename(tmp_path / "released.otlp")


@pytest.mark.skipif(not JAR.is_file(), reason="installed Jafar jar is absent")
def test_malformed_query_fails_without_success_json():
    source = _fixture("JAFAR_LOCAL_TEST_JFR")
    result = _run("jfr", source, "--query", "events/[bad")

    assert result.returncode != 0
    assert result.stdout == b""
    assert result.stderr.startswith(b"jafar-local:")


@pytest.mark.skipif(not JAR.is_file(), reason="installed Jafar jar is absent")
def test_malformed_source_fails_without_success_json(tmp_path):
    broken = tmp_path / "broken.jfr"
    broken.write_bytes(b"JFR\x00not-a-recording")

    result = _run("jfr", broken)

    assert result.returncode != 0
    assert result.stdout == b""
    assert result.stderr.startswith(b"jafar-local:")


@pytest.mark.skipif(not JAR.is_file(), reason="installed Jafar jar is absent")
def test_timeout_kills_owned_child_without_success_json():
    source = _fixture("JAFAR_LOCAL_TEST_JFR")
    result = _run("jfr", source, "--timeout", "0.01")

    assert result.returncode == 3
    assert result.stdout == b""
    assert result.stderr.startswith(b"jafar-local: timeout:")
