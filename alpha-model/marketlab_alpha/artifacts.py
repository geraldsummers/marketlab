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
