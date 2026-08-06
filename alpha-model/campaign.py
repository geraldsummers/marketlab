#!/usr/bin/env python3
"""Immutable command-line orchestration for the archive alpha campaign."""

from __future__ import annotations

import argparse
import heapq
import itertools
import json
import shutil
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from marketlab_alpha.artifact_commands import freeze_candidate, register_archives
from marketlab_alpha.artifacts import canonical_json_bytes, iter_jsonl, read_json, sha256_file, write_once_bytes, write_once_json, write_once_records
from marketlab_alpha.panel import HORIZON_MILLIS, enrich_cross_asset_features, materialize_panel
from marketlab_alpha.universe import HistoricalObservation, build_weekly_universes


def parse_instant(value: str) -> datetime:
    instant = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if instant.tzinfo is None or instant.utcoffset() is None:
        raise argparse.ArgumentTypeError("timestamps must include an offset")
    return instant.astimezone(timezone.utc)


def acquire(args: argparse.Namespace) -> None:
    manifest = register_archives([Path(value) for value in args.input], Path(args.output))
    print(json.dumps(manifest, sort_keys=True))


def discover_binance(args: argparse.Namespace) -> None:
    from marketlab_alpha.archive_discovery import discover_archive_symbols

    root = Path(args.output_root)
    manifest = discover_archive_symbols(
        root,
        market=args.market,
        data_type=args.data_type,
        symbol_pattern=args.symbol_pattern,
        bucket_url=args.bucket_url,
    )
    symbols_path = root / "symbols.txt"
    payload = ("\n".join(manifest["symbols"]) + "\n").encode()
    if symbols_path.exists():
        if symbols_path.read_bytes() != payload:
            raise ValueError("discovery symbols differ from the immutable symbols file")
        symbols_hash = sha256_file(symbols_path)
    else:
        symbols_hash = write_once_bytes(symbols_path, payload)
    print(json.dumps({
        "symbols": len(manifest["symbols"]),
        "symbolsPath": str(symbols_path.absolute()),
        "symbolsSha256": symbols_hash,
        "artifactSha256": manifest["artifactSha256"],
    }))


def download_binance(args: argparse.Namespace) -> None:
    from marketlab_alpha.binance_archive import ArchiveRequest, acquire_binance_archives, month_range

    symbols = [line.strip().upper() for line in Path(args.symbols).read_text().splitlines() if line.strip()]
    if not symbols or len(symbols) != len(set(symbols)):
        raise ValueError("symbols file must contain unique non-empty symbols")
    requests = [
        ArchiveRequest(args.market, args.data_type, symbol, month, args.interval)
        for symbol in symbols
        for month in month_range(args.start_month, args.end_month)
    ]
    manifest = acquire_binance_archives(requests, Path(args.output_root), max_workers=args.workers)
    acquired = sum(entry["status"] == "ACQUIRED" for entry in manifest["entries"])
    print(json.dumps({"acquired": acquired, "requests": len(requests), "artifactSha256": manifest["artifactSha256"]}))


def normalize_binance(args: argparse.Namespace) -> None:
    from marketlab_alpha.binance_normalize import normalize_kline_manifest

    result = normalize_kline_manifest(Path(args.manifest), Path(args.output))
    print(json.dumps({"rows": result["rows"], "gaps": len(result["gaps"]), "outputSha256": result["outputSha256"]}))


def universe_observations(args: argparse.Namespace) -> None:
    from marketlab_alpha.binance_normalize import build_daily_universe_observations

    result = build_daily_universe_observations(Path(args.bars), Path(args.output))
    print(json.dumps({
        "rows": result["rows"],
        "quarantinedDays": len(result["quarantinedDays"]),
        "outputSha256": result["outputSha256"],
    }))


def universe(args: argparse.Namespace) -> None:
    observations = []
    for value in iter_jsonl(Path(args.observations)):
        observed = value.get("observedAt", value.get("observedAtEpochMillis"))
        if isinstance(observed, (int, float)):
            observed_at = datetime.fromtimestamp(float(observed) / 1000.0, tz=timezone.utc)
        else:
            observed_at = parse_instant(str(observed))
        observations.append(HistoricalObservation(
            symbol=str(value["symbol"]),
            observed_at=observed_at,
            quote_notional=float(value.get("quoteNotional", value.get("notional", 0.0))),
            eligible=bool(value.get("eligible", True)),
        ))
    snapshots = build_weekly_universes(
        observations,
        args.first_as_of,
        args.last_as_of,
        basket_sizes=tuple(args.basket_size),
    )
    result = {
        "schemaVersion": "marketlab.historical-universe-series.v1",
        "sourcePath": str(Path(args.observations).absolute()),
        "sourceSha256": sha256_file(Path(args.observations)),
        "snapshots": [snapshot.to_dict() for snapshot in snapshots],
    }
    result["artifactSha256"] = write_once_json(Path(args.output), result)
    print(json.dumps({"snapshots": len(snapshots), "artifactSha256": result["artifactSha256"]}))


def universe_symbols(args: argparse.Namespace) -> None:
    source = Path(args.universe)
    series = read_json(source)
    if series.get("schemaVersion") != "marketlab.historical-universe-series.v1":
        raise ValueError("unsupported historical universe series")
    base_symbols = sorted({
        str(member["symbol"])
        for snapshot in series.get("snapshots", [])
        for member in snapshot.get("members", [])
    })
    if not {"BTC", "ETH"}.issubset(base_symbols):
        raise ValueError("universe symbol union is missing mandatory BTC or ETH")
    venue_symbols = [f"{symbol}{args.quote_suffix}" for symbol in base_symbols]
    output = Path(args.output)
    digest = write_once_bytes(output, ("\n".join(venue_symbols) + "\n").encode())
    manifest = {
        "schemaVersion": "marketlab.universe-symbol-union.v1",
        "universePath": str(source.absolute()),
        "universeSha256": sha256_file(source),
        "quoteSuffix": args.quote_suffix,
        "baseSymbols": base_symbols,
        "venueSymbols": venue_symbols,
        "outputPath": str(output.absolute()),
        "outputSha256": digest,
    }
    manifest["artifactSha256"] = write_once_json(output.with_suffix(output.suffix + ".manifest.json"), manifest)
    print(json.dumps({"symbols": len(venue_symbols), "outputSha256": digest}))


def materialize(args: argparse.Namespace) -> None:
    universe_series = read_json(Path(args.universe))
    memberships: dict[int, tuple[str, ...]] = {}
    for snapshot in universe_series.get("snapshots", []):
        if int(snapshot["basketSize"]) != args.basket_size:
            continue
        effective = int(parse_instant(snapshot["asOf"]).timestamp() * 1000)
        memberships[effective] = tuple(member["symbol"] for member in snapshot["members"])
    if not memberships:
        raise ValueError(f"universe contains no basket-size {args.basket_size} snapshots")
    bars_path, output = Path(args.bars), Path(args.output)
    output.absolute().parent.mkdir(parents=True, exist_ok=True)
    period_start = int(args.period_start.timestamp() * 1000)
    period_end = int(args.period_end.timestamp() * 1000)
    if period_start >= period_end:
        raise ValueError("panel period start must precede its end")
    temporary_root = Path(tempfile.mkdtemp(prefix=".panel.", dir=output.absolute().parent))
    symbol_paths: list[Path] = []
    row_count = 0
    try:
        seen_symbols: set[str] = set()
        source = (
            row for row in iter_jsonl(bars_path)
            if int(row["eventTimeEpochMillis"]) < period_end
            and int(row["availableTimeEpochMillis"]) <= period_end
        )
        for symbol, grouped in itertools.groupby(source, key=lambda row: str(row["symbol"])):
            if symbol in seen_symbols:
                raise ValueError("normalized bars must be grouped by symbol for bounded materialization")
            seen_symbols.add(symbol)
            symbol_rows = materialize_panel(
                grouped,
                memberships,
                tuple(args.horizon),
                args.base_interval_ms,
                args.lag_count,
                (),
                enrich_cross_assets=False,
            )
            path = temporary_root / f"{symbol}.jsonl"
            with path.open("wb") as handle:
                for row in symbol_rows:
                    decision = int(row["decisionTimeEpochMillis"])
                    if not period_start <= decision < period_end:
                        continue
                    row = {
                        **row,
                        "targets": {
                            horizon: value if decision + HORIZON_MILLIS[horizon] <= period_end else None
                            for horizon, value in row["targets"].items()
                        },
                    }
                    handle.write(canonical_json_bytes(row))
            symbol_paths.append(path)

        def merged_rows():
            nonlocal row_count
            iterators = [iter(iter_jsonl(path)) for path in symbol_paths]
            heap: list[tuple[int, str, int, dict]] = []
            for index, iterator in enumerate(iterators):
                try:
                    row = next(iterator)
                except StopIteration:
                    continue
                heapq.heappush(heap, (int(row["decisionTimeEpochMillis"]), str(row["symbol"]), index, row))
            while heap:
                decision = heap[0][0]
                contemporaneous = []
                while heap and heap[0][0] == decision:
                    _, _, index, row = heapq.heappop(heap)
                    contemporaneous.append(row)
                    try:
                        following = next(iterators[index])
                    except StopIteration:
                        continue
                    heapq.heappush(heap, (
                        int(following["decisionTimeEpochMillis"]),
                        str(following["symbol"]),
                        index,
                        following,
                    ))
                for row in enrich_cross_asset_features(contemporaneous, ("BTC", "ETH")):
                    row_count += 1
                    yield row

        panel_hash = write_once_records(output, merged_rows())
    finally:
        shutil.rmtree(temporary_root, ignore_errors=True)
    manifest = {
        "schemaVersion": "marketlab.directional-panel-manifest.v1",
        "panelPath": str(output.absolute()),
        "panelSha256": panel_hash,
        "barsPath": str(bars_path.absolute()),
        "barsSha256": sha256_file(bars_path),
        "universePath": str(Path(args.universe).absolute()),
        "universeSha256": sha256_file(Path(args.universe)),
        "basketSize": args.basket_size,
        "baseIntervalMillis": args.base_interval_ms,
        "horizons": list(args.horizon),
        "outcomePeriod": {
            "startInclusive": args.period_start.isoformat().replace("+00:00", "Z"),
            "endExclusive": args.period_end.isoformat().replace("+00:00", "Z"),
        },
        "rows": row_count,
    }
    manifest_path = output.with_suffix(output.suffix + ".manifest.json")
    manifest["artifactSha256"] = write_once_json(manifest_path, manifest)
    print(json.dumps({"rows": len(rows), "panelSha256": panel_hash, "manifest": str(manifest_path)}))


def probe_gpu(args: argparse.Namespace) -> None:
    from marketlab_alpha.models import probe_gpu_environment

    probe = probe_gpu_environment()
    value = probe.to_dict() if hasattr(probe, "to_dict") else probe
    digest = write_once_json(Path(args.output), value)
    print(json.dumps({"artifactSha256": digest, "cudaAvailable": value.get("available", False)}))


def search(args: argparse.Namespace) -> None:
    from marketlab_alpha.search import run_development_search

    result = run_development_search(
        panel_path=Path(args.panel),
        lock_path=Path(args.lock),
        output_directory=Path(args.output),
        confirmation_ledger_root=Path(args.confirmation_ledger_root),
        maximum_trials=args.maximum_trials,
        test_mode=args.test_mode,
    )
    print(json.dumps({
        "stage": "EXPLORATORY",
        "selected": len(result["selected"]),
        "artifactSha256": result["artifactSha256"],
    }))


def freeze(args: argparse.Namespace) -> None:
    value = freeze_candidate(
        Path(args.search),
        Path(args.lock),
        args.candidate,
        Path(args.confirmation_panel),
        Path(args.output),
    )
    print(json.dumps({
        "candidateId": args.candidate,
        "stage": value["stage"],
        "artifactSha256": value["artifactSha256"],
    }))


def merge_searches(args: argparse.Namespace) -> None:
    from marketlab_alpha.search import merge_development_searches

    value = merge_development_searches([Path(path) for path in args.search], Path(args.lock), Path(args.output))
    print(json.dumps({
        "stage": "EXPLORATORY",
        "selected": len(value["selected"]),
        "artifactSha256": value["artifactSha256"],
    }))


def confirm(args: argparse.Namespace) -> None:
    from marketlab_alpha.search import run_confirmation

    result = run_confirmation(
        Path(args.panel), Path(args.frozen), args.frozen_sha256, Path(args.output)
    )
    print(json.dumps({
        "candidateId": result["candidateId"],
        "stage": result["stage"],
        "decision": result["decision"],
        "inferenceStatus": result["inference"]["status"],
        "artifactSha256": result["artifactSha256"],
    }))


def report(args: argparse.Namespace) -> None:
    confirmation = read_json(Path(args.confirmation))
    lines = [
        f"# Alpha candidate result: {confirmation['candidateId']}",
        "",
        f"- Current epistemic stage: `{confirmation['stage']}`",
        f"- Primary decision: `{confirmation['decision']}`",
        f"- Strongest baseline: `{confirmation['strongestBaseline']}`",
        f"- Mean loss improvement: `{confirmation['meanLossImprovement']:.12g}`",
        f"- Confirmation period opened: `{confirmation['openedOutcomePeriod']}`",
        "",
        "This is predictive evidence only. It does not establish execution, portfolio alpha, or live authorization.",
        "",
    ]
    payload = "\n".join(lines).encode()
    from marketlab_alpha.artifacts import write_once_bytes

    digest = write_once_bytes(Path(args.output), payload)
    print(json.dumps({"artifactSha256": digest}))


def finalize_family(args: argparse.Namespace) -> None:
    from marketlab_alpha.search import finalize_confirmation_family

    value = finalize_confirmation_family(
        [Path(path) for path in args.confirmation],
        args.confirmation_sha256,
        Path(args.family_manifest),
        Path(args.output),
    )
    print(json.dumps({
        "campaignId": value["campaignId"],
        "candidates": len(value["candidateResults"]),
        "stages": {item["candidateId"]: item["stage"] for item in value["candidateResults"]},
    }))


def worker(args: argparse.Namespace) -> None:
    from marketlab_alpha.worker import run_worker

    run_worker(Path(args.manifest), Path(args.output))


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="command", required=True)

    command = commands.add_parser("acquire", help="register immutable local archive inputs")
    command.add_argument("--input", action="append", required=True)
    command.add_argument("--output", required=True)
    command.set_defaults(handler=acquire)

    command = commands.add_parser("discover-binance", help="discover symbols from historical Binance archive prefixes")
    command.add_argument("--market", choices=("um", "cm"), default="um")
    command.add_argument("--data-type", default="klines")
    command.add_argument("--symbol-pattern", default=r"^[A-Z0-9]+USDT$")
    command.add_argument("--bucket-url", default="https://s3-ap-northeast-1.amazonaws.com/data.binance.vision")
    command.add_argument("--output-root", required=True)
    command.set_defaults(handler=discover_binance)

    command = commands.add_parser("download-binance", help="acquire checksum-verified official monthly futures archives")
    command.add_argument("--market", choices=("um", "cm"), default="um")
    command.add_argument("--data-type", required=True)
    command.add_argument("--symbols", required=True, help="one uppercase venue symbol per line")
    command.add_argument("--start-month", required=True)
    command.add_argument("--end-month", required=True)
    command.add_argument("--interval")
    command.add_argument("--output-root", required=True)
    command.add_argument("--workers", type=int, default=8)
    command.set_defaults(handler=download_binance)

    command = commands.add_parser("normalize-binance", help="normalize verified Binance kline objects into causal bars")
    command.add_argument("--manifest", required=True)
    command.add_argument("--output", required=True)
    command.set_defaults(handler=normalize_binance)

    command = commands.add_parser("universe-observations", help="aggregate complete UTC days for causal liquidity ranks")
    command.add_argument("--bars", required=True)
    command.add_argument("--output", required=True)
    command.set_defaults(handler=universe_observations)

    command = commands.add_parser("worker", help="execute the isolated runner FitPredict protocol")
    command.add_argument("--manifest", required=True)
    command.add_argument("--output", required=True)
    command.set_defaults(handler=worker)

    command = commands.add_parser("universe", help="reconstruct weekly point-in-time baskets")
    command.add_argument("--observations", required=True)
    command.add_argument("--first-as-of", required=True, type=parse_instant)
    command.add_argument("--last-as-of", required=True, type=parse_instant)
    command.add_argument("--basket-size", action="append", type=int, default=[])
    command.add_argument("--output", required=True)
    command.set_defaults(handler=universe)

    command = commands.add_parser("universe-symbols", help="freeze the venue-symbol union selected by point-in-time baskets")
    command.add_argument("--universe", required=True)
    command.add_argument("--quote-suffix", default="USDT")
    command.add_argument("--output", required=True)
    command.set_defaults(handler=universe_symbols)

    command = commands.add_parser("materialize", help="create causal multi-horizon panel")
    command.add_argument("--bars", required=True)
    command.add_argument("--universe", required=True)
    command.add_argument("--basket-size", type=int, required=True)
    command.add_argument("--base-interval-ms", type=int, default=300_000)
    command.add_argument("--lag-count", type=int, default=32)
    command.add_argument("--horizon", action="append", choices=tuple(HORIZON_MILLIS), default=[])
    command.add_argument("--period-start", required=True, type=parse_instant)
    command.add_argument("--period-end", required=True, type=parse_instant)
    command.add_argument("--output", required=True)
    command.set_defaults(handler=materialize)

    command = commands.add_parser("probe-gpu", help="publish GPU/runtime identity")
    command.add_argument("--output", required=True)
    command.set_defaults(handler=probe_gpu)

    command = commands.add_parser("search", help="run bounded purged chronological development search")
    command.add_argument("--panel", required=True)
    command.add_argument("--lock", required=True)
    command.add_argument("--output", required=True)
    command.add_argument("--confirmation-ledger-root", required=True)
    command.add_argument("--maximum-trials", type=int)
    command.add_argument("--test-mode", action="store_true")
    command.set_defaults(handler=search)

    command = commands.add_parser("freeze", help="freeze one selected development candidate")
    command.add_argument("--search", required=True)
    command.add_argument("--lock", required=True)
    command.add_argument("--candidate", required=True)
    command.add_argument("--confirmation-panel", required=True)
    command.add_argument("--output", required=True)
    command.set_defaults(handler=freeze)

    command = commands.add_parser("merge-searches", help="select the frozen family across basket-size searches")
    command.add_argument("--search", action="append", required=True)
    command.add_argument("--lock", required=True)
    command.add_argument("--output", required=True)
    command.set_defaults(handler=merge_searches)

    command = commands.add_parser("confirm", help="consume a candidate confirmation period once")
    command.add_argument("--panel", required=True)
    command.add_argument("--frozen", required=True)
    command.add_argument("--frozen-sha256", required=True)
    command.add_argument("--output", required=True)
    command.set_defaults(handler=confirm)

    command = commands.add_parser("report", help="render a concise predictive-evidence report")
    command.add_argument("--confirmation", required=True)
    command.add_argument("--output", required=True)
    command.set_defaults(handler=report)

    command = commands.add_parser("finalize-family", help="apply Holm correction to the complete confirmation family")
    command.add_argument("--confirmation", action="append", required=True)
    command.add_argument("--confirmation-sha256", action="append", required=True)
    command.add_argument("--family-manifest", required=True)
    command.add_argument("--output", required=True)
    command.set_defaults(handler=finalize_family)
    return root


def main() -> None:
    args = parser().parse_args()
    if args.command == "universe" and not args.basket_size:
        args.basket_size = [4, 6, 10]
    if args.command == "materialize" and not args.horizon:
        args.horizon = list(HORIZON_MILLIS)
    args.handler(args)


if __name__ == "__main__":
    main()
