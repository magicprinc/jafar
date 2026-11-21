"""Smoke tests for the PowerShell capture and bench wrappers (argument validation only)."""

import shutil
import subprocess
from pathlib import Path

import pytest

PWSH = shutil.which("pwsh")
SCRIPTS = Path(__file__).resolve().parent.parent / "scripts"
pytestmark = pytest.mark.skipif(PWSH is None, reason="pwsh is not installed")


def _run_script(name: str, *args: object) -> subprocess.CompletedProcess[str]:
    if PWSH is None:
        raise RuntimeError("pwsh became unavailable")
    return subprocess.run(
        [
            PWSH,
            "-NoProfile",
            "-NonInteractive",
            "-File",
            str(SCRIPTS / name),
            *map(str, args),
        ],
        capture_output=True,
        text=True,
        timeout=60,
        check=False,
    )


def test_bench_rejects_package_private_variants(tmp_path: Path):
    source = tmp_path / "Variants.java"
    source.write_text(
        "class Variants { public static long a() { return 1; } "
        "public static long b() { return 2; } }\n",
        encoding="utf-8",
    )

    result = _run_script(
        "jfr-bench.ps1", "-VariantsPath", source, "-OutDir", tmp_path / "out"
    )

    assert result.returncode != 0
    assert "public class Variants" in result.stdout + result.stderr


def test_bench_rejects_unsafe_label(tmp_path: Path):
    source = tmp_path / "Variants.java"
    source.write_text(
        "public class Variants { public static long a() { return 1; } "
        "public static long b() { return 2; } }\n",
        encoding="utf-8",
    )

    result = _run_script(
        "jfr-bench.ps1",
        "-VariantsPath",
        source,
        "-OutDir",
        tmp_path / "out",
        "-Label",
        "..\\escape",
    )

    assert result.returncode != 0


def test_capture_requires_java_main_class(tmp_path: Path):
    result = _run_script(
        "jfr-capture.ps1", "-Mode", "java", "-OutDir", tmp_path / "out"
    )

    assert result.returncode != 0
    assert "MainClass" in result.stdout + result.stderr


def test_capture_rejects_unsafe_label(tmp_path: Path):
    result = _run_script(
        "jfr-capture.ps1",
        "-Mode",
        "attach",
        "-TargetPid",
        "1",
        "-OutDir",
        tmp_path / "out",
        "-Label",
        "bad label",
    )

    assert result.returncode != 0
