"""Focused historical V3 confirmation and Hyperliquid transfer diagnostic."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import math
import pickle
import tempfile
import urllib.request
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence

from .artifacts import (
    canonical_json_bytes,
    iter_jsonl,
    read_json,
    sha256_file,
    write_once_bytes,
    write_once_json,
)
from .contracts import canonical_sha256
from .panel import HORIZON_MILLIS, materialize_panel


LOCK_SCHEMA = "marketlab.alpha-focused-v3-lock.v1"
FROZEN_SCHEMA = "marketlab.alpha-focused-v3-frozen.v1"
RESULT_SCHEMA = "marketlab.alpha-focused-v3-confirmation-result.v1"
TRANSFER_ACQUISITION_SCHEMA = "marketlab.alpha-hyperliquid-transfer-acquisition.v1"
TRANSFER_RESULT_SCHEMA = "marketlab.alpha-hyperliquid-transfer-diagnostic.v1"


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _instant_ms(value: str) -> int:
    return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)


def _mean(values: Sequence[float]) -> float:
    if not values:
        raise ValueError("cannot average an empty sequence")
    return math.fsum(values) / len(values)


def _verify(path: Path, expected: str, label: str) -> None:
    if not path.is_file() or sha256_file(path) != expected:
        raise ValueError(f"{label} differs from the focused V3 lock: {path}")


def _validate_lock(value: Mapping[str, Any]) -> dict[str, Any]:
    lock = dict(value)
    if lock.get("schemaVersion") != LOCK_SCHEMA:
        raise ValueError("unsupported focused V3 lock")
    if lock.get("stage") != "FROZEN_CANDIDATE":
        raise ValueError("focused V3 lock must remain FROZEN_CANDIDATE")
    selection = lock.get("selection", {})
    expected = {
        "basketSize": 4,
        "horizon": "15m",
        "target": "outright-return",
        "factorRepresentation": "none",
        "modelId": "extra_trees",
        "seed": 19870403,
        "configuration": {"max_depth": 4, "min_samples_leaf": 5, "n_estimators": 400},
    }
    if selection != expected:
        raise ValueError("focused V3 selection differs from the audited specification")
    period = lock.get("confirmationPeriod", {})
    if period != {"startInclusive": "2025-06-01T00:00:00Z", "endExclusive": "2026-08-01T00:00:00Z"}:
        raise ValueError("focused V3 confirmation period changed")
    if lock.get("featureNames") != [
        "basket_latest_return",
        "cross_section_return_rank",
        "latest_return",
        "log_quote_volume",
        "log_range",
        "log_trade_count",
        "mean_return",
        "realized_variance",
        "volume_change",
    ]:
        raise ValueError("focused V3 feature schema changed")
    return lock


def _selected(search: Mapping[str, Any], candidate_id: str) -> dict[str, Any]:
    matches = [item for item in search.get("selected", []) if item.get("candidateId") == candidate_id]
    if len(matches) != 1:
        raise ValueError("focused candidate must appear exactly once in the completed search result")
    return matches[0]


def _load_verified_bundle(model_path: Path, expected_sha256: str) -> dict[str, Any]:
    _verify(model_path, expected_sha256, "model artifact")
    with model_path.open("rb") as handle:
        bundle = pickle.load(handle)
    required = {"estimator", "featureNames", "configuration", "modelId", "horizon", "targetState", "marketBaseline"}
    if not required.issubset(bundle):
        raise ValueError("verified model bundle is incomplete")
    return bundle


def _bundle_schema(bundle: Mapping[str, Any]) -> dict[str, Any]:
    market = bundle["marketBaseline"]
    return {
        "featureNames": list(bundle["featureNames"]),
        "marketBaselineFeatureNames": list(market["featureNames"]),
        "symbols": list(bundle["symbols"]),
        "unknownSymbolPolicy": bundle["unknownSymbolPolicy"],
    }


def freeze_focused_v3(
    lock_path: Path,
    audit_path: Path,
    search_path: Path,
    model_path: Path,
    development_panel_path: Path,
    confirmation_panel_path: Path,
    confirmation_manifest_path: Path,
    confirmation_ledger_root: Path,
    output: Path,
) -> dict[str, Any]:
    lock = _validate_lock(read_json(lock_path))
    if output.exists():
        raise FileExistsError(f"write-once focused freeze exists: {output}")
    if not confirmation_ledger_root.is_dir() or any(path.is_file() for path in confirmation_ledger_root.rglob("*")):
        raise ValueError("confirmation ledger must exist and remain empty before freeze")
    artifacts = lock["artifacts"]
    _verify(audit_path, artifacts["v2AuditSha256"], "V2 evidence audit")
    _verify(search_path, artifacts["v2SearchResultSha256"], "V2 search result")
    _verify(development_panel_path, artifacts["developmentPanelSha256"], "development panel")
    _verify(confirmation_panel_path, artifacts["confirmationPanelSha256"], "sealed confirmation panel")
    _verify(confirmation_manifest_path, artifacts["confirmationPanelManifestSha256"], "confirmation panel manifest")
    search = read_json(search_path)
    selected = _selected(search, lock["sourceCandidateId"])
    if selected.get("selection") != lock["sourceSelection"]:
        raise ValueError("selected V2 specification changed")
    if selected.get("configuration") != lock["selection"]["configuration"] or selected.get("seed") != lock["selection"]["seed"]:
        raise ValueError("selected V2 configuration or seed changed")
    if selected.get("modelArtifactSha256") != artifacts["modelArtifactSha256"]:
        raise ValueError("selected V2 model identity changed")
    bundle = _load_verified_bundle(model_path, artifacts["modelArtifactSha256"])
    if list(bundle["featureNames"]) != lock["featureNames"]:
        raise ValueError("verified bundle feature names differ from the V3 lock")
    if bundle["configuration"] != lock["selection"]["configuration"] or bundle["modelId"] != lock["selection"]["modelId"]:
        raise ValueError("verified bundle estimator differs from the V3 lock")
    if canonical_sha256(_bundle_schema(bundle)) != selected.get("featureSchemaSha256"):
        raise ValueError("reconstructed feature schema differs from the selected V2 artifact")
    manifest = read_json(confirmation_manifest_path)
    if manifest.get("panelSha256") != artifacts["confirmationPanelSha256"]:
        raise ValueError("confirmation panel differs from its manifest")
    if manifest.get("outcomePeriod") != lock["confirmationPeriod"] or manifest.get("basketSize") != 4:
        raise ValueError("confirmation panel period or basket differs from the V3 lock")
    placebo = selected.get("timeShiftPlacebo", {})
    folds = placebo.get("outerFoldImprovements", [])
    suspicious = (
        placebo.get("status") != "COMPLETED"
        or (placebo.get("meanOuterImprovement", 0.0) > 0.0 and sum(value > 0 for value in folds) > len(folds) // 2)
    )
    if suspicious:
        raise ValueError("selected V2 time-shift integrity gate is not clean")
    frozen = {
        "schemaVersion": FROZEN_SCHEMA,
        "campaignId": lock["campaignId"],
        "candidateId": lock["candidateId"],
        "stage": "FROZEN_CANDIDATE",
        "frozenAt": _utc_now(),
        "focusedLockPath": str(lock_path.resolve()),
        "focusedLockSha256": sha256_file(lock_path),
        "implementationPath": str(Path(__file__).resolve()),
        "implementationSha256": sha256_file(Path(__file__)),
        "auditPath": str(audit_path.resolve()),
        "auditSha256": sha256_file(audit_path),
        "searchResultPath": str(search_path.resolve()),
        "searchResultSha256": sha256_file(search_path),
        "selectedTrialId": selected["trialId"],
        "selection": lock["selection"],
        "featureNames": lock["featureNames"],
        "featureSchemaSha256": selected["featureSchemaSha256"],
        "modelArtifactPath": str(model_path.resolve()),
        "modelArtifactSha256": sha256_file(model_path),
        "developmentPanelPath": str(development_panel_path.resolve()),
        "developmentPanelSha256": sha256_file(development_panel_path),
        "confirmationPanelPath": str(confirmation_panel_path.resolve()),
        "confirmationPanelSha256": sha256_file(confirmation_panel_path),
        "confirmationPanelManifestPath": str(confirmation_manifest_path.resolve()),
        "confirmationPanelManifestSha256": sha256_file(confirmation_manifest_path),
        "confirmationPeriod": lock["confirmationPeriod"],
        "confirmationLedgerRoot": str(confirmation_ledger_root.resolve()),
        "confirmationMarkerRelativePath": f"focused-v3/{lock['candidateId']}-confirmation-1.json",
        "acceptance": lock["acceptance"],
        "developmentPlacebo": {
            "status": placebo["status"],
            "meanImprovement": placebo["meanOuterImprovement"],
            "positiveFolds": placebo["positiveOuterFolds"],
            "folds": len(folds),
            "clean": True,
        },
        "openedOutcomePeriods": [],
        "limitations": lock["limitations"],
        "paperTradingAuthorized": False,
        "liveTradingAuthorized": False,
    }
    artifact_sha256 = write_once_json(output, frozen)
    return {"frozen": frozen, "artifactSha256": artifact_sha256}


def _hac_p_value(times: Sequence[int], differentials: Sequence[float], lag: int) -> float | None:
    by_time: dict[int, list[float]] = defaultdict(list)
    for time_ms, value in zip(times, differentials):
        by_time[int(time_ms)].append(float(value))
    series = [_mean(by_time[key]) for key in sorted(by_time)]
    n = len(series)
    if n <= lag + 2:
        return None
    average = _mean(series)
    centered = [value - average for value in series]
    long_run = math.fsum(value * value for value in centered) / n
    for offset in range(1, min(lag, n - 2) + 1):
        covariance = math.fsum(centered[index] * centered[index - offset] for index in range(offset, n)) / n
        long_run += 2.0 * (1.0 - offset / (lag + 1.0)) * covariance
    if not math.isfinite(long_run) or long_run <= 0.0:
        return None
    standard_error = math.sqrt(long_run / n)
    return 0.5 * math.erfc((average / standard_error) / math.sqrt(2.0))


def _score_rows(rows: list[dict[str, Any]], bundle: Mapping[str, Any]) -> dict[str, Any]:
    from .search import (
        _apply_target_state,
        _apply_target_state_to_values,
        _panel_dataset,
        squared_losses,
        strongest_baseline_improvement,
    )

    horizon = bundle["horizon"]
    x, aligned = _panel_dataset(rows, bundle["featureNames"], horizon, bundle["modelId"], bundle["lookback"], bundle.get("symbols"))
    y = _apply_target_state(aligned, horizon, bundle["targetState"])
    prediction = bundle["estimator"].predict(x)
    persistence = _apply_target_state_to_values(
        aligned,
        [float(row["features"][f"persistence_return_{horizon}"]) for row in aligned],
        bundle["targetState"],
    )
    controls: dict[str, Any] = {
        "zero-return": [0.0] * len(aligned),
        "historical-mean": [float(bundle["historicalMean"])] * len(aligned),
        "persistence": persistence,
        "reversal": [-float(value) for value in persistence],
    }
    market = bundle["marketBaseline"]
    market_x, market_aligned = _panel_dataset(rows, market["featureNames"], market["horizon"], market["modelId"], market["lookback"], market.get("symbols"))
    identifiers = [(row["decisionTimeEpochMillis"], row["symbol"]) for row in aligned]
    if identifiers != [(row["decisionTimeEpochMillis"], row["symbol"]) for row in market_aligned]:
        raise ValueError("market baseline and candidate rows do not align")
    controls["strongest-market-only"] = market["estimator"].predict(market_x)
    improvement, strongest = strongest_baseline_improvement(y, prediction, controls)
    candidate_losses = list(squared_losses(y, prediction))
    baseline_losses = list(squared_losses(y, controls[strongest]))
    differentials = [float(baseline - candidate) for baseline, candidate in zip(baseline_losses, candidate_losses)]
    return {
        "rows": aligned,
        "candidateLosses": candidate_losses,
        "baselineLosses": baseline_losses,
        "differentials": differentials,
        "strongestBaseline": strongest,
        "meanCandidateLoss": _mean(candidate_losses),
        "meanBaselineLoss": _mean(baseline_losses),
        "meanImprovement": improvement,
        "relativeImprovement": improvement / _mean(baseline_losses),
        "baselineMetrics": {name: _mean(list(squared_losses(y, values))) for name, values in controls.items()},
    }


def confirm_focused_v3(frozen_path: Path, expected_frozen_sha256: str, output: Path) -> dict[str, Any]:
    if sha256_file(frozen_path) != expected_frozen_sha256:
        raise ValueError("focused frozen record differs from the externally registered hash")
    frozen = read_json(frozen_path)
    if frozen.get("schemaVersion") != FROZEN_SCHEMA:
        raise ValueError("unsupported focused frozen record")
    if output.exists():
        raise FileExistsError(f"write-once focused confirmation result exists: {output}")
    _verify(Path(frozen["focusedLockPath"]), frozen["focusedLockSha256"], "focused lock")
    _verify(Path(frozen["implementationPath"]), frozen["implementationSha256"], "focused V3 implementation")
    _verify(Path(frozen["auditPath"]), frozen["auditSha256"], "V2 audit")
    _verify(Path(frozen["searchResultPath"]), frozen["searchResultSha256"], "V2 search result")
    _verify(Path(frozen["modelArtifactPath"]), frozen["modelArtifactSha256"], "model artifact")
    _verify(Path(frozen["confirmationPanelPath"]), frozen["confirmationPanelSha256"], "confirmation panel")
    _verify(Path(frozen["confirmationPanelManifestPath"]), frozen["confirmationPanelManifestSha256"], "confirmation manifest")
    marker = Path(frozen["confirmationLedgerRoot"]) / frozen["confirmationMarkerRelativePath"]
    marker_payload = {
        "schemaVersion": "marketlab.alpha-focused-v3-confirmation-marker.v1",
        "campaignId": frozen["campaignId"],
        "candidateId": frozen["candidateId"],
        "confirmationId": f"{frozen['candidateId']}-confirmation-1",
        "frozenSha256": expected_frozen_sha256,
        "openedAt": _utc_now(),
        "outcomePeriod": frozen["confirmationPeriod"],
    }
    write_once_json(marker, marker_payload)
    # Outcomes may be read only after the durable marker above exists.
    bundle = _load_verified_bundle(Path(frozen["modelArtifactPath"]), frozen["modelArtifactSha256"])
    start, end = (_instant_ms(frozen["confirmationPeriod"][key]) for key in ("startInclusive", "endExclusive"))
    rows = [
        row for row in iter_jsonl(Path(frozen["confirmationPanelPath"]))
        if start <= int(row["decisionTimeEpochMillis"]) and int(row["decisionTimeEpochMillis"]) + HORIZON_MILLIS["15m"] <= end
        and row.get("targets", {}).get("15m") is not None
    ]
    scored = _score_rows(rows, bundle)
    aligned, differentials = scored["rows"], scored["differentials"]
    p_value = _hac_p_value([row["decisionTimeEpochMillis"] for row in aligned], differentials, int(frozen["acceptance"]["hacLagFiveMinuteOrigins"]))
    by_month: dict[str, list[float]] = defaultdict(list)
    by_asset: dict[str, list[float]] = defaultdict(list)
    for row, value in zip(aligned, differentials):
        month = datetime.fromtimestamp(int(row["decisionTimeEpochMillis"]) / 1000, timezone.utc).strftime("%Y-%m")
        by_month[month].append(value)
        by_asset[str(row["symbol"])].append(value)
    month_improvements = {key: _mean(values) for key, values in sorted(by_month.items())}
    asset_improvements = {key: _mean(values) for key, values in sorted(by_asset.items())}
    gates = {
        "primaryLossImproved": scored["meanImprovement"] > 0.0,
        "minimumRelativeImprovement": scored["relativeImprovement"] >= float(frozen["acceptance"]["minimumRelativeMseImprovement"]),
        "dependenceAwarePValue": p_value is not None and p_value <= float(frozen["acceptance"]["maximumAdjustedPValue"]),
        "positiveCalendarMonths": sum(value > 0.0 for value in month_improvements.values()) >= int(frozen["acceptance"]["minimumPositiveCalendarMonths"]),
        "mandatoryAssetsPositive": all(asset_improvements.get(symbol, 0.0) > 0.0 for symbol in frozen["acceptance"]["mandatoryPositiveAssets"]),
        "cleanDevelopmentPlacebo": frozen["developmentPlacebo"]["clean"] is True,
    }
    if p_value is None:
        stage, decision = "INCONCLUSIVE", "INVALID_CONFIRMATION_INFERENCE"
    elif all(gates.values()):
        stage, decision = "BLIND_VALIDATED", "PREDICTIVE_SIGNAL_GATE_PASSED"
    else:
        stage, decision = "REJECTED", "PREDICTIVE_SIGNAL_GATE_FAILED"
    result = {
        "schemaVersion": RESULT_SCHEMA,
        "campaignId": frozen["campaignId"],
        "candidateId": frozen["candidateId"],
        "stage": stage,
        "decision": decision,
        "completedAt": _utc_now(),
        "frozenSha256": expected_frozen_sha256,
        "confirmationMarkerPath": str(marker.resolve()),
        "openedOutcomePeriods": [{**frozen["confirmationPeriod"], "openedAt": marker_payload["openedAt"]}],
        "rowCount": len(aligned),
        "strongestBaseline": scored["strongestBaseline"],
        "meanCandidateLoss": scored["meanCandidateLoss"],
        "meanBaselineLoss": scored["meanBaselineLoss"],
        "meanLossImprovement": scored["meanImprovement"],
        "relativeMseImprovement": scored["relativeImprovement"],
        "baselineMetrics": scored["baselineMetrics"],
        "inference": {"method": "decision-time aggregate Newey-West HAC", "lagFiveMinuteOrigins": frozen["acceptance"]["hacLagFiveMinuteOrigins"], "rawPValue": p_value, "adjustedPValue": p_value, "familySize": 1},
        "calendarMonthImprovements": month_improvements,
        "assetImprovements": asset_improvements,
        "gates": gates,
        "predictiveClaimOnly": True,
        "tradabilityTested": False,
        "paperTradingAuthorized": False,
        "liveTradingAuthorized": False,
        "limitations": frozen["limitations"],
    }
    artifact_sha256 = write_once_json(output, result)
    return {"result": result, "artifactSha256": artifact_sha256}


def _load_universe_snapshots(root: Path, start: int, end: int) -> tuple[dict[int, list[str]], list[dict[str, Any]]]:
    memberships: dict[int, list[str]] = {}
    records = []
    for path in sorted(root.rglob("*.json")):
        value = read_json(path)
        if value.get("schemaVersion") != "marketlab.hyperliquid-social-universe.v1":
            continue
        effective = int(value["effectiveFrom"])
        if int(value["effectiveToExclusive"]) <= start or effective >= end:
            continue
        members = [str(item["symbol"]).upper() for item in value["members"][:4]]
        if not {"BTC", "ETH"}.issubset(members):
            raise ValueError(f"point-in-time transfer basket omits BTC or ETH: {path}")
        memberships[effective] = members
        records.append({"path": str(path.resolve()), "sha256": sha256_file(path), "effectiveFrom": effective, "effectiveToExclusive": int(value["effectiveToExclusive"]), "members": members})
    if not memberships or min(memberships) > start:
        raise ValueError("transfer period lacks an effective point-in-time universe at its start")
    return memberships, records


def acquire_hyperliquid_transfer(lock_path: Path, universe_root: Path, output: Path) -> dict[str, Any]:
    lock = _validate_lock(read_json(lock_path))
    period = lock["transferDiagnostic"]["period"]
    start, end = _instant_ms(period["startInclusive"]), _instant_ms(period["endExclusive"])
    if output.exists():
        raise FileExistsError(f"write-once transfer acquisition exists: {output}")
    memberships, snapshots = _load_universe_snapshots(universe_root, start, end)
    symbols = sorted({symbol for members in memberships.values() for symbol in members})
    output.mkdir(parents=True, exist_ok=False)
    objects = []
    maximum_chunk = 12 * 86_400_000
    for symbol in symbols:
        cursor = start
        while cursor < end:
            chunk_end = min(end, cursor + maximum_chunk)
            request_body = canonical_json_bytes({"type": "candleSnapshot", "req": {"coin": symbol, "interval": "5m", "startTime": cursor, "endTime": chunk_end}}).rstrip(b"\n")
            request = urllib.request.Request(
                lock["transferDiagnostic"]["sourceUri"],
                data=request_body,
                headers={"Content-Type": "application/json", "User-Agent": "marketlab-focused-v3/1"},
                method="POST",
            )
            with urllib.request.urlopen(request, timeout=120) as response:
                payload = response.read()
            values = json.loads(payload)
            if not isinstance(values, list):
                raise ValueError("Hyperliquid candle response is not a list")
            relative = Path("raw") / symbol / f"{cursor}-{chunk_end}.json"
            path = output / relative
            write_once_bytes(path, payload)
            objects.append({"symbol": symbol, "startInclusiveEpochMillis": cursor, "endExclusiveEpochMillis": chunk_end, "requestBodySha256": hashlib.sha256(request_body).hexdigest(), "path": str(relative), "sha256": sha256_file(path), "rows": len(values)})
            cursor = chunk_end
    acquisition = {
        "schemaVersion": TRANSFER_ACQUISITION_SCHEMA,
        "campaignId": lock["campaignId"],
        "classification": "RETROSPECTIVE_SOURCE_TRANSFER_DIAGNOSTIC",
        "source": "hyperliquid-mainnet",
        "sourceUri": lock["transferDiagnostic"]["sourceUri"],
        "period": period,
        "acquiredAt": _utc_now(),
        "universeSnapshots": snapshots,
        "objects": objects,
    }
    manifest_sha256 = write_once_json(output / "acquisition-manifest.json", acquisition)
    return {"manifest": acquisition, "artifactSha256": manifest_sha256}


def diagnose_hyperliquid_transfer(
    lock_path: Path,
    frozen_path: Path,
    expected_frozen_sha256: str,
    acquisition_path: Path,
    output: Path,
) -> dict[str, Any]:
    lock = _validate_lock(read_json(lock_path))
    if sha256_file(frozen_path) != expected_frozen_sha256:
        raise ValueError("focused frozen record differs from the transfer registration")
    frozen = read_json(frozen_path)
    acquisition = read_json(acquisition_path)
    if acquisition.get("schemaVersion") != TRANSFER_ACQUISITION_SCHEMA or acquisition.get("period") != lock["transferDiagnostic"]["period"]:
        raise ValueError("transfer acquisition differs from the focused lock")
    if output.exists():
        raise FileExistsError(f"write-once transfer result exists: {output}")
    root = acquisition_path.parent
    bars = []
    for item in acquisition["objects"]:
        path = root / item["path"]
        _verify(path, item["sha256"], "Hyperliquid candle response")
        for candle in json.loads(path.read_bytes()):
            event = int(candle["t"])
            if not (_instant_ms(acquisition["period"]["startInclusive"]) <= event < _instant_ms(acquisition["period"]["endExclusive"])):
                continue
            close, volume = float(candle["c"]), float(candle["v"])
            bars.append({
                "symbol": str(candle.get("s", item["symbol"])).upper(),
                "eventTimeEpochMillis": event,
                "availableTimeEpochMillis": int(candle.get("T", event + 299_999)) + 1,
                "open": float(candle["o"]), "high": float(candle["h"]), "low": float(candle["l"]), "close": close,
                "quoteVolume": volume * close,
                "tradeCount": int(candle.get("n", 0)),
            })
    unique_bars: dict[tuple[str, int], dict[str, Any]] = {}
    for bar in bars:
        key = (bar["symbol"], bar["eventTimeEpochMillis"])
        prior = unique_bars.get(key)
        if prior is not None and prior != bar:
            raise ValueError(f"conflicting Hyperliquid candle at {key}")
        unique_bars[key] = bar
    bars = sorted(unique_bars.values(), key=lambda item: (item["eventTimeEpochMillis"], item["symbol"]))
    memberships = {int(item["effectiveFrom"]): item["members"] for item in acquisition["universeSnapshots"]}
    rows = materialize_panel(bars, memberships, ["15m"], 300_000, 32, ("BTC", "ETH"), True)
    start, end = (_instant_ms(acquisition["period"][key]) for key in ("startInclusive", "endExclusive"))
    rows = [row for row in rows if start <= row["decisionTimeEpochMillis"] and row["decisionTimeEpochMillis"] + HORIZON_MILLIS["15m"] <= end and row["targets"]["15m"] is not None]
    bundle = _load_verified_bundle(Path(frozen["modelArtifactPath"]), frozen["modelArtifactSha256"])
    scored = _score_rows(rows, bundle)
    by_day: dict[str, list[float]] = defaultdict(list)
    by_asset: dict[str, list[float]] = defaultdict(list)
    for row, value in zip(scored["rows"], scored["differentials"]):
        day = datetime.fromtimestamp(row["decisionTimeEpochMillis"] / 1000, timezone.utc).strftime("%Y-%m-%d")
        by_day[day].append(value); by_asset[row["symbol"]].append(value)
    p_value = _hac_p_value([row["decisionTimeEpochMillis"] for row in scored["rows"]], scored["differentials"], 288)
    observed_start = min(bar["eventTimeEpochMillis"] for bar in bars)
    observed_end = max(bar["eventTimeEpochMillis"] for bar in bars) + 300_000
    result = {
        "schemaVersion": TRANSFER_RESULT_SCHEMA,
        "campaignId": lock["campaignId"],
        "candidateId": lock["transferDiagnostic"]["candidateId"],
        "stage": "INCONCLUSIVE",
        "classification": "RETROSPECTIVE_SOURCE_TRANSFER_DIAGNOSTIC",
        "decision": "DESCRIPTIVE_TRANSFER_RESULT_ONLY",
        "completedAt": _utc_now(),
        "period": acquisition["period"],
        "observedCandlePeriod": {
            "startInclusiveEpochMillis": observed_start,
            "endExclusiveEpochMillis": observed_end,
            "calendarDays": (observed_end - observed_start) / 86_400_000,
        },
        "frozenSha256": expected_frozen_sha256,
        "acquisitionManifestSha256": sha256_file(acquisition_path),
        "rowCount": len(scored["rows"]),
        "strongestBaseline": scored["strongestBaseline"],
        "meanLossImprovement": scored["meanImprovement"],
        "relativeMseImprovement": scored["relativeImprovement"],
        "descriptiveHacPValue": p_value,
        "dailyImprovements": {key: _mean(values) for key, values in sorted(by_day.items())},
        "assetImprovements": {key: _mean(values) for key, values in sorted(by_asset.items())},
        "limitations": [
            "Existing historical target-venue data are retrospective, not prospective.",
            "Hyperliquid candle quote volume is reconstructed as base volume times close.",
            "The short source-transfer period cannot validate stability or tradability.",
            "No model parameter, feature, or threshold may be tuned from this diagnostic.",
        ],
        "predictivePromotionPermitted": False,
        "paperTradingAuthorized": False,
        "liveTradingAuthorized": False,
    }
    artifact_sha256 = write_once_json(output, result)
    return {"result": result, "artifactSha256": artifact_sha256}


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    freeze = commands.add_parser("freeze-focused-v3")
    for name in ("lock", "audit", "search", "model", "development-panel", "confirmation-panel", "confirmation-manifest", "confirmation-ledger-root", "output"):
        freeze.add_argument(f"--{name}", type=Path, required=True)
    confirm = commands.add_parser("confirm-focused-v3")
    confirm.add_argument("--frozen", type=Path, required=True); confirm.add_argument("--frozen-sha256", required=True); confirm.add_argument("--output", type=Path, required=True)
    acquire = commands.add_parser("acquire-hyperliquid-transfer")
    acquire.add_argument("--lock", type=Path, required=True); acquire.add_argument("--universe-root", type=Path, required=True); acquire.add_argument("--output", type=Path, required=True)
    transfer = commands.add_parser("diagnose-hyperliquid-transfer")
    transfer.add_argument("--lock", type=Path, required=True); transfer.add_argument("--frozen", type=Path, required=True); transfer.add_argument("--frozen-sha256", required=True); transfer.add_argument("--acquisition", type=Path, required=True); transfer.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    from .budget import require_budget
    require_budget(outcome_access="SINGLE_USE_HISTORICAL_CONFIRMATION" if args.command == "confirm-focused-v3" else None)
    if args.command == "freeze-focused-v3":
        result = freeze_focused_v3(args.lock, args.audit, args.search, args.model, args.development_panel, args.confirmation_panel, args.confirmation_manifest, args.confirmation_ledger_root, args.output)
    elif args.command == "confirm-focused-v3":
        result = confirm_focused_v3(args.frozen, args.frozen_sha256, args.output)
    elif args.command == "acquire-hyperliquid-transfer":
        result = acquire_hyperliquid_transfer(args.lock, args.universe_root, args.output)
    else:
        result = diagnose_hyperliquid_transfer(args.lock, args.frozen, args.frozen_sha256, args.acquisition, args.output)
    print(json.dumps(result, sort_keys=True, default=str))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
