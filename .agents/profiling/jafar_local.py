#!/usr/bin/env python3
"""Run one local Jafar MCP query against one local recording.

The wrapper owns one child JVM, uses the MCP stdio transport, and emits exactly
one UTF-8 JSON document on success.  It intentionally supports only JFR, pprof
and OTLP: heap-dump analysis belongs to MAT, and this wrapper never attaches to
a live JVM or contacts a remote service.

Exit codes:
  0 success, 2 usage/input, 3 timeout, 4 protocol, 5 tool,
  6 close, 7 response/process/serialization.
"""

from __future__ import annotations

import argparse
import json
import os
import queue
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from typing import BinaryIO

JAVA = r"C:\Z\JDK\bin\java.exe"
JAVA_HOME = r"C:\Z\JDK"
JAR = r"C:\Z\jafar\jfr-mcp-0.29.0.jar"

PROTOCOL_VERSION = "2025-06-18"
MAX_LINE_BYTES = 1_048_576
MAX_TOTAL_BYTES = 4_194_304
MAX_ROWS = 100
MAX_INPUT_BYTES = 512 * 1024 * 1024
MAX_REQUEST_BYTES = 2_048
DEFAULT_TIMEOUT = 60.0

_FORMATS = ("jfr", "pprof", "otlp")
_DEFAULT_QUERIES = {
    "jfr": "events/jdk.ExecutionSample | count()",
    "pprof": "samples | count()",
    "otlp": "samples | count()",
}
_ALLOWED_ENV = (
    "PATH",
    "PATHEXT",
    "SYSTEMROOT",
    "WINDIR",
    "SYSTEMDRIVE",
    "COMSPEC",
    "USERPROFILE",
    "HOMEDRIVE",
    "HOMEPATH",
    "LOCALAPPDATA",
    "APPDATA",
    "PROGRAMDATA",
    "PROGRAMFILES",
    "PROGRAMFILES(X86)",
    "PROCESSOR_ARCHITECTURE",
    "NUMBER_OF_PROCESSORS",
    "OS",
)


class HelperError(Exception):
    def __init__(self, code: int, label: str, detail: str = "") -> None:
        super().__init__(detail or label)
        self.code = code
        self.label = label
        self.detail = detail


class _Reader:
    """One thread owns stdout; callers consume only a matching response id."""

    def __init__(self, stream: BinaryIO) -> None:
        self._stream = stream
        self._queue: queue.Queue[tuple[str, object]] = queue.Queue()
        self._thread = threading.Thread(
            target=self._read, name="jafar-stdio-reader", daemon=True
        )
        self._thread.start()

    def _read(self) -> None:
        total = 0
        try:
            while True:
                line = self._stream.readline(MAX_LINE_BYTES + 1)
                if not line:
                    self._queue.put(("eof", None))
                    return
                if len(line) > MAX_LINE_BYTES:
                    self._queue.put(("error", "protocol line too large"))
                    return
                total += len(line)
                if total > MAX_TOTAL_BYTES:
                    self._queue.put(("error", "protocol response too large"))
                    return
                try:
                    message = json.loads(line.decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError):
                    self._queue.put(("error", "invalid JSON-RPC frame"))
                    return
                self._queue.put(("message", message))
        except (OSError, ValueError):
            self._queue.put(("eof", None))

    def wait_for(self, request_id: int, deadline: float) -> dict:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise HelperError(3, "timeout", "protocol response deadline exceeded")
            try:
                kind, value = self._queue.get(timeout=remaining)
            except queue.Empty:
                raise HelperError(3, "timeout", "protocol response deadline exceeded")
            if kind == "eof":
                raise HelperError(4, "protocol", "server closed stdout")
            if kind == "error":
                raise HelperError(4, "protocol", str(value))
            if isinstance(value, dict) and value.get("id") == request_id:
                return value


def _env(home: Path, work: Path) -> dict[str, str]:
    env = {key: os.environ[key] for key in _ALLOWED_ENV if key in os.environ}
    drive = home.drive or "C:"
    env.update(
        {
            "JAVA_HOME": JAVA_HOME,
            "HOME": str(home),
            "USERPROFILE": str(home),
            "HOMEDRIVE": drive,
            "HOMEPATH": str(home)[len(drive) :] or "\\",
            "APPDATA": str(work / "appdata"),
            "LOCALAPPDATA": str(work / "localappdata"),
            "TEMP": str(work / "temp"),
            "TMP": str(work / "temp"),
            "PATH": JAVA_HOME + r"\bin;" + env.get("PATH", ""),
        }
    )
    return env


def _send(proc: subprocess.Popen[bytes], message: dict[str, object]) -> None:
    wire = (
        json.dumps(message, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        + b"\n"
    )
    if len(wire) > MAX_REQUEST_BYTES:
        raise HelperError(2, "usage", "request too large")
    if proc.stdin is None:
        raise HelperError(4, "protocol", "server stdin is unavailable")
    try:
        proc.stdin.write(wire)
        proc.stdin.flush()
    except (OSError, ValueError) as error:
        raise HelperError(4, "protocol", "failed to write to server") from error


def _payload(response: dict) -> dict:
    if "error" in response:
        raise HelperError(4, "protocol", "JSON-RPC error response")
    result = response.get("result")
    if not isinstance(result, dict):
        raise HelperError(4, "protocol", "missing tool result")
    if result.get("isError") is True:
        raise HelperError(5, "tool", "tool reported an error")
    content = result.get("content")
    if not isinstance(content, list) or not content or not isinstance(content[0], dict):
        raise HelperError(7, "response", "missing tool content")
    text = content[0].get("text")
    if not isinstance(text, str):
        raise HelperError(7, "response", "non-text tool content")
    try:
        payload = json.loads(text)
    except json.JSONDecodeError:
        raise HelperError(7, "response", "tool content is not JSON")
    if not isinstance(payload, dict):
        raise HelperError(7, "response", "tool payload is not an object")
    return payload


def _call(
    proc: subprocess.Popen,
    reader: _Reader,
    request_id: int,
    name: str,
    arguments: dict,
    deadline: float,
) -> dict:
    _send(
        proc,
        {
            "jsonrpc": "2.0",
            "id": request_id,
            "method": "tools/call",
            "params": {"name": name, "arguments": arguments},
        },
    )
    return _payload(reader.wait_for(request_id, deadline))


def _terminate(proc: subprocess.Popen[bytes]) -> None:
    if proc.poll() is not None:
        return
    try:
        proc.kill()
        proc.wait(timeout=5)
    except (OSError, subprocess.TimeoutExpired) as error:
        if proc.poll() is None:
            raise HelperError(
                7, "process", "owned Java process did not terminate"
            ) from error


def run(fmt: str, source: Path, query: str | None, limit: int, timeout: float) -> dict:
    if fmt not in _FORMATS:
        raise HelperError(2, "usage", "format must be jfr, pprof, or otlp")
    if source.drive.startswith("\\\\") or not source.is_file():
        raise HelperError(2, "usage", "input must be a local regular file")
    source = source.resolve(strict=True)
    if source.drive.startswith("\\\\") or source.stat().st_size > MAX_INPUT_BYTES:
        raise HelperError(2, "usage", "input must be local and no larger than 512 MiB")
    selected_query = query if query is not None else _DEFAULT_QUERIES[fmt]
    if not selected_query.strip() or "\n" in selected_query or "\r" in selected_query:
        raise HelperError(2, "usage", "query must be one non-empty line")

    with tempfile.TemporaryDirectory(prefix="jafar-local-") as scratch:
        work = Path(scratch)
        home = work / "home"
        state = work / "state"
        cwd = work / "cwd"
        for directory in (
            home,
            state,
            cwd,
            work / "temp",
            work / "appdata",
            work / "localappdata",
        ):
            directory.mkdir(parents=True, exist_ok=True)
        sessions = state / "sessions.json"
        child = None
        reader = None
        try:
            args = [
                f"-Duser.home={home}",
                f"-Djava.io.tmpdir={work / 'temp'}",
                f"-Djafar.state.dir={state}",
                f"-Djafar.mcp.sessions.file={sessions}",
                "-Djfr.shell.registry.remote=false",
                "-Dfile.encoding=UTF-8",
                "-Xmx512m",
                "-jar",
                JAR,
                "--stdio",
            ]
            start = time.monotonic()
            deadline = start + timeout
            try:
                child = subprocess.Popen(
                    [JAVA, *args],
                    stdin=subprocess.PIPE,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.DEVNULL,
                    cwd=str(cwd),
                    env=_env(home, work),
                )
            except OSError as error:
                raise HelperError(7, "process", "failed to start Java") from error
            if child.stdout is None or child.stdin is None:
                raise HelperError(7, "process", "stdio pipes are unavailable")
            reader = _Reader(child.stdout)

            _send(
                child,
                {
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "initialize",
                    "params": {
                        "protocolVersion": PROTOCOL_VERSION,
                        "capabilities": {},
                        "clientInfo": {"name": "jafar-local", "version": "1"},
                    },
                },
            )
            init = reader.wait_for(1, deadline)
            if "error" in init:
                raise HelperError(4, "protocol", "initialize failed")
            result = init.get("result")
            if (
                not isinstance(result, dict)
                or result.get("protocolVersion") != PROTOCOL_VERSION
            ):
                raise HelperError(4, "protocol", "unexpected protocol version")
            _send(
                child,
                {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}},
            )

            opened = _call(
                child,
                reader,
                2,
                f"{fmt}_open",
                {"path": str(source)},
                deadline,
            )
            session_id = opened.get("id")
            if type(session_id) is not int:
                raise HelperError(7, "response", "open returned no session id")
            session = str(session_id)

            response = _call(
                child,
                reader,
                3,
                f"{fmt}_query",
                {"sessionId": session, "query": selected_query, "limit": limit},
                deadline,
            )
            rows = response.get("results")
            if not isinstance(rows, list):
                raise HelperError(7, "response", "query results are not a list")
            truncated = (
                len(rows) > limit
                or response.get("truncated") is True
                or response.get("capTruncated") is True
            )
            rows = rows[:limit]

            closed = _call(
                child,
                reader,
                4,
                f"{fmt}_close",
                {"sessionId": session},
                deadline,
            )
            closed_expected = (
                closed.get("success") is True
                if fmt == "jfr"
                else type(closed.get("closed")) is int
                and closed["closed"] == session_id
            )
            if not closed_expected:
                raise HelperError(
                    6, "close", "close did not confirm the expected session"
                )

            try:
                child.stdin.close()
            except (OSError, ValueError):
                raise HelperError(4, "protocol", "failed to close server stdin")
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise HelperError(3, "timeout", "process did not exit")
            try:
                child.wait(timeout=remaining)
            except subprocess.TimeoutExpired:
                raise HelperError(
                    3, "timeout", "process did not exit after stdin close"
                )
            if child.returncode != 0:
                raise HelperError(7, "process", "server exited with an error")

            return {
                "format": fmt,
                "query": selected_query,
                "resultCount": len(rows),
                "truncated": truncated,
                "results": rows,
            }
        finally:
            if child is not None:
                _terminate(child)
                for stream in (child.stdin, child.stdout):
                    if stream is not None:
                        stream.close()


def _fail(error: HelperError) -> int:
    message = f"jafar-local: {error.label}: {error.detail or 'failed'}\n"
    sys.stderr.write(message)
    sys.stderr.flush()
    return error.code


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="jafar_local.py")
    parser.add_argument("format", choices=_FORMATS)
    parser.add_argument("source", type=Path)
    parser.add_argument("--query")
    parser.add_argument("--limit", type=int, default=MAX_ROWS)
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT)
    args = parser.parse_args(argv)
    if not 1 <= args.limit <= MAX_ROWS:
        return _fail(HelperError(2, "usage", "limit must be between 1 and 100"))
    if not (0 < args.timeout <= 3600):
        return _fail(
            HelperError(2, "usage", "timeout must be between 0 and 3600 seconds")
        )
    try:
        result = run(args.format, args.source, args.query, args.limit, args.timeout)
    except HelperError as error:
        return _fail(error)
    except (OSError, ValueError, RecursionError):
        return _fail(HelperError(7, "response", "unexpected local failure"))
    try:
        encoded = (
            json.dumps(
                result, ensure_ascii=False, allow_nan=False, separators=(",", ":")
            ).encode("utf-8")
            + b"\n"
        )
    except (TypeError, ValueError, RecursionError):
        return _fail(HelperError(7, "response", "could not encode strict JSON"))
    if len(encoded) > MAX_LINE_BYTES:
        return _fail(HelperError(7, "response", "result too large"))
    try:
        sys.stdout.buffer.write(encoded)
        sys.stdout.buffer.flush()
    except OSError:
        return _fail(HelperError(7, "response", "could not write result"))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
