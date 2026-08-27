"""Immutable development-ledger audit for suspended archive campaigns."""

from __future__ import annotations

import argparse
import hashlib
import json
import statistics
from collections import Counter, defaultdict
from pathlib import Path
from typing import Any, Iterable

from .artifacts import canonical_json_bytes, read_json, sha256_file, write_once_bytes, write_once_json


SCHEMA = "marketlab.alpha-development-evidence-audit.v1"
MANIFEST_SCHEMA = "marketlab.alpha-development-ledger-manifest.v1"
EXPECTED_SEEDS = {19870403, 230511, 910237}
OBSERVED_BASKETS = {4, 6}
LOCKED_BASKETS = {4, 6, 10}


def _mean(values: Iterable[float]) -> float | None:
    items = list(values)
    return statistics.fmean(items) if items else None


def _positive_majority(values: list[float]) -> bool:
    return bool(values) and sum(value > 0 for value in values) > len(values) / 2


def _selection(record: dict[str, Any]) -> dict[str, Any]:
    value = record.get("selection")
    if not isinstance(value, dict):
        raise ValueError(f"trial has malformed selection: {record.get('trialId')}")
    return value


def _specification(record: dict[str, Any]) -> dict[str, Any]:
    selection = _selection(record)
    return {
        "candidateId": record["candidateId"],
        "mechanism": record["mechanism"],
        "model": selection["modelFamilies"],
        "horizon": selection["horizons"],
        "target": selection["targets"],
        "factorRepresentation": selection["factorRepresentations"],
        "informationSet": selection["informationSets"],
        "variant": selection["variants"],
        "configuration": record.get("configuration", {}),
        "configurationIndex": record.get("configurationIndex"),
    }


def _specification_key(record: dict[str, Any]) -> str:
    return canonical_json_bytes(_specification(record)).decode().strip()


def _manifest_entry(path: Path, relative_path: str, kind: str) -> dict[str, Any]:
    if not path.is_file():
        raise ValueError(f"audit input is not a regular file: {path}")
    return {
        "kind": kind,
        "path": relative_path,
        "sha256": sha256_file(path),
        "sizeBytes": path.stat().st_size,
    }


def _validate_confirmation_ledger(root: Path) -> None:
    if not root.is_dir():
        raise ValueError(f"confirmation ledger root does not exist: {root}")
    opened = sorted(path for path in root.rglob("*") if path.is_file())
    if opened:
        raise ValueError(f"confirmation ledger is not sealed: {opened[0]}")


def _load_search(
    basket: int,
    root: Path,
    campaign_id: str,
) -> tuple[list[dict[str, Any]], dict[str, Any] | None, list[dict[str, Any]], list[dict[str, Any]]]:
    trial_paths = sorted(root.glob("checkpoints/trials/*/trial.json"))
    if not trial_paths:
        raise ValueError(f"search-{basket} contains no durable trial records")
    trials: list[dict[str, Any]] = []
    manifest_entries: list[dict[str, Any]] = []
    for path in trial_paths:
        record = read_json(path)
        trial_id = record.get("trialId")
        if record.get("schemaVersion") != "marketlab.alpha-trial-ledger-entry.v1" or not trial_id:
            raise ValueError(f"malformed trial ledger entry: {path}")
        if record.get("campaignId") != campaign_id:
            raise ValueError(f"trial campaign mismatch: {trial_id}")
        selected_basket = _selection(record).get("basketSizes")
        if selected_basket != basket:
            raise ValueError(f"trial basket mismatch: {trial_id}")
        if record.get("status") not in {"COMPLETED", "FAILED"}:
            raise ValueError(f"non-durable trial state: {trial_id}")
        relative = f"search-{basket}/{path.relative_to(root)}"
        entry = _manifest_entry(path, relative, "trial-ledger-entry")
        manifest_entries.append(entry)
        trials.append(record)

    plan_entries: list[dict[str, Any]] = []
    planned_ids: set[str] = set()
    for path in sorted(root.glob("checkpoints/plans/*.json")):
        plan = read_json(path)
        if plan.get("schemaVersion") != "marketlab.alpha-trial-plan.v1" or plan.get("campaignId") != campaign_id:
            raise ValueError(f"malformed or mismatched immutable plan: {path}")
        plan_identity = hashlib.sha256(
            json.dumps(plan, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
        ).hexdigest()[:16]
        if plan_identity not in path.name:
            raise ValueError(f"modified immutable plan: {path}")
        for task in plan.get("orderedTasks", []):
            trial_id = task.get("trialId")
            if not trial_id or trial_id in planned_ids:
                raise ValueError(f"duplicate or missing planned trial id: {trial_id}")
            planned_ids.add(trial_id)
        entry = _manifest_entry(path, f"search-{basket}/{path.relative_to(root)}", "immutable-trial-plan")
        manifest_entries.append(entry)
        plan_entries.append(entry)

    result_path = root / "search-result.json"
    result = None
    if result_path.exists():
        result = read_json(result_path)
        if result.get("campaignId") != campaign_id or result.get("basketSize") != basket:
            raise ValueError(f"completed search result mismatch: {result_path}")
        manifest_entries.append(
            _manifest_entry(result_path, f"search-{basket}/search-result.json", "completed-search-result")
        )
    status_path = root / "status.json"
    if status_path.exists():
        manifest_entries.append(_manifest_entry(status_path, f"search-{basket}/status.json", "operational-report"))
    return trials, result, manifest_entries, plan_entries


def _placebo_for(result: dict[str, Any] | None, specification: dict[str, Any]) -> dict[str, Any] | None:
    if result is None:
        return None
    candidates = list(result.get("developmentCandidates", [])) + list(result.get("selected", []))
    expected = dict(specification)
    expected.pop("configurationIndex", None)
    for candidate in candidates:
        try:
            observed = _specification(candidate)
            observed.pop("configurationIndex", None)
            if canonical_json_bytes(observed) == canonical_json_bytes(expected):
                placebo = candidate.get("timeShiftPlacebo")
                if isinstance(placebo, dict) and placebo.get("status") == "COMPLETED":
                    return placebo
        except (KeyError, TypeError, ValueError):
            continue
    return None


def _evaluate_group(records: list[dict[str, Any]], result: dict[str, Any] | None) -> dict[str, Any]:
    records = sorted(records, key=lambda item: item["seed"])
    specification = _specification(records[0])
    improvements = [value for item in records for value in item.get("outerFoldImprovements", [])]
    market = [value for item in records for value in item.get("outerFoldMarketOnlyImprovements", [])]
    naive = [value for item in records for value in item.get("outerFoldNaiveImprovements", [])]
    losses = [value for item in records for value in item.get("outerFoldCandidateLosses", [])]
    placebo = _placebo_for(result, specification)
    placebo_folds = [] if placebo is None else list(placebo.get("outerFoldImprovements", []))
    seed_evidence = [
        {
            "seed": item["seed"],
            "meanImprovement": _mean(item.get("outerFoldImprovements", [])),
            "positiveFolds": sum(value > 0 for value in item.get("outerFoldImprovements", [])),
            "folds": len(item.get("outerFoldImprovements", [])),
            "majorityPositive": _positive_majority(item.get("outerFoldImprovements", [])),
        }
        for item in records
    ]
    candidate_loss = _mean(losses)
    absolute = _mean(improvements)
    baseline_loss = None if candidate_loss is None or absolute is None else candidate_loss + absolute
    gates = {
        "allRegisteredSeeds": {item["seed"] for item in records} == EXPECTED_SEEDS,
        "majorityPositiveEverySeed": bool(seed_evidence) and all(item["majorityPositive"] for item in seed_evidence),
        "improvesMarketOnlyControl": _positive_majority(market) and (_mean(market) or 0.0) > 0.0,
        "improvesStrongestNaiveControl": _positive_majority(naive) and (_mean(naive) or 0.0) > 0.0,
        "cleanTimeShiftPlacebo": bool(placebo_folds)
        and not (_positive_majority(placebo_folds) and (_mean(placebo_folds) or 0.0) > 0.0),
    }
    return {
        "specification": specification,
        "trialIds": [item["trialId"] for item in records],
        "seedEvidence": seed_evidence,
        "absoluteForecastLossImprovement": absolute,
        "relativeForecastLossImprovement": None
        if baseline_loss in {None, 0.0} or absolute is None
        else absolute / baseline_loss,
        "marketOnlyImprovement": _mean(market),
        "strongestNaiveImprovement": _mean(naive),
        "positiveFolds": sum(value > 0 for value in improvements),
        "folds": len(improvements),
        "placebo": None
        if placebo is None
        else {
            "meanImprovement": _mean(placebo_folds),
            "positiveFolds": sum(value > 0 for value in placebo_folds),
            "folds": len(placebo_folds),
        },
        "gates": gates,
        "passes": all(gates.values()),
    }


def _search_summary(
    basket: int,
    trials: list[dict[str, Any]],
    result: dict[str, Any] | None,
    plan_count: int,
) -> tuple[dict[str, Any], dict[str, dict[str, Any]]]:
    statuses = Counter(item["status"] for item in trials)
    by_candidate: dict[str, dict[str, Any]] = {}
    evaluated: dict[str, dict[str, Any]] = {}
    grouped: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for item in trials:
        if item["status"] == "COMPLETED" and item.get("searchStage") == "ROBUSTNESS":
            grouped[_specification_key(item)].append(item)
    for key, records in grouped.items():
        evaluated[key] = _evaluate_group(records, result)
    for candidate_id in sorted({item["candidateId"] for item in trials}):
        candidate_trials = [item for item in trials if item["candidateId"] == candidate_id]
        completed = [item for item in candidate_trials if item["status"] == "COMPLETED"]
        candidate_groups = [value for value in evaluated.values() if value["specification"]["candidateId"] == candidate_id]
        leader = max(
            candidate_groups,
            key=lambda value: value["absoluteForecastLossImprovement"] or float("-inf"),
            default=None,
        )
        by_candidate[candidate_id] = {
            "attemptedTrials": len(candidate_trials),
            "completedTrials": len(completed),
            "failedTrials": len(candidate_trials) - len(completed),
            "rungs": dict(sorted(Counter(item.get("searchStage", "UNKNOWN") for item in candidate_trials).items())),
            "models": dict(sorted(Counter(_selection(item)["modelFamilies"] for item in candidate_trials).items())),
            "strongestCompletedRobustnessSpecification": leader,
        }
    return (
        {
            "basketSize": basket,
            "searchComplete": result is not None,
            "attemptedTrials": len(trials),
            "completedTrials": statuses["COMPLETED"],
            "failedTrials": statuses["FAILED"],
            "immutablePlanCount": plan_count,
            "candidates": by_candidate,
        },
        evaluated,
    )


def _render(audit: dict[str, Any], audit_sha256: str) -> str:
    searches = audit["searches"]
    accounting = ", ".join(
        f"basket {item['basketSize']}: {item['completedTrials']} completed/{item['failedTrials']} failed"
        for item in searches
    )
    return f"""# Archive Directional V2 development evidence audit

## Decision

**{audit['recommendation']}**

## What we tested

The registered archive-backed directional development family on baskets 4 and 6. Trial ledgers, immutable plans, failures, controls, seeds, folds, and available time-shift placebos were audited. Basket 10 was not run.

## Current epistemic stage

`{audit['stage']}`. This is open, nonblind development evidence only; it is not confirmation, tradability, portfolio, paper, or live evidence.

## What happened

{accounting}. The exhaustive campaign was suspended before basket 10 and before confirmation.

## What we know

The audit recommendation is `{audit['recommendation']}` under the preregistered focused-V3 gate. Confirmation ledger files observed: 0.

## What we suspect

Positive development loss differences may identify specifications worth a smaller fresh campaign, but incomplete cross-basket family accounting and absent dependence-aware inference can also explain apparent leaders.

## What remains untested

Basket 10, a complete development family on basket 6, dependence-aware and family-wide inference, economic magnitude after costs, Hyperliquid transfer, prospective stability, tradability, portfolio construction, and operations.

## Opened outcomes and periods that are no longer blind

The development period `{audit['developmentPeriod']['startInclusive']}` through `{audit['developmentPeriod']['endExclusive']}` has been opened repeatedly and is not eligible as untouched evidence. The registered confirmation period remains sealed and unopened.

## What data or authority would change the answer

A fresh preregistered development or prospective period, complete point-in-time target-venue data, and later explicit authority for each promotion gate. No capital authority was requested or granted.

## Next permitted action

{audit['nextPermittedAction']}

## Immutable identities

- Ledger manifest SHA-256: `{audit['ledgerManifestSha256']}`
- Audit JSON SHA-256: `{audit_sha256}`
"""


def audit_development(
    campaign_lock_path: Path,
    searches: dict[int, Path],
    confirmation_ledger_root: Path,
    output: Path,
    suspension_record: Path | None = None,
) -> dict[str, Any]:
    if output.exists():
        raise FileExistsError(f"write-once audit output already exists: {output}")
    campaign = read_json(campaign_lock_path)
    campaign_id = campaign.get("campaignId")
    if campaign.get("schemaVersion") != "marketlab.alpha-campaign-lock.v1" or not campaign_id:
        raise ValueError("unsupported campaign lock")
    locked_baskets = set(campaign.get("searchDimensions", {}).get("basketSizes", []))
    if locked_baskets != LOCKED_BASKETS:
        raise ValueError("campaign basket family mismatch")
    if set(searches) != OBSERVED_BASKETS:
        raise ValueError("evidence audit requires exactly basket 4 and basket 6 inputs")
    _validate_confirmation_ledger(confirmation_ledger_root)

    manifest_entries = [_manifest_entry(campaign_lock_path, "campaign.lock.json", "campaign-lock")]
    loaded: dict[int, tuple[list[dict[str, Any]], dict[str, Any] | None, int]] = {}
    all_trial_ids: set[str] = set()
    for basket, root in sorted(searches.items()):
        trials, result, entries, plans = _load_search(basket, root, campaign_id)
        for trial in trials:
            if trial["trialId"] in all_trial_ids:
                raise ValueError(f"duplicate trial id across ledgers: {trial['trialId']}")
            all_trial_ids.add(trial["trialId"])
        manifest_entries.extend(entries)
        loaded[basket] = (trials, result, len(plans))
    if suspension_record is not None:
        manifest_entries.append(_manifest_entry(suspension_record, "suspension.json", "suspension-record"))
    manifest = {
        "schemaVersion": MANIFEST_SCHEMA,
        "campaignId": campaign_id,
        "entries": sorted(manifest_entries, key=lambda item: item["path"]),
    }

    search_summaries = []
    evaluated_by_basket: dict[int, dict[str, dict[str, Any]]] = {}
    for basket, (trials, result, plan_count) in sorted(loaded.items()):
        summary, evaluated = _search_summary(basket, trials, result, plan_count)
        search_summaries.append(summary)
        evaluated_by_basket[basket] = evaluated

    common_keys = set(evaluated_by_basket[4]) & set(evaluated_by_basket[6])
    eligible = []
    for key in sorted(common_keys):
        evidence = {str(basket): evaluated_by_basket[basket][key] for basket in sorted(OBSERVED_BASKETS)}
        if all(value["passes"] for value in evidence.values()):
            eligible.append({"specification": evidence["4"]["specification"], "basketEvidence": evidence})
    all_complete = all(item["searchComplete"] for item in search_summaries)
    if eligible:
        recommendation = "FOCUSED_V3_JUSTIFIED"
        next_action = "Write, review, and freeze one focused V3 lock around the single cross-basket specification; do not launch or open confirmation automatically."
    elif all_complete:
        recommendation = "REJECT"
        next_action = "Record the completed family rejection. A new candidate requires a materially distinct mechanism, information set, or fresh preregistered period."
    else:
        recommendation = "INSUFFICIENT_EVIDENCE"
        next_action = "Keep V2 suspended. Specify a materially smaller, fresh, preregistered V3 only if the audit identifies one exact cross-basket mechanism worth testing."
    stage = "REJECTED" if recommendation == "REJECT" else "INCONCLUSIVE"

    output.mkdir(parents=True, exist_ok=False)
    manifest_sha256 = write_once_json(output / "ledger-manifest.json", manifest)
    audit = {
        "schemaVersion": SCHEMA,
        "campaignId": campaign_id,
        "stage": stage,
        "recommendation": recommendation,
        "developmentPeriod": campaign["developmentPeriod"],
        "confirmationOpened": False,
        "testedBaskets": sorted(OBSERVED_BASKETS),
        "untestedBaskets": sorted(LOCKED_BASKETS - OBSERVED_BASKETS),
        "ledgerManifestSha256": manifest_sha256,
        "searches": search_summaries,
        "focusedV3Eligibility": {
            "eligibleSpecifications": eligible,
            "requiredObservedBaskets": sorted(OBSERVED_BASKETS),
            "requiredSeeds": sorted(EXPECTED_SEEDS),
            "requiresBothControls": True,
            "requiresCleanTimeShiftPlacebo": True,
        },
        "limitations": [
            "Development outcomes are open and nonblind.",
            "Fold aggregates do not establish statistical significance.",
            "No execution-cost, tradability, portfolio, or operational claim was tested.",
            "Archive-source evidence does not establish transfer to Hyperliquid.",
        ],
        "nextPermittedAction": next_action,
    }
    audit_sha256 = write_once_json(output / "audit.json", audit)
    report = _render(audit, audit_sha256).encode()
    report_sha256 = write_once_bytes(output / "audit.md", report)
    return {
        "recommendation": recommendation,
        "stage": stage,
        "ledgerManifestSha256": manifest_sha256,
        "auditSha256": audit_sha256,
        "reportSha256": report_sha256,
        "output": str(output.absolute()),
    }


def _search_argument(value: str) -> tuple[int, Path]:
    try:
        basket_text, path_text = value.split("=", 1)
        basket = int(basket_text)
    except ValueError as error:
        raise argparse.ArgumentTypeError("search must be BASKET=PATH") from error
    return basket, Path(path_text)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["audit-development"])
    parser.add_argument("--lock", type=Path, required=True)
    parser.add_argument("--search", action="append", type=_search_argument, required=True)
    parser.add_argument("--confirmation-ledger-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--suspension-record", type=Path)
    args = parser.parse_args(argv)
    searches = dict(args.search)
    if len(searches) != len(args.search):
        parser.error("duplicate --search basket")
    result = audit_development(
        args.lock,
        searches,
        args.confirmation_ledger_root,
        args.output,
        args.suspension_record,
    )
    print(json.dumps(result, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
