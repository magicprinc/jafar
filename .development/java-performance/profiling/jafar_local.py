#!/usr/bin/env python3
"""Run one offline analysis through the official Jafar engine.

The wrapper owns one stdio child JVM and emits one UTF-8 JSON document after
it exits. It does not attach to live JVMs, register MCP or accept remote inputs.

Exit codes:
  0 success, 2 usage/input, 3 timeout, 4 protocol, 5 tool,
  6 close, 7 response/process/serialization.
"""

from __future__ import annotations

import argparse
import json
import os
import queue
import shutil
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path
from typing import IO

JAVA = r"C:\Z\JDK\bin\java.exe"
JAVA_HOME = r"C:\Z\JDK"
JAR = r"C:\Z\jafar\jfr-mcp-0.29.0.jar"

PROTOCOL_VERSION = "2025-06-18"
MAX_LINE_BYTES = 1_048_576
MAX_TOTAL_BYTES = 4_194_304
MAX_ROWS = 100
_STACKPROFILE_BUCKETS = (1, 100)  # The engine allocates a bucket array per stack node.
MAX_INPUT_BYTES = 512 * 1024 * 1024
MAX_REQUEST_BYTES = 2_048
DEFAULT_TIMEOUT = 60.0

_ACTIONS = {
    "jfr": "query list_types summary diagnose compare flamegraph callgraph hotmethods exceptions use tsa stackprofile help",
    "hdump": "query summary report help",
    "pprof": "query summary flamegraph use hotmethods tsa help",
    "otlp": "query summary flamegraph use help",
}
_TOOLS = frozenset(
    f"{fmt}_{action}" for fmt, actions in _ACTIONS.items() for action in actions.split()
)
_MANAGED = frozenset({"sessionId", "baselineSessionId", "candidateSessionId"})
_DEFAULT_QUERIES = {
    "jfr": "events/jdk.ExecutionSample | count()",
    "hdump": "objects | count()",
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

    def __init__(self, stream: IO[bytes]) -> None:
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


def _payload(response: dict, *, allow_text: bool = False) -> dict:
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
        if allow_text and text.strip():
            return {"documentation": text}
        raise HelperError(7, "response", "tool content is not JSON")
    if not isinstance(payload, dict):
        raise HelperError(7, "response", "tool payload is not an object")
    return payload


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


def _local_file(source: Path) -> Path:
    if source.drive.startswith("\\\\") or not source.is_file():
        raise HelperError(2, "usage", "input must be a local regular file")
    source = source.resolve(strict=True)
    if source.drive.startswith("\\\\") or source.stat().st_size > MAX_INPUT_BYTES:
        raise HelperError(2, "usage", "input must be local and no larger than 512 MiB")
    return source


class Client:
    """One owner, sequential requests, one child. IDs are private to this invocation."""

    def __init__(self, proc: subprocess.Popen[bytes], deadline: float) -> None:
        self.proc = proc
        self.deadline = deadline
        self.request_id = 0
        if proc.stdout is None or proc.stdin is None:
            raise HelperError(7, "process", "stdio pipes are unavailable")
        self.reader = _Reader(proc.stdout)

    def request(self, method: str, params: dict[str, object]) -> dict:
        self.request_id += 1
        _send(
            self.proc,
            {
                "jsonrpc": "2.0",
                "id": self.request_id,
                "method": method,
                "params": params,
            },
        )
        return self.reader.wait_for(self.request_id, self.deadline)

    def initialize(self) -> None:
        response = self.request(
            "initialize",
            {
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {"name": "jafar-local", "version": "2"},
            },
        )
        result = response.get("result")
        if (
            not isinstance(result, dict)
            or result.get("protocolVersion") != PROTOCOL_VERSION
        ):
            raise HelperError(4, "protocol", "unexpected protocol version")
        _send(
            self.proc,
            {"jsonrpc": "2.0", "method": "notifications/initialized", "params": {}},
        )

    def call(self, name: str, options: dict[str, object]) -> dict:
        return _payload(
            self.request("tools/call", {"name": name, "arguments": options}),
            allow_text=name.endswith("_help"),
        )

    def schema(self, name: str) -> dict:
        response = self.request("tools/list", {})
        result = response.get("result")
        if (
            not isinstance(result, dict)
            or not isinstance(result.get("tools"), list)
            or result.get("nextCursor")
        ):
            raise HelperError(4, "protocol", "unexpected tool catalog")
        for tool in result["tools"]:
            if (
                isinstance(tool, dict)
                and tool.get("name") == name
                and isinstance(tool.get("inputSchema"), dict)
            ):
                return tool["inputSchema"]
        raise HelperError(4, "protocol", "tool missing from installed release")

    def open(self, fmt: str, source: Path) -> str:
        session_id = self.call(f"{fmt}_open", {"path": str(source)}).get("id")
        if type(session_id) is not int:
            raise HelperError(7, "response", "open returned no session id")
        return str(session_id)

    def close(self, fmt: str, session: str) -> None:
        closed = self.call(f"{fmt}_close", {"sessionId": session})
        ok = (
            closed.get("success") is True
            if fmt in {"jfr", "hdump"}
            else str(closed.get("closed")) == session
        )
        if not ok:
            raise HelperError(6, "close", "close did not confirm the expected session")

    def finish(self) -> None:
        if self.proc.stdin is None:
            raise HelperError(4, "protocol", "server stdin is unavailable")
        self.proc.stdin.close()
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise HelperError(3, "timeout", "process did not exit")
        try:
            self.proc.wait(timeout=remaining)
        except subprocess.TimeoutExpired as error:
            raise HelperError(
                3, "timeout", "process did not exit after stdin close"
            ) from error
        if self.proc.returncode != 0:
            raise HelperError(7, "process", "server exited with an error")


def _matches_option(schema: object, value: object, *, int_bits: int = 32) -> bool:
    if not isinstance(schema, dict):
        return False
    choices = schema.get("enum")
    if choices is not None and value not in choices:
        return False
    match schema.get("type"):
        case "string":
            return isinstance(value, str)
        case "boolean":
            return isinstance(value, bool)
        case "integer":
            return type(value) is int and -(2 ** (int_bits - 1)) <= value < 2 ** (
                int_bits - 1
            )
        case "number":
            return (
                isinstance(value, (int, float))
                and not isinstance(value, bool)
                and -float("inf") < value < float("inf")
            )
        case "array":
            return isinstance(value, list) and all(
                _matches_option(schema.get("items"), member) for member in value
            )
        case _:
            return False


def _schema_options(
    schema: dict, options: dict[str, object], limit: int
) -> dict[str, object]:
    properties = schema.get("properties", {})
    required = set(schema.get("required", [])) - _MANAGED
    if not isinstance(properties, dict):
        raise HelperError(4, "protocol", "unexpected tool property schema")
    if (
        options.keys() - properties.keys()
        or options.keys() & _MANAGED
        or required - options.keys()
    ):
        raise HelperError(
            2, "usage", "unknown, managed, or missing tool arguments; use --describe"
        )
    if any(
        not _matches_option(
            properties.get(key),
            value,
            int_bits=64 if key in {"startTime", "endTime"} else 32,
        )
        for key, value in options.items()
    ):
        raise HelperError(
            2, "usage", "tool argument type or enum mismatch; use --describe"
        )
    effective = dict(options)
    for key in ("limit", "topN", "topThreads"):
        if key in properties:
            size = effective.get(key, limit)
            if type(size) is not int or not 1 <= size <= limit:
                raise HelperError(2, "usage", "tool row bound must be within --limit")
            effective[key] = size
    return effective


def _query_result(fmt: str, query: str, limit: int, response: dict) -> dict:
    rows = response.get("results")
    if not isinstance(rows, list):
        raise HelperError(7, "response", "query results are not a list")
    truncated = (
        len(rows) > limit
        or response.get("truncated") is True
        or response.get("capTruncated") is True
    )
    return {
        "format": fmt,
        "query": query,
        "resultCount": min(len(rows), limit),
        "truncated": truncated,
        "results": rows[:limit],
    }


def _execute(
    client: Client, args: argparse.Namespace, work: Path, options: dict[str, object]
) -> dict:
    tool = args.describe or args.tool or f"{args.format}_query"
    if args.describe:
        schema = client.schema(tool)
        properties = {
            key: value
            for key, value in schema.get("properties", {}).items()
            if key not in _MANAGED
        }
        if tool == "jfr_stackprofile" and "buckets" in properties:
            low, high = _STACKPROFILE_BUCKETS
            properties["buckets"] = dict(
                properties["buckets"], minimum=low, maximum=high
            )
        required = [key for key in schema.get("required", []) if key not in _MANAGED]
        return {
            "tool": tool,
            "requiresInput": not tool.endswith("_help"),
            "requiresBaseline": tool == "jfr_compare",
            "inputSchema": {
                "type": "object",
                "properties": properties,
                "required": required,
            },
        }

    fmt = tool.split("_", 1)[0]
    if args.tool:
        options = _schema_options(client.schema(tool), options, args.limit)
        if tool == "jfr_stackprofile" and "buckets" in options:
            buckets = options["buckets"]
            low, high = _STACKPROFILE_BUCKETS
            if type(buckets) is not int or not low <= buckets <= high:
                raise HelperError(
                    2, "usage", f"buckets must be between {low} and {high}"
                )
    if tool.endswith("_help"):
        return {"tool": tool, "result": client.call(tool, options)}

    source = args.source
    if fmt == "hdump":
        staged = work / "input.hprof"
        shutil.copyfile(source, staged)
        source = staged
    session = client.open(fmt, source)
    baseline = None
    if args.baseline is not None:
        baseline = client.open(fmt, args.baseline)
        if baseline == session:
            raise HelperError(7, "response", "compare requires two distinct sessions")
        options.update(baselineSessionId=baseline, candidateSessionId=session)
    else:
        options["sessionId"] = session
    response = client.call(tool, options)
    if baseline is not None:
        client.close(fmt, baseline)
    client.close(fmt, session)
    if not args.tool:
        return _query_result(fmt, str(options["query"]), args.limit, response)
    return {"tool": tool, "result": response}


def _prepare(args: argparse.Namespace) -> dict[str, object]:
    if not 1 <= args.limit <= MAX_ROWS:
        raise HelperError(2, "usage", "limit must be between 1 and 100")
    if not 0 < args.timeout <= 3600:
        raise HelperError(2, "usage", "timeout must be between 0 and 3600 seconds")
    if args.describe:
        if args.describe not in _TOOLS:
            raise HelperError(2, "usage", "unknown or managed expert tool")
        if args.format or args.source or args.baseline or args.query or args.args:
            raise HelperError(2, "usage", "--describe takes only a tool name")
        return {}
    tool = args.tool or (f"{args.format}_query" if args.format else "")
    if tool not in _TOOLS:
        raise HelperError(2, "usage", "choose a profiling format or use --tool")
    fmt = tool.split("_", 1)[0]
    if args.format and args.format != fmt:
        raise HelperError(2, "usage", "tool does not match input format")
    if tool.endswith("_help"):
        if args.source is not None:
            raise HelperError(2, "usage", "help needs no input file")
    elif args.source is None:
        raise HelperError(2, "usage", "analysis requires format and local file")
    else:
        args.source = _local_file(args.source)
        if fmt == "hdump":
            with args.source.open("rb") as stream:
                if stream.read(13) != b"JAVA PROFILE ":
                    raise HelperError(2, "usage", "expected uncompressed HPROF")
    if (args.baseline is not None) != (tool == "jfr_compare"):
        raise HelperError(2, "usage", "--baseline is required only for jfr_compare")
    if args.baseline is not None:
        args.baseline = _local_file(args.baseline)
    if args.tool:
        if args.query is not None:
            raise HelperError(
                2, "usage", "expert tool parameters use --args, not --query"
            )
        try:
            options = json.loads(args.args if args.args is not None else "{}")
        except (json.JSONDecodeError, RecursionError) as error:
            raise HelperError(2, "usage", "--args must be a JSON object") from error
        if not isinstance(options, dict) or options.keys() & _MANAGED:
            raise HelperError(
                2, "usage", "--args must be a JSON object without session controls"
            )
        return options
    if args.args is not None:
        raise HelperError(2, "usage", "--args requires --tool")
    query = args.query if args.query is not None else _DEFAULT_QUERIES.get(fmt, "")
    if not query.strip() or "\n" in query or "\r" in query:
        raise HelperError(2, "usage", "provide one nonempty query line")
    return {"query": query, "limit": args.limit}


def run(args: argparse.Namespace) -> dict:
    options = _prepare(args)
    deadline = time.monotonic() + args.timeout
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
            directory.mkdir()
        java_args = [
            JAVA,
            f"-Duser.home={home}",
            f"-Djava.io.tmpdir={work / 'temp'}",
            f"-Djafar.state.dir={state}",
            f"-Djafar.mcp.sessions.file={state / 'sessions.json'}",
            "-Djfr.shell.registry.remote=false",
            "-Dfile.encoding=UTF-8",
            "-Xmx512m",
            "-jar",
            JAR,
            "--stdio",
        ]
        try:
            child = subprocess.Popen(
                java_args,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.DEVNULL,
                cwd=str(cwd),
                env=_env(home, work),
            )
        except OSError as error:
            raise HelperError(7, "process", "failed to start Java") from error
        try:
            client = Client(child, deadline)
            client.initialize()
            result = _execute(client, args, work, options)
            client.finish()
            return result
        finally:
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
    parser.add_argument("format", choices=tuple(_ACTIONS), nargs="?")
    parser.add_argument("source", type=Path, nargs="?")
    selection = parser.add_mutually_exclusive_group()
    selection.add_argument("--tool", metavar="NAME")
    selection.add_argument("--describe", metavar="NAME")
    parser.add_argument("--query")
    parser.add_argument("--args", metavar="JSON")
    parser.add_argument("--baseline", type=Path)
    parser.add_argument("--limit", type=int, default=MAX_ROWS)
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT)
    args = parser.parse_args(argv)
    try:
        result = run(args)
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
