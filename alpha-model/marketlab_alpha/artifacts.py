"""Content identity and write-once artifact helpers.

Raw market data remains outside Git.  These helpers deliberately make replacing
an existing research artifact an error: an opened outcome is never silently
turned back into an untouched holdout.
"""

from __future__ import annotations

import hashlib
import json
import os
import tempfile
from pathlib import Path
from typing import Any, Iterable


def canonical_json_bytes(value: Any) -> bytes:
    return (json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n").encode()


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path, block_size: int = 1024 * 1024) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(block_size), b""):
            digest.update(block)
    return digest.hexdigest()


def verify_file(path: Path, expected_sha256: str) -> None:
    if len(expected_sha256) != 64 or sha256_file(path) != expected_sha256:
        raise ValueError(f"artifact hash mismatch: {path}")


def write_once_bytes(path: Path, payload: bytes) -> str:
    """Atomically publish bytes and refuse to replace any prior artifact."""
    path = path.absolute()
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        raise FileExistsError(f"write-once artifact already exists: {path}")
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
        os.link(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        temporary.unlink(missing_ok=True)
    return sha256_bytes(payload)


def write_once_json(path: Path, value: Any) -> str:
    return write_once_bytes(path, canonical_json_bytes(value))


def read_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as handle:
        return json.load(handle)


def iter_jsonl(path: Path) -> Iterable[dict[str, Any]]:
    if path.suffix.lower() == ".parquet":
        try:
            import duckdb
        except ImportError as error:
            raise RuntimeError("Parquet artifacts require the pinned duckdb dependency") from error
        connection = duckdb.connect(":memory:")
        try:
            relation = connection.execute("SELECT * FROM read_parquet(?)", [str(path)])
            columns = [item[0] for item in relation.description]
            while True:
                batch = relation.fetchmany(4096)
                if not batch:
                    break
                for row in batch:
                    yield dict(zip(columns, row))
        finally:
            connection.close()
        return
    with path.open(encoding="utf-8") as handle:
        for line_number, line in enumerate(handle, start=1):
            if not line.strip():
                continue
            value = json.loads(line)
            if not isinstance(value, dict):
                raise ValueError(f"JSONL row {line_number} is not an object: {path}")
            yield value


def write_once_jsonl(path: Path, values: Iterable[Any]) -> str:
    payload = b"".join(canonical_json_bytes(value) for value in values)
    return write_once_bytes(path, payload)


def write_once_chunks(path: Path, chunks: Iterable[bytes]) -> str:
    """Publish an immutable artifact without retaining the payload in memory."""
    path = path.absolute()
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        raise FileExistsError(f"write-once artifact already exists: {path}")
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    digest = hashlib.sha256()
    try:
        with os.fdopen(descriptor, "wb") as handle:
            for chunk in chunks:
                if not isinstance(chunk, bytes):
                    raise TypeError("artifact chunks must be bytes")
                handle.write(chunk)
                digest.update(chunk)
            handle.flush()
            os.fsync(handle.fileno())
        os.link(temporary, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
    finally:
        temporary.unlink(missing_ok=True)
    return digest.hexdigest()


def write_once_jsonl_stream(path: Path, values: Iterable[Any]) -> str:
    return write_once_chunks(path, (canonical_json_bytes(value) for value in values))


def write_once_records(path: Path, values: Iterable[Any]) -> str:
    """Write newline JSON or Zstd Parquet according to the requested suffix."""
    if path.suffix.lower() != ".parquet":
        return write_once_jsonl_stream(path, values)
    try:
        import duckdb
    except ImportError as error:
        raise RuntimeError("Parquet artifacts require the pinned duckdb dependency") from error
    path = path.absolute()
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        raise FileExistsError(f"write-once artifact already exists: {path}")
    json_descriptor, json_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".jsonl", dir=path.parent)
    parquet_descriptor, parquet_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".parquet", dir=path.parent)
    os.close(parquet_descriptor)
    Path(parquet_name).unlink()
    try:
        with os.fdopen(json_descriptor, "wb") as handle:
            for value in values:
                handle.write(canonical_json_bytes(value))
            handle.flush()
            os.fsync(handle.fileno())
        escaped_json = json_name.replace("'", "''")
        escaped_parquet = parquet_name.replace("'", "''")
        connection = duckdb.connect(":memory:")
        try:
            connection.execute(
                f"COPY (SELECT * FROM read_json_auto('{escaped_json}', format='newline_delimited', union_by_name=true)) "
                f"TO '{escaped_parquet}' (FORMAT PARQUET, COMPRESSION ZSTD)"
            )
        finally:
            connection.close()
        with Path(parquet_name).open("rb") as handle:
            os.fsync(handle.fileno())
        os.link(parquet_name, path)
        directory = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)
        return sha256_file(path)
    finally:
        Path(json_name).unlink(missing_ok=True)
        Path(parquet_name).unlink(missing_ok=True)
