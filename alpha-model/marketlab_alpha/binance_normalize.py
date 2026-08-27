"""Deterministic normalization of checksum-verified Binance kline archives."""

from __future__ import annotations

import csv
from datetime import datetime, timezone
import io
import math
import zipfile
from pathlib import Path
from typing import Any

from .artifacts import iter_jsonl, read_json, sha256_file, write_once_json, write_once_records


DAY_MILLIS = 86_400_000
FIXED_INTERVAL_MILLIS = {
    "1m": 60_000, "3m": 180_000, "5m": 300_000, "15m": 900_000,
    "30m": 1_800_000, "1h": 3_600_000, "2h": 7_200_000,
    "4h": 14_400_000, "6h": 21_600_000, "8h": 28_800_000,
    "12h": 43_200_000, "1d": DAY_MILLIS, "3d": 3 * DAY_MILLIS,
    "1w": 7 * DAY_MILLIS,
}


def build_daily_universe_observations(normalized_path: Path, output: Path) -> dict[str, Any]:
    """Aggregate causal daily liquidity observations from normalized klines.

    A day's notional becomes observable only at the latest availability time of
    a contributing bar. Missing archive days remain absent; they are never
    converted to zero volume or an inferred delisting state.
    """
    source_manifest_path = normalized_path.with_suffix(normalized_path.suffix + ".manifest.json")
    source_manifest = read_json(source_manifest_path)
    if source_manifest.get("schemaVersion") != "marketlab.binance-normalized-klines.v1":
        raise ValueError("unsupported normalized kline manifest")
    if sha256_file(normalized_path) != source_manifest.get("outputSha256"):
        raise ValueError("normalized klines differ from their manifest")

    quarantined_days = []
    row_count = 0

    def finalize(symbol: str, day: str, value: dict[str, Any]) -> dict[str, Any] | None:
        times = sorted(value["eventTimes"])
        intervals = value["intervals"]
        day_start = int(datetime.fromisoformat(day).replace(tzinfo=timezone.utc).timestamp() * 1000)
        interval = next(iter(intervals)) if len(intervals) == 1 else 0
        complete = (
            interval > 0
            and DAY_MILLIS % interval == 0
            and len(times) == DAY_MILLIS // interval
            and times[0] == day_start
            and times[-1] + interval == day_start + DAY_MILLIS
            and all(right - left == interval for left, right in zip(times, times[1:]))
        )
        if not complete:
            quarantined_days.append({
                "symbol": symbol,
                "observationDay": day,
                "sourceRows": value["sourceRows"],
                "reason": "incomplete or non-contiguous UTC day",
            })
            return None
        return {
            "symbol": symbol,
            "observationDay": day,
            "observedAtEpochMillis": value["observedAtEpochMillis"],
            "quoteNotional": value["quoteNotional"],
            "eligible": True,
            "sourceRows": value["sourceRows"],
        }

    def rows() -> Any:
        sorted_rows = sorted(
            iter_jsonl(normalized_path),
            key=lambda row: (
                str(row.get("symbol", "")).strip().upper(),
                int(row["eventTimeEpochMillis"]),
            ),
        )
        nonlocal row_count
        current_key: tuple[str, str] | None = None
        aggregate: dict[str, Any] | None = None
        prior_order: tuple[str, int] | None = None
        for line_number, row in enumerate(sorted_rows, start=1):
            symbol = str(row.get("symbol", "")).strip().upper()
            if not symbol:
                raise ValueError(f"normalized row {line_number} has no symbol")
            event_time = int(row["eventTimeEpochMillis"])
            order = (symbol, event_time)
            if prior_order is not None and order <= prior_order:
                raise ValueError("normalized bars must be sorted by symbol and event time")
            prior_order = order
            available_time = int(row["availableTimeEpochMillis"])
            if available_time <= event_time:
                raise ValueError(f"normalized row {line_number} is available before its bar completes")
            notional = float(row["quoteVolume"])
            if not math.isfinite(notional) or notional < 0.0:
                raise ValueError(f"normalized row {line_number} has invalid quote volume")
            day = datetime.fromtimestamp(event_time / 1000.0, tz=timezone.utc).date().isoformat()
            key = (symbol, day)
            if current_key is not None and key != current_key:
                completed = finalize(current_key[0], current_key[1], aggregate or {})
                if completed is not None:
                    row_count += 1
                    yield completed
                aggregate = None
            if aggregate is None:
                aggregate = {
                    "quoteNotional": 0.0,
                    "observedAtEpochMillis": 0,
                    "sourceRows": 0,
                    "eventTimes": [],
                    "intervals": set(),
                }
                current_key = key
            aggregate["quoteNotional"] += notional
            aggregate["observedAtEpochMillis"] = max(aggregate["observedAtEpochMillis"], available_time)
            aggregate["sourceRows"] += 1
            aggregate["eventTimes"].append(event_time)
            aggregate["intervals"].add(available_time - event_time)
        if current_key is not None:
            completed = finalize(current_key[0], current_key[1], aggregate or {})
            if completed is not None:
                row_count += 1
                yield completed

    output_hash = write_once_records(output, rows())
    manifest = {
        "schemaVersion": "marketlab.daily-universe-observations.v1",
        "normalizedPath": str(normalized_path.absolute()),
        "normalizedSha256": sha256_file(normalized_path),
        "normalizedManifestPath": str(source_manifest_path.absolute()),
        "normalizedManifestSha256": sha256_file(source_manifest_path),
        "outputPath": str(output.absolute()),
        "outputSha256": output_hash,
        "rows": row_count,
        "quarantinedDays": quarantined_days,
        "availabilitySemantics": "daily quote notional is available at the latest contributing kline availability time",
        "missingDaySemantics": "absent or incomplete days are quarantined, never zero-filled and never interpreted as a delisting",
    }
    manifest["artifactSha256"] = write_once_json(output.with_suffix(output.suffix + ".manifest.json"), manifest)
    return manifest


def normalize_kline_manifest(manifest_path: Path, output: Path) -> dict[str, Any]:
    manifest = read_json(manifest_path)
    if manifest.get("schemaVersion") != "marketlab.binance-archive-manifest.v1":
        raise ValueError("unsupported Binance archive manifest")
    root = manifest_path.parent
    sources: list[str] = []
    previous: dict[str, tuple[int, int]] = {}
    gaps = []
    row_count = 0

    def rows() -> Any:
        nonlocal row_count
        ordered_entries = sorted(
            manifest["entries"],
            key=lambda entry: (
                entry["request"]["symbol"],
                entry["request"].get("month", ""),
                entry.get("uri", ""),
            ),
        )
        for entry in ordered_entries:
            if entry["status"] == "MISSING":
                continue
            request = entry["request"]
            if request["dataType"] != "klines":
                raise ValueError("kline normalizer cannot consume another archive family")
            archive = root / entry["archiveObject"]["path"]
            if sha256_file(archive) != entry["archiveObject"]["sha256"]:
                raise ValueError(f"normalized source differs from manifest: {archive}")
            sources.append(entry["archiveObject"]["sha256"])
            for row in _read_archive(
                archive,
                request["symbol"],
                entry["archiveObject"]["sha256"],
                request.get("interval"),
            ):
                prior = previous.get(row["symbol"])
                interval = row["availableTimeEpochMillis"] - row["eventTimeEpochMillis"]
                if prior and row["eventTimeEpochMillis"] <= prior[0]:
                    raise ValueError(f"duplicate or reversed kline {(row['symbol'], row['eventTimeEpochMillis'])}")
                if prior and row["eventTimeEpochMillis"] != prior[0] + prior[1]:
                    gaps.append({
                        "symbol": row["symbol"],
                        "afterEpochMillis": prior[0],
                        "beforeEpochMillis": row["eventTimeEpochMillis"],
                    })
                previous[row["symbol"]] = (row["eventTimeEpochMillis"], interval)
                row_count += 1
                yield row

    output_hash = write_once_records(output, rows())
    normalized = {
        "schemaVersion": "marketlab.binance-normalized-klines.v1",
        "archiveManifestPath": str(manifest_path.absolute()),
        "archiveManifestSha256": sha256_file(manifest_path),
        "sourceObjectSha256": sorted(set(sources)),
        "outputPath": str(output.absolute()),
        "outputSha256": output_hash,
        "rows": row_count,
        "gaps": gaps,
        "sortOrder": ["symbol", "eventTimeEpochMillis"],
        "availabilitySemantics": "exchange close time plus one millisecond; retrospective proxy, not local receipt",
    }
    normalized["artifactSha256"] = write_once_json(output.with_suffix(output.suffix + ".manifest.json"), normalized)
    return normalized


def _read_archive(
    path: Path, venue_symbol: str, source_sha256: str, expected_interval: str | None = None
) -> list[dict[str, Any]]:
    with zipfile.ZipFile(path) as archive:
        files = [item for item in archive.infolist() if not item.is_dir()]
        if len(files) != 1:
            raise ValueError(f"archive must contain exactly one CSV: {path}")
        payload = archive.read(files[0]).decode("utf-8")
    result = []
    for columns in csv.reader(io.StringIO(payload)):
        if not columns or not columns[0].lstrip("-").isdigit():
            continue
        if len(columns) < 11:
            raise ValueError(f"kline row has fewer than eleven columns: {path}")
        open_time = _milliseconds(int(columns[0]))
        close_time = _milliseconds(int(columns[6]))
        values = [float(columns[index]) for index in (1, 2, 3, 4, 5, 7, 9, 10)]
        if not all(math.isfinite(value) for value in values):
            raise ValueError(f"non-finite kline value: {path}")
        open_price, high, low, close, base_volume, quote_volume, taker_base, taker_quote = values
        if min(open_price, high, low, close) <= 0 or high < max(open_price, close) or low > min(open_price, close):
            raise ValueError(f"impossible OHLC row: {path}")
        trade_count = int(columns[8])
        if min(base_volume, quote_volume, taker_base, taker_quote) < 0 or trade_count < 0:
            raise ValueError(f"negative volume or trade count: {path}")
        if taker_base > base_volume + 1e-12 or taker_quote > quote_volume + 1e-12:
            raise ValueError(f"taker-buy volume exceeds total volume: {path}")
        if close_time < open_time:
            raise ValueError(f"kline closes before it opens: {path}")
        if expected_interval is not None:
            expected_millis = FIXED_INTERVAL_MILLIS.get(expected_interval)
            if expected_millis is None:
                raise ValueError(f"normalizer does not support variable or unknown interval {expected_interval}")
            if close_time + 1 - open_time != expected_millis:
                raise ValueError(f"kline duration differs from requested {expected_interval}: {path}")
        result.append({
            "symbol": _base_symbol(venue_symbol),
            "venueSymbol": venue_symbol,
            "eventTimeEpochMillis": open_time,
            "availableTimeEpochMillis": close_time + 1,
            "open": open_price,
            "high": high,
            "low": low,
            "close": close,
            "baseVolume": base_volume,
            "quoteVolume": quote_volume,
            "tradeCount": trade_count,
            "takerBuyBaseVolume": taker_base,
            "takerBuyQuoteVolume": taker_quote,
            "sourceArchiveSha256": source_sha256,
        })
    return result


def _milliseconds(value: int) -> int:
    # Binance documents a timestamp-unit transition in some public archives.
    return value // 1_000 if abs(value) >= 100_000_000_000_000 else value


def _base_symbol(value: str) -> str:
    if value.endswith("USDT"):
        return value[:-4]
    if value.endswith("USDC"):
        return value[:-4]
    return value
