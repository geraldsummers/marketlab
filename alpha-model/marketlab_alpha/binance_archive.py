"""Checksum-verified official Binance futures archive acquisition."""

from __future__ import annotations

import hashlib
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Iterable

from .artifacts import read_json, sha256_bytes, sha256_file, write_once_bytes, write_once_json


ROOT = "https://data.binance.vision/data/futures"
MONTHLY_TYPES = {
    "klines",
    "aggTrades",
    "trades",
    "fundingRate",
    "markPriceKlines",
    "indexPriceKlines",
    "premiumIndexKlines",
}
INTERVAL_TYPES = {"klines", "markPriceKlines", "indexPriceKlines", "premiumIndexKlines"}
MARKETS = {"um", "cm"}


@dataclass(frozen=True)
class ArchiveRequest:
    market: str
    data_type: str
    symbol: str
    month: str
    interval: str | None = None

    def __post_init__(self) -> None:
        if self.market not in MARKETS:
            raise ValueError(f"market must be one of {sorted(MARKETS)}")
        if self.data_type not in MONTHLY_TYPES:
            raise ValueError(f"unsupported monthly data type {self.data_type}")
        if not self.symbol or self.symbol != self.symbol.upper() or not self.symbol.isalnum():
            raise ValueError("symbol must be uppercase alphanumeric")
        try:
            datetime.strptime(self.month, "%Y-%m")
        except ValueError as error:
            raise ValueError("month must use YYYY-MM") from error
        if self.data_type in INTERVAL_TYPES and not self.interval:
            raise ValueError(f"{self.data_type} requires an interval")
        if self.data_type not in INTERVAL_TYPES and self.interval is not None:
            raise ValueError(f"{self.data_type} does not accept an interval")

    @property
    def uri(self) -> str:
        if self.interval:
            name = f"{self.symbol}-{self.interval}-{self.month}.zip"
            suffix = f"{self.data_type}/{self.symbol}/{self.interval}/{name}"
        else:
            name = f"{self.symbol}-{self.data_type}-{self.month}.zip"
            suffix = f"{self.data_type}/{self.symbol}/{name}"
        return f"{ROOT}/{self.market}/monthly/{suffix}"


def acquire_binance_archives(
    requests: Iterable[ArchiveRequest],
    output_root: Path,
    *,
    fetch: Callable[[str], bytes] | None = None,
    max_workers: int = 1,
) -> dict:
    """Acquire exact archive/checksum bytes into a content-addressed store.

    Missing objects are recorded as missing and never interpreted as zero
    activity. The returned manifest is write-once for one request family.
    """
    fetch = fetch or _fetch
    if max_workers < 1 or max_workers > 32:
        raise ValueError("max_workers must be between 1 and 32")
    ordered_requests = sorted(requests, key=lambda value: value.uri)
    output_root = output_root.absolute()
    output_root.mkdir(parents=True, exist_ok=True)
    manifest_path = output_root / "manifest.json"
    if manifest_path.exists():
        manifest = read_json(manifest_path)
        if manifest.get("schemaVersion") != "marketlab.binance-archive-manifest.v1":
            raise ValueError(f"archive manifest schema is invalid: {manifest_path}")
        if [entry.get("uri") for entry in manifest.get("entries", [])] != [request.uri for request in ordered_requests]:
            raise ValueError("archive manifest request family differs from the frozen invocation")
        manifest["artifactSha256"] = sha256_file(manifest_path)
        return manifest
    request_root = output_root / "requests"
    request_root.mkdir(parents=True, exist_ok=True)
    def acquire_one(request: ArchiveRequest) -> dict:
        request_id = hashlib.sha256(request.uri.encode()).hexdigest()
        checkpoint = request_root / request_id[:2] / f"{request_id}.json"
        if checkpoint.exists():
            entry = read_json(checkpoint)
            if entry.get("uri") != request.uri:
                raise ValueError(f"archive request checkpoint identity mismatch: {checkpoint}")
            if entry.get("status") == "ACQUIRED":
                archive = output_root / entry["archiveObject"]["path"]
                if sha256_file(archive) != entry["archiveObject"]["sha256"]:
                    raise ValueError(f"archive request checkpoint object mismatch: {checkpoint}")
            return entry
        retrieved_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
        try:
            checksum_bytes = fetch(request.uri + ".CHECKSUM")
            expected = checksum_bytes.decode("utf-8").strip().split()[0]
            if len(expected) != 64 or any(value not in "0123456789abcdef" for value in expected):
                raise ValueError(f"invalid official checksum: {request.uri}")
            archive_bytes = fetch(request.uri)
        except urllib.error.HTTPError as error:
            if error.code == 404:
                entry = {
                    "request": _request_dict(request),
                    "uri": request.uri,
                    "retrievedAt": retrieved_at,
                    "status": "MISSING",
                    "httpStatus": 404,
                    "interpretation": "missing archive is not zero activity",
                }
                write_once_json(checkpoint, entry)
                return entry
            raise
        actual = sha256_bytes(archive_bytes)
        if actual != expected:
            raise ValueError(f"official checksum mismatch: {request.uri}")
        checksum_object = _store_object(output_root, checksum_bytes, "checksum")
        archive_object = _store_object(output_root, archive_bytes, "zip")
        entry = {
            "request": _request_dict(request),
            "uri": request.uri,
            "retrievedAt": retrieved_at,
            "status": "ACQUIRED",
            "officialSha256": expected,
            "archiveObject": archive_object,
            "checksumObject": checksum_object,
            "bytes": len(archive_bytes),
        }
        write_once_json(checkpoint, entry)
        return entry

    if max_workers == 1:
        entries = [acquire_one(request) for request in ordered_requests]
    else:
        with ThreadPoolExecutor(max_workers=max_workers, thread_name_prefix="binance-archive") as executor:
            entries = list(executor.map(acquire_one, ordered_requests))
    if not entries:
        raise ValueError("archive request family must not be empty")
    manifest = {
        "schemaVersion": "marketlab.binance-archive-manifest.v1",
        "source": "binance-public-data",
        "classification": "RETROSPECTIVE_DISCOVERY",
        "entries": entries,
    }
    manifest["artifactSha256"] = write_once_json(manifest_path, manifest)
    return manifest


def month_range(start: str, end_inclusive: str) -> tuple[str, ...]:
    start_date = datetime.strptime(start, "%Y-%m")
    end_date = datetime.strptime(end_inclusive, "%Y-%m")
    if start_date > end_date:
        raise ValueError("start month must not follow end month")
    months = []
    year, month = start_date.year, start_date.month
    while (year, month) <= (end_date.year, end_date.month):
        months.append(f"{year:04d}-{month:02d}")
        month += 1
        if month == 13:
            year, month = year + 1, 1
    return tuple(months)


def _fetch(uri: str) -> bytes:
    request = urllib.request.Request(uri, headers={"User-Agent": "marketlab-alpha-archive/1"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()


def _store_object(root: Path, payload: bytes, extension: str) -> dict[str, object]:
    digest = sha256_bytes(payload)
    path = root / "objects" / digest[:2] / f"{digest}.{extension}"
    if path.exists():
        if path.read_bytes() != payload:
            raise ValueError(f"content-address collision at {path}")
    else:
        try:
            write_once_bytes(path, payload)
        except FileExistsError:
            if path.read_bytes() != payload:
                raise ValueError(f"content-address collision at {path}")
    return {"path": str(path.relative_to(root)), "sha256": digest, "bytes": len(payload)}


def _request_dict(request: ArchiveRequest) -> dict[str, str]:
    value = {
        "market": request.market,
        "dataType": request.data_type,
        "symbol": request.symbol,
        "month": request.month,
    }
    if request.interval:
        value["interval"] = request.interval
    return value
