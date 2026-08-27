"""Immutable, JSON-native contracts for the archive-first alpha campaign.

The validators in this module are intentionally side-effect free.  In
particular, confirmation validation never creates the single-use marker; the
orchestrator must publish the returned marker with an atomic create operation.
"""

from __future__ import annotations

import hashlib
import json
import re
from collections.abc import Mapping, Sequence
from datetime import datetime
from pathlib import Path
from typing import Any, Final, TypedDict


STAGES: Final[tuple[str, ...]] = (
    "IDEA",
    "DATA_FEASIBILITY",
    "EXPLORATORY",
    "FROZEN_CANDIDATE",
    "BLIND_VALIDATED",
    "PROSPECTIVE_SHADOW",
    "PAPER_ELIGIBLE",
    "LIVE_ELIGIBLE",
    "REJECTED",
    "INCONCLUSIVE",
    "DATA_BLOCKED",
    "OPERATIONALLY_BLOCKED",
)

CAMPAIGN_SCHEMA: Final = "marketlab.alpha-campaign-lock.v1"
SEARCH_SCHEMA: Final = "marketlab.alpha-candidate-search-manifest.v1"
TRIAL_SCHEMA: Final = "marketlab.alpha-trial-ledger-entry.v1"
FROZEN_SCHEMA: Final = "marketlab.alpha-frozen-candidate-lock.v1"
FAMILY_SCHEMA: Final = "marketlab.alpha-confirmation-family-manifest.v1"
MARKER_SCHEMA: Final = "marketlab.alpha-confirmation-marker.v1"
RESULT_SCHEMA: Final = "marketlab.alpha-confirmation-result.v1"

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_DIGEST = re.compile(r"^sha256:[0-9a-f]{64}$")
_IDENTIFIER = re.compile(r"^[a-z0-9][a-z0-9._-]*$")
_REQUIRED_SEARCH_DIMENSIONS: Final = frozenset(
    {
        "assets",
        "basketSizes",
        "factorRepresentations",
        "horizons",
        "targets",
        "informationSets",
        "modelFamilies",
        "variants",
    }
)


class ContractValidationError(ValueError):
    """Raised when a research record is incomplete, ambiguous, or unsafe."""


class ArtifactHash(TypedDict):
    path: str
    sha256: str
    sizeBytes: int


class GPUIdentity(TypedDict, total=False):
    available: bool
    deviceName: str
    deviceUuid: str
    driverVersion: str
    runtimeVersion: str
    totalMemoryBytes: int
    imageDigest: str
    determinismNotes: list[str]
    unavailableReason: str


class OutcomePeriod(TypedDict):
    startInclusive: str
    endExclusive: str
    purpose: str


def canonical_json_bytes(value: Any) -> bytes:
    """Return the canonical JSON representation used by all contract hashes."""

    try:
        return json.dumps(
            value,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            allow_nan=False,
        ).encode("utf-8")
    except (TypeError, ValueError) as error:
        raise ContractValidationError(f"value is not strict JSON: {error}") from error


def canonical_sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def verify_canonical_hash(value: Any, expected_sha256: str, path: str = "documentSha256") -> None:
    """Verify a JSON document against its canonical, formatting-independent hash."""

    _sha256(expected_sha256, path)
    if canonical_sha256(value) != expected_sha256:
        raise ContractValidationError(f"{path} does not match the canonical document")


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _mapping(value: Any, path: str) -> Mapping[str, Any]:
    if not isinstance(value, Mapping):
        raise ContractValidationError(f"{path} must be an object")
    for key in value:
        if not isinstance(key, str):
            raise ContractValidationError(f"{path} has a non-string key")
    return value


def _required(record: Mapping[str, Any], fields: Sequence[str], path: str) -> None:
    missing = [field for field in fields if field not in record]
    if missing:
        raise ContractValidationError(f"{path} is missing: {', '.join(missing)}")


def _text(value: Any, path: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise ContractValidationError(f"{path} must be a non-empty string")
    return value


def _identifier(value: Any, path: str) -> str:
    value = _text(value, path)
    if not _IDENTIFIER.fullmatch(value):
        raise ContractValidationError(f"{path} is not a portable identifier")
    return value


def _integer(value: Any, path: str, *, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ContractValidationError(f"{path} must be an integer >= {minimum}")
    return value


def _number(value: Any, path: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise ContractValidationError(f"{path} must be a finite number")
    numeric = float(value)
    if not (-float("inf") < numeric < float("inf")):
        raise ContractValidationError(f"{path} must be a finite number")
    return numeric


def _timestamp(value: Any, path: str) -> datetime:
    value = _text(value, path)
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ContractValidationError(f"{path} must be an ISO-8601 timestamp") from error
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise ContractValidationError(f"{path} must include a UTC offset")
    return parsed


def _sha256(value: Any, path: str) -> str:
    value = _text(value, path)
    if not _SHA256.fullmatch(value):
        raise ContractValidationError(f"{path} must be a lowercase SHA-256 hex digest")
    return value


def _strings(value: Any, path: str, *, allow_empty: bool = False) -> list[str]:
    if isinstance(value, (str, bytes)) or not isinstance(value, Sequence):
        raise ContractValidationError(f"{path} must be an array")
    result = [_text(item, f"{path}[{index}]") for index, item in enumerate(value)]
    if not allow_empty and not result:
        raise ContractValidationError(f"{path} must not be empty")
    if len(result) != len(set(result)):
        raise ContractValidationError(f"{path} must not contain duplicates")
    return result


def _search_dimension_values(dimension: str, value: Any, path: str) -> list[Any]:
    if dimension != "basketSizes":
        return _strings(value, path)
    if isinstance(value, (str, bytes)) or not isinstance(value, Sequence) or not value:
        raise ContractValidationError(f"{path} must be a non-empty array")
    result = [_integer(item, f"{path}[{index}]", minimum=2) for index, item in enumerate(value)]
    if result != sorted(set(result)):
        raise ContractValidationError(f"{path} must be unique and ascending")
    return result


def _selection_value(dimension: str, value: Any, path: str) -> Any:
    if dimension == "basketSizes":
        return _integer(value, path, minimum=2)
    return _text(value, path)


def _period(value: Any, path: str, *, require_opened_at: bool = False) -> tuple[datetime, datetime]:
    period = _mapping(value, path)
    required = ["startInclusive", "endExclusive", "purpose"]
    if require_opened_at:
        required.append("openedAt")
    _required(period, required, path)
    start = _timestamp(period["startInclusive"], f"{path}.startInclusive")
    end = _timestamp(period["endExclusive"], f"{path}.endExclusive")
    _text(period["purpose"], f"{path}.purpose")
    if start >= end:
        raise ContractValidationError(f"{path} must have startInclusive < endExclusive")
    if require_opened_at:
        _timestamp(period["openedAt"], f"{path}.openedAt")
    return start, end


def _opened_periods(value: Any, path: str) -> None:
    if isinstance(value, (str, bytes)) or not isinstance(value, Sequence):
        raise ContractValidationError(f"{path} must be an array")
    periods: list[tuple[datetime, datetime]] = []
    for index, item in enumerate(value):
        periods.append(_period(item, f"{path}[{index}]", require_opened_at=True))
    ordered = sorted(periods)
    for previous, current in zip(ordered, ordered[1:]):
        if current[0] < previous[1]:
            raise ContractValidationError(f"{path} contains overlapping outcome periods")


def _artifacts(value: Any, path: str) -> None:
    if isinstance(value, (str, bytes)) or not isinstance(value, Sequence):
        raise ContractValidationError(f"{path} must be an array")
    paths: set[str] = set()
    for index, raw in enumerate(value):
        artifact = _mapping(raw, f"{path}[{index}]")
        _required(artifact, ["path", "sha256", "sizeBytes"], f"{path}[{index}]")
        relative = _safe_relative(artifact["path"], f"{path}[{index}].path")
        if relative in paths:
            raise ContractValidationError(f"{path} contains duplicate artifact path {relative}")
        paths.add(relative)
        _sha256(artifact["sha256"], f"{path}[{index}].sha256")
        _integer(artifact["sizeBytes"], f"{path}[{index}].sizeBytes")


def _safe_relative(value: Any, path: str) -> str:
    value = _text(value, path)
    candidate = Path(value)
    if candidate.is_absolute() or not candidate.parts or any(part in ("", ".", "..") for part in candidate.parts):
        raise ContractValidationError(f"{path} must be a normalized relative path")
    return value


def _limitations(record: Mapping[str, Any], path: str) -> None:
    _required(record, ["survivorship", "sourceTransfer"], path)
    _text(record["survivorship"], f"{path}.survivorship")
    _text(record["sourceTransfer"], f"{path}.sourceTransfer")


def _epistemic_partition(record: Mapping[str, Any], path: str) -> None:
    _required(record, ["userConstraints", "designConventions", "empiricalClaims"], path)
    _strings(record["userConstraints"], f"{path}.userConstraints")
    _strings(record["designConventions"], f"{path}.designConventions")
    _strings(record["empiricalClaims"], f"{path}.empiricalClaims")


def validate_gpu_identity(value: Any, path: str = "gpuIdentity") -> dict[str, Any]:
    identity = _mapping(value, path)
    _required(identity, ["available", "determinismNotes"], path)
    if not isinstance(identity["available"], bool):
        raise ContractValidationError(f"{path}.available must be boolean")
    _strings(identity["determinismNotes"], f"{path}.determinismNotes", allow_empty=True)
    if identity["available"]:
        _required(
            identity,
            [
                "deviceName",
                "deviceUuid",
                "driverVersion",
                "runtimeVersion",
                "totalMemoryBytes",
                "imageDigest",
            ],
            path,
        )
        for field in ("deviceName", "deviceUuid", "driverVersion", "runtimeVersion"):
            _text(identity[field], f"{path}.{field}")
        _integer(identity["totalMemoryBytes"], f"{path}.totalMemoryBytes", minimum=1)
        digest = _text(identity["imageDigest"], f"{path}.imageDigest")
        if not _DIGEST.fullmatch(digest):
            raise ContractValidationError(f"{path}.imageDigest must be sha256:<64 lowercase hex>")
    else:
        _text(identity.get("unavailableReason"), f"{path}.unavailableReason")
    return dict(identity)


def validate_campaign_lock(value: Any) -> dict[str, Any]:
    record = _mapping(value, "campaign")
    _required(
        record,
        [
            "schemaVersion",
            "campaignId",
            "stage",
            "createdAt",
            "userConstraints",
            "designConventions",
            "empiricalClaims",
            "mechanisms",
            "searchDimensions",
            "searchBudget",
            "validation",
            "developmentPeriod",
            "confirmation",
            "artifacts",
        ],
        "campaign",
    )
    if record["schemaVersion"] != CAMPAIGN_SCHEMA:
        raise ContractValidationError(f"campaign.schemaVersion must be {CAMPAIGN_SCHEMA}")
    _identifier(record["campaignId"], "campaign.campaignId")
    if record["stage"] != "EXPLORATORY":
        raise ContractValidationError("campaign.stage must be EXPLORATORY")
    _timestamp(record["createdAt"], "campaign.createdAt")
    _epistemic_partition(record, "campaign")
    _strings(record["mechanisms"], "campaign.mechanisms")
    dimensions = _mapping(record["searchDimensions"], "campaign.searchDimensions")
    _required(
        dimensions,
        ["horizons", "basketSizes", "factorRepresentations", "modelFamilies", "targets"],
        "campaign.searchDimensions",
    )
    for field in ("horizons", "factorRepresentations", "modelFamilies", "targets"):
        _strings(dimensions[field], f"campaign.searchDimensions.{field}")
    basket_sizes = dimensions["basketSizes"]
    if isinstance(basket_sizes, (str, bytes)) or not isinstance(basket_sizes, Sequence) or not basket_sizes:
        raise ContractValidationError("campaign.searchDimensions.basketSizes must be a non-empty array")
    parsed_sizes = [
        _integer(size, f"campaign.searchDimensions.basketSizes[{index}]", minimum=2)
        for index, size in enumerate(basket_sizes)
    ]
    if parsed_sizes != sorted(set(parsed_sizes)):
        raise ContractValidationError("campaign.searchDimensions.basketSizes must be unique and ascending")

    budget = _mapping(record["searchBudget"], "campaign.searchBudget")
    _required(
        budget,
        [
            "stageATrialsPerFamily",
            "stageBAdditionalTrialsPerSurvivor",
            "stageBMaxFamiliesPerMechanism",
            "maxFrozenPerMechanism",
            "maxFrozenOverall",
            "stageASeeds",
            "stageBSeeds",
        ],
        "campaign.searchBudget",
    )
    for field in (
        "stageATrialsPerFamily",
        "stageBAdditionalTrialsPerSurvivor",
        "stageBMaxFamiliesPerMechanism",
        "maxFrozenPerMechanism",
        "maxFrozenOverall",
    ):
        _integer(budget[field], f"campaign.searchBudget.{field}", minimum=1)
    for field in ("stageASeeds", "stageBSeeds"):
        seeds = budget[field]
        if isinstance(seeds, (str, bytes)) or not isinstance(seeds, Sequence) or not seeds:
            raise ContractValidationError(f"campaign.searchBudget.{field} must be a non-empty array")
        parsed = [_integer(seed, f"campaign.searchBudget.{field}[{index}]") for index, seed in enumerate(seeds)]
        if len(parsed) != len(set(parsed)):
            raise ContractValidationError(f"campaign.searchBudget.{field} must not contain duplicates")
    if budget["maxFrozenOverall"] > len(record["mechanisms"]) * budget["maxFrozenPerMechanism"]:
        raise ContractValidationError("campaign.searchBudget.maxFrozenOverall exceeds the per-mechanism bound")

    validation = _mapping(record["validation"], "campaign.validation")
    _required(validation, ["method", "primaryLoss", "baselines", "purgeByHorizon", "multiplicity"], "campaign.validation")
    _text(validation["method"], "campaign.validation.method")
    _text(validation["primaryLoss"], "campaign.validation.primaryLoss")
    _strings(validation["baselines"], "campaign.validation.baselines")
    if validation["purgeByHorizon"] is not True:
        raise ContractValidationError("campaign.validation.purgeByHorizon must be true")
    _text(validation["multiplicity"], "campaign.validation.multiplicity")

    development_period = _period(record["developmentPeriod"], "campaign.developmentPeriod")
    confirmation = _mapping(record["confirmation"], "campaign.confirmation")
    _required(
        confirmation,
        [
            "singleUse",
            "minimumCalendarDays",
            "minimumNonOverlappingTargets",
            "startInclusive",
            "endExclusive",
            "purpose",
        ],
        "campaign.confirmation",
    )
    if confirmation["singleUse"] is not True:
        raise ContractValidationError("campaign.confirmation.singleUse must be true")
    _integer(confirmation["minimumCalendarDays"], "campaign.confirmation.minimumCalendarDays", minimum=1)
    _integer(confirmation["minimumNonOverlappingTargets"], "campaign.confirmation.minimumNonOverlappingTargets", minimum=1)
    confirmation_period = _period(confirmation, "campaign.confirmation")
    if development_period[1] > confirmation_period[0]:
        raise ContractValidationError("campaign development and confirmation periods overlap")
    confirmation_days = (confirmation_period[1] - confirmation_period[0]).total_seconds() / 86_400
    if confirmation_days < confirmation["minimumCalendarDays"]:
        raise ContractValidationError("campaign confirmation boundary is shorter than minimumCalendarDays")
    _artifacts(record["artifacts"], "campaign.artifacts")
    canonical_json_bytes(record)
    return dict(record)


def validate_candidate_search_manifest(value: Any, campaign: Any | None = None) -> dict[str, Any]:
    record = _mapping(value, "searchManifest")
    _required(
        record,
        [
            "schemaVersion",
            "campaignId",
            "candidateId",
            "mechanism",
            "stage",
            "createdAt",
            "userConstraints",
            "designConventions",
            "empiricalClaims",
            "searchDimensions",
            "maximumTrials",
            "gpuIdentity",
            "openedOutcomePeriods",
            "limitations",
            "artifacts",
        ],
        "searchManifest",
    )
    if record["schemaVersion"] != SEARCH_SCHEMA:
        raise ContractValidationError(f"searchManifest.schemaVersion must be {SEARCH_SCHEMA}")
    _identifier(record["campaignId"], "searchManifest.campaignId")
    _identifier(record["candidateId"], "searchManifest.candidateId")
    _text(record["mechanism"], "searchManifest.mechanism")
    if record["stage"] != "EXPLORATORY":
        raise ContractValidationError("searchManifest.stage must be EXPLORATORY")
    _timestamp(record["createdAt"], "searchManifest.createdAt")
    _epistemic_partition(record, "searchManifest")
    dimensions = _mapping(record["searchDimensions"], "searchManifest.searchDimensions")
    missing = _REQUIRED_SEARCH_DIMENSIONS - set(dimensions)
    if missing:
        raise ContractValidationError(
            "searchManifest.searchDimensions is incomplete; missing: " + ", ".join(sorted(missing))
        )
    for dimension in sorted(_REQUIRED_SEARCH_DIMENSIONS):
        _search_dimension_values(dimension, dimensions[dimension], f"searchManifest.searchDimensions.{dimension}")
    maximum_trials = _integer(record["maximumTrials"], "searchManifest.maximumTrials", minimum=1)
    validate_gpu_identity(record["gpuIdentity"], "searchManifest.gpuIdentity")
    _opened_periods(record["openedOutcomePeriods"], "searchManifest.openedOutcomePeriods")
    _limitations(_mapping(record["limitations"], "searchManifest.limitations"), "searchManifest.limitations")
    _artifacts(record["artifacts"], "searchManifest.artifacts")
    if campaign is not None:
        campaign_record = validate_campaign_lock(campaign)
        if record["campaignId"] != campaign_record["campaignId"]:
            raise ContractValidationError("searchManifest.campaignId does not match campaign")
        if record["mechanism"] not in campaign_record["mechanisms"]:
            raise ContractValidationError("searchManifest.mechanism is outside the campaign lock")
        for dimension in ("basketSizes", "factorRepresentations", "horizons", "targets", "modelFamilies"):
            allowed = campaign_record["searchDimensions"][dimension]
            if not set(dimensions[dimension]).issubset(set(allowed)):
                raise ContractValidationError(
                    f"searchManifest.searchDimensions.{dimension} exceeds the campaign lock"
                )
        budget = campaign_record["searchBudget"]
        campaign_trial_limit = (
            budget["stageATrialsPerFamily"] * len(dimensions["modelFamilies"])
            + budget["stageBAdditionalTrialsPerSurvivor"]
            * min(budget["stageBMaxFamiliesPerMechanism"], len(dimensions["modelFamilies"]))
            * len(budget["stageBSeeds"])
        )
        if maximum_trials > campaign_trial_limit:
            raise ContractValidationError("searchManifest.maximumTrials exceeds the campaign stage budgets")
    canonical_json_bytes(record)
    return dict(record)


def validate_trial_ledger_entry(value: Any, manifest: Any | None = None) -> dict[str, Any]:
    record = _mapping(value, "trial")
    _required(
        record,
        [
            "schemaVersion",
            "campaignId",
            "candidateId",
            "trialId",
            "startedAt",
            "completedAt",
            "status",
            "seed",
            "selection",
            "metrics",
            "artifactHashes",
        ],
        "trial",
    )
    if record["schemaVersion"] != TRIAL_SCHEMA:
        raise ContractValidationError(f"trial.schemaVersion must be {TRIAL_SCHEMA}")
    for field in ("campaignId", "candidateId", "trialId"):
        _identifier(record[field], f"trial.{field}")
    start = _timestamp(record["startedAt"], "trial.startedAt")
    end = _timestamp(record["completedAt"], "trial.completedAt")
    if end < start:
        raise ContractValidationError("trial.completedAt must not precede startedAt")
    if record["status"] not in {"COMPLETED", "PRUNED", "FAILED", "NUMERICALLY_UNSTABLE"}:
        raise ContractValidationError("trial.status is not an allowed ledger status")
    _integer(record["seed"], "trial.seed")
    selection = _mapping(record["selection"], "trial.selection")
    _required(selection, sorted(_REQUIRED_SEARCH_DIMENSIONS), "trial.selection")
    for dimension in _REQUIRED_SEARCH_DIMENSIONS:
        _selection_value(dimension, selection[dimension], f"trial.selection.{dimension}")
    metrics = _mapping(record["metrics"], "trial.metrics")
    for name, metric in metrics.items():
        _text(name, "trial.metrics key")
        _number(metric, f"trial.metrics.{name}")
    if record["status"] == "COMPLETED" and not metrics:
        raise ContractValidationError("completed trial must contain metrics")
    _artifacts(record["artifactHashes"], "trial.artifactHashes")
    if manifest is not None:
        manifest_record = validate_candidate_search_manifest(manifest)
        for field in ("campaignId", "candidateId"):
            if record[field] != manifest_record[field]:
                raise ContractValidationError(f"trial.{field} does not match search manifest")
        for dimension in _REQUIRED_SEARCH_DIMENSIONS:
            if selection[dimension] not in manifest_record["searchDimensions"][dimension]:
                raise ContractValidationError(f"trial.selection.{dimension} is outside the search manifest")
    canonical_json_bytes(record)
    return dict(record)


def validate_confirmation_family_manifest(value: Any, campaign: Any | None = None) -> dict[str, Any]:
    record = _mapping(value, "confirmationFamily")
    _required(
        record,
        [
            "schemaVersion",
            "campaignId",
            "createdAt",
            "campaignLockFileSha256",
            "developmentPanelSha256s",
            "confirmationLedgerRoot",
            "multiplicity",
            "selectedCandidates",
        ],
        "confirmationFamily",
    )
    if record["schemaVersion"] != FAMILY_SCHEMA:
        raise ContractValidationError(f"confirmationFamily.schemaVersion must be {FAMILY_SCHEMA}")
    _identifier(record["campaignId"], "confirmationFamily.campaignId")
    _timestamp(record["createdAt"], "confirmationFamily.createdAt")
    _sha256(record["campaignLockFileSha256"], "confirmationFamily.campaignLockFileSha256")
    panel_hashes = _strings(record["developmentPanelSha256s"], "confirmationFamily.developmentPanelSha256s")
    for index, panel_hash in enumerate(panel_hashes):
        _sha256(panel_hash, f"confirmationFamily.developmentPanelSha256s[{index}]")
    if panel_hashes != sorted(panel_hashes):
        raise ContractValidationError("confirmationFamily.developmentPanelSha256s must be sorted")
    ledger_root = Path(_text(record["confirmationLedgerRoot"], "confirmationFamily.confirmationLedgerRoot"))
    if not ledger_root.is_absolute() or ".." in ledger_root.parts:
        raise ContractValidationError("confirmationFamily.confirmationLedgerRoot must be an absolute normalized path")
    _text(record["multiplicity"], "confirmationFamily.multiplicity")
    selected = record["selectedCandidates"]
    if isinstance(selected, (str, bytes)) or not isinstance(selected, Sequence):
        raise ContractValidationError("confirmationFamily.selectedCandidates must be an array")
    identities: list[tuple[str, str]] = []
    for index, raw in enumerate(selected):
        candidate = _mapping(raw, f"confirmationFamily.selectedCandidates[{index}]")
        _required(candidate, ["candidateId", "trialId", "searchManifestSha256"], f"confirmationFamily.selectedCandidates[{index}]")
        identity = (
            _identifier(candidate["candidateId"], f"confirmationFamily.selectedCandidates[{index}].candidateId"),
            _identifier(candidate["trialId"], f"confirmationFamily.selectedCandidates[{index}].trialId"),
        )
        _sha256(candidate["searchManifestSha256"], f"confirmationFamily.selectedCandidates[{index}].searchManifestSha256")
        identities.append(identity)
    if identities != sorted(set(identities)):
        raise ContractValidationError("confirmationFamily.selectedCandidates must be unique and sorted")
    if len({candidate_id for candidate_id, _ in identities}) != len(identities):
        raise ContractValidationError("confirmationFamily has duplicate candidate IDs")
    if campaign is not None:
        campaign_record = validate_campaign_lock(campaign)
        if record["campaignId"] != campaign_record["campaignId"]:
            raise ContractValidationError("confirmationFamily.campaignId does not match campaign")
        if len(identities) > campaign_record["searchBudget"]["maxFrozenOverall"]:
            raise ContractValidationError("confirmationFamily exceeds campaign finalist bound")
        if record["multiplicity"] != campaign_record["validation"]["multiplicity"]:
            raise ContractValidationError("confirmationFamily multiplicity does not match campaign")
    canonical_json_bytes(record)
    return dict(record)


def validate_frozen_candidate_lock(
    value: Any,
    campaign: Any | None = None,
    search_manifest: Any | None = None,
) -> dict[str, Any]:
    record = _mapping(value, "frozenLock")
    _required(
        record,
        [
            "schemaVersion",
            "campaignId",
            "candidateId",
            "stage",
            "frozenAt",
            "searchManifestSha256",
            "selectedTrialId",
            "selectedSpecification",
            "featureSchemaSha256",
            "availabilityRules",
            "developmentPeriod",
            "confirmationPeriod",
            "purgeEmbargo",
            "baselines",
            "primaryLoss",
            "inference",
            "costs",
            "acceptanceThresholds",
            "confirmationMarker",
            "confirmationPanelPath",
            "confirmationPanelSha256",
            "confirmationFamily",
            "confirmationFamilySha256",
            "openedOutcomePeriods",
            "limitations",
            "artifacts",
        ],
        "frozenLock",
    )
    if record["schemaVersion"] != FROZEN_SCHEMA:
        raise ContractValidationError(f"frozenLock.schemaVersion must be {FROZEN_SCHEMA}")
    _identifier(record["campaignId"], "frozenLock.campaignId")
    _identifier(record["candidateId"], "frozenLock.candidateId")
    if record["stage"] != "FROZEN_CANDIDATE":
        raise ContractValidationError("frozenLock.stage must be FROZEN_CANDIDATE")
    _timestamp(record["frozenAt"], "frozenLock.frozenAt")
    _sha256(record["searchManifestSha256"], "frozenLock.searchManifestSha256")
    _identifier(record["selectedTrialId"], "frozenLock.selectedTrialId")
    specification = _mapping(record["selectedSpecification"], "frozenLock.selectedSpecification")
    _required(specification, sorted(_REQUIRED_SEARCH_DIMENSIONS), "frozenLock.selectedSpecification")
    for field in _REQUIRED_SEARCH_DIMENSIONS:
        _selection_value(field, specification[field], f"frozenLock.selectedSpecification.{field}")
    _sha256(record["featureSchemaSha256"], "frozenLock.featureSchemaSha256")
    _strings(record["availabilityRules"], "frozenLock.availabilityRules")
    development = _period(record["developmentPeriod"], "frozenLock.developmentPeriod")
    confirmation = _period(record["confirmationPeriod"], "frozenLock.confirmationPeriod")
    if development[1] > confirmation[0]:
        raise ContractValidationError("frozenLock development and confirmation periods overlap")
    purge = _mapping(record["purgeEmbargo"], "frozenLock.purgeEmbargo")
    _required(purge, ["purge", "embargo"], "frozenLock.purgeEmbargo")
    _text(purge["purge"], "frozenLock.purgeEmbargo.purge")
    _text(purge["embargo"], "frozenLock.purgeEmbargo.embargo")
    _strings(record["baselines"], "frozenLock.baselines")
    _text(record["primaryLoss"], "frozenLock.primaryLoss")
    for field in ("inference", "costs", "acceptanceThresholds"):
        item = _mapping(record[field], f"frozenLock.{field}")
        if not item:
            raise ContractValidationError(f"frozenLock.{field} must not be empty")
        canonical_json_bytes(item)
    marker = _mapping(record["confirmationMarker"], "frozenLock.confirmationMarker")
    _required(marker, ["confirmationId", "relativePath", "mustNotExistBeforeOpen"], "frozenLock.confirmationMarker")
    _identifier(marker["confirmationId"], "frozenLock.confirmationMarker.confirmationId")
    _safe_relative(marker["relativePath"], "frozenLock.confirmationMarker.relativePath")
    if marker["mustNotExistBeforeOpen"] is not True:
        raise ContractValidationError("frozenLock.confirmationMarker.mustNotExistBeforeOpen must be true")
    _text(record["confirmationPanelPath"], "frozenLock.confirmationPanelPath")
    _sha256(record["confirmationPanelSha256"], "frozenLock.confirmationPanelSha256")
    family = validate_confirmation_family_manifest(record["confirmationFamily"], campaign)
    verify_canonical_hash(family, record["confirmationFamilySha256"], "frozenLock.confirmationFamilySha256")
    matching_family_candidates = [
        item for item in family["selectedCandidates"] if item["candidateId"] == record["candidateId"]
    ]
    if (
        len(matching_family_candidates) != 1
        or matching_family_candidates[0]["trialId"] != record["selectedTrialId"]
        or matching_family_candidates[0]["searchManifestSha256"] != record["searchManifestSha256"]
    ):
        raise ContractValidationError("frozenLock candidate/trial is outside the confirmation family")
    opened = record["openedOutcomePeriods"]
    _opened_periods(opened, "frozenLock.openedOutcomePeriods")
    if opened:
        raise ContractValidationError("a newly frozen lock cannot contain opened outcome periods")
    _limitations(_mapping(record["limitations"], "frozenLock.limitations"), "frozenLock.limitations")
    _artifacts(record["artifacts"], "frozenLock.artifacts")
    if campaign is not None:
        campaign_record = validate_campaign_lock(campaign)
        if record["campaignId"] != campaign_record["campaignId"]:
            raise ContractValidationError("frozenLock.campaignId does not match campaign")
        for dimension in ("basketSizes", "factorRepresentations", "horizons", "targets", "modelFamilies"):
            if specification[dimension] not in campaign_record["searchDimensions"][dimension]:
                raise ContractValidationError(
                    f"frozenLock.selectedSpecification.{dimension} exceeds the campaign lock"
                )
        campaign_confirmation = campaign_record["confirmation"]
        for field in ("startInclusive", "endExclusive", "purpose"):
            if record["confirmationPeriod"][field] != campaign_confirmation[field]:
                raise ContractValidationError("frozenLock confirmation period does not match campaign")
            if record["developmentPeriod"][field] != campaign_record["developmentPeriod"][field]:
                raise ContractValidationError("frozenLock development period does not match campaign")
    if search_manifest is not None:
        manifest_record = validate_candidate_search_manifest(search_manifest, campaign)
        if record["campaignId"] != manifest_record["campaignId"] or record["candidateId"] != manifest_record["candidateId"]:
            raise ContractValidationError("frozenLock identity does not match search manifest")
        verify_canonical_hash(
            manifest_record,
            record["searchManifestSha256"],
            "frozenLock.searchManifestSha256",
        )
        for dimension in _REQUIRED_SEARCH_DIMENSIONS:
            if specification[dimension] not in manifest_record["searchDimensions"][dimension]:
                raise ContractValidationError(
                    f"frozenLock.selectedSpecification.{dimension} exceeds the search manifest"
                )
    canonical_json_bytes(record)
    return dict(record)


def confirmation_marker_payload(
    frozen_lock: Any,
    frozen_lock_sha256: str,
    opened_at: str,
) -> dict[str, Any]:
    """Build, but do not persist, the payload for an atomic single-use marker."""

    lock = validate_frozen_candidate_lock(frozen_lock)
    _sha256(frozen_lock_sha256, "frozenLockSha256")
    if canonical_sha256(lock) != frozen_lock_sha256:
        raise ContractValidationError("frozenLockSha256 does not match the canonical frozen lock")
    _timestamp(opened_at, "openedAt")
    period = lock["confirmationPeriod"]
    return {
        "schemaVersion": MARKER_SCHEMA,
        "confirmationId": lock["confirmationMarker"]["confirmationId"],
        "candidateId": lock["candidateId"],
        "frozenLockSha256": frozen_lock_sha256,
        "openedAt": opened_at,
        "period": dict(period),
        "confirmationPanelSha256": lock["confirmationPanelSha256"],
        "confirmationFamilySha256": lock["confirmationFamilySha256"],
    }


def confirmation_marker_path(frozen_lock: Any, root: str | Path) -> Path:
    lock = validate_frozen_candidate_lock(frozen_lock)
    base = Path(root).resolve()
    marker = base.joinpath(lock["confirmationMarker"]["relativePath"]).resolve()
    if not marker.is_relative_to(base):
        raise ContractValidationError("confirmation marker escapes its orchestration root")
    return marker


def validate_confirmation_preflight(frozen_lock: Any, root: str | Path) -> Path:
    """Verify a confirmation is unopened without changing filesystem state.

    The caller must subsequently create this exact path atomically (for
    example with O_CREAT|O_EXCL) before reading any confirmation outcomes.
    """

    marker = confirmation_marker_path(frozen_lock, root)
    if marker.exists() or marker.is_symlink():
        raise ContractValidationError("confirmation has already been opened")
    return marker


def validate_confirmation_marker(
    value: Any,
    frozen_lock: Any,
    frozen_lock_sha256: str,
) -> dict[str, Any]:
    marker = _mapping(value, "confirmationMarker")
    _required(
        marker,
        [
            "schemaVersion",
            "confirmationId",
            "candidateId",
            "frozenLockSha256",
            "openedAt",
            "period",
            "confirmationPanelSha256",
            "confirmationFamilySha256",
        ],
        "confirmationMarker",
    )
    if marker["schemaVersion"] != MARKER_SCHEMA:
        raise ContractValidationError(f"confirmationMarker.schemaVersion must be {MARKER_SCHEMA}")
    lock = validate_frozen_candidate_lock(frozen_lock)
    _sha256(frozen_lock_sha256, "frozenLockSha256")
    if canonical_sha256(lock) != frozen_lock_sha256:
        raise ContractValidationError("frozenLockSha256 does not match the canonical frozen lock")
    if marker["confirmationId"] != lock["confirmationMarker"]["confirmationId"]:
        raise ContractValidationError("confirmation marker ID does not match frozen lock")
    if marker["candidateId"] != lock["candidateId"]:
        raise ContractValidationError("confirmation marker candidate does not match frozen lock")
    if marker["frozenLockSha256"] != frozen_lock_sha256:
        raise ContractValidationError("confirmation marker frozen-lock hash does not match")
    _timestamp(marker["openedAt"], "confirmationMarker.openedAt")
    _period(marker["period"], "confirmationMarker.period")
    if marker["period"] != lock["confirmationPeriod"]:
        raise ContractValidationError("confirmation marker period does not match frozen lock")
    if marker["confirmationPanelSha256"] != lock["confirmationPanelSha256"]:
        raise ContractValidationError("confirmation marker panel hash does not match frozen lock")
    if marker["confirmationFamilySha256"] != lock["confirmationFamilySha256"]:
        raise ContractValidationError("confirmation marker family hash does not match frozen lock")
    canonical_json_bytes(marker)
    return dict(marker)


def validate_confirmation_result(
    value: Any,
    frozen_lock: Any,
    frozen_lock_sha256: str,
    marker: Any,
) -> dict[str, Any]:
    record = _mapping(value, "confirmationResult")
    _required(
        record,
        [
            "schemaVersion",
            "campaignId",
            "candidateId",
            "stage",
            "completedAt",
            "frozenLockSha256",
            "confirmationId",
            "selectedTrialId",
            "confirmationPanelSha256",
            "confirmationFamilySha256",
            "openedOutcomePeriods",
            "decision",
            "primaryMetric",
            "baselineMetrics",
            "inference",
            "limitations",
            "artifacts",
        ],
        "confirmationResult",
    )
    if record["schemaVersion"] != RESULT_SCHEMA:
        raise ContractValidationError(f"confirmationResult.schemaVersion must be {RESULT_SCHEMA}")
    lock = validate_frozen_candidate_lock(frozen_lock)
    marker_record = validate_confirmation_marker(marker, lock, frozen_lock_sha256)
    if record["campaignId"] != lock["campaignId"] or record["candidateId"] != lock["candidateId"]:
        raise ContractValidationError("confirmation result identity does not match frozen lock")
    if record["frozenLockSha256"] != frozen_lock_sha256:
        raise ContractValidationError("confirmation result frozen-lock hash does not match")
    if record["confirmationId"] != marker_record["confirmationId"]:
        raise ContractValidationError("confirmation result marker ID does not match")
    if record["selectedTrialId"] != lock["selectedTrialId"]:
        raise ContractValidationError("confirmation result trial ID does not match frozen lock")
    if record["confirmationPanelSha256"] != lock["confirmationPanelSha256"]:
        raise ContractValidationError("confirmation result panel hash does not match frozen lock")
    if record["confirmationFamilySha256"] != lock["confirmationFamilySha256"]:
        raise ContractValidationError("confirmation result family hash does not match frozen lock")
    if record["stage"] not in {"BLIND_VALIDATED", "REJECTED", "INCONCLUSIVE", "DATA_BLOCKED", "OPERATIONALLY_BLOCKED"}:
        raise ContractValidationError("confirmationResult.stage is not a valid confirmation outcome")
    _timestamp(record["completedAt"], "confirmationResult.completedAt")
    _opened_periods(record["openedOutcomePeriods"], "confirmationResult.openedOutcomePeriods")
    if len(record["openedOutcomePeriods"]) != 1:
        raise ContractValidationError("confirmation result must record exactly one opened outcome period")
    opened = record["openedOutcomePeriods"][0]
    for field in ("startInclusive", "endExclusive", "purpose"):
        if opened[field] != lock["confirmationPeriod"][field]:
            raise ContractValidationError("opened outcome period does not match frozen confirmation period")
    _text(record["decision"], "confirmationResult.decision")
    metric = _mapping(record["primaryMetric"], "confirmationResult.primaryMetric")
    if metric.get("status") == "NOT_EVALUATED":
        _required(metric, ["name", "status"], "confirmationResult.primaryMetric")
        _text(metric["name"], "confirmationResult.primaryMetric.name")
        if record["stage"] != "INCONCLUSIVE":
            raise ContractValidationError("a non-evaluated primary metric must remain INCONCLUSIVE")
    else:
        _required(metric, ["name", "value", "baseline", "improvement"], "confirmationResult.primaryMetric")
        _text(metric["name"], "confirmationResult.primaryMetric.name")
        _number(metric["value"], "confirmationResult.primaryMetric.value")
        _number(metric["baseline"], "confirmationResult.primaryMetric.baseline")
        _number(metric["improvement"], "confirmationResult.primaryMetric.improvement")
    baselines = _mapping(record["baselineMetrics"], "confirmationResult.baselineMetrics")
    if not baselines and metric.get("status") != "NOT_EVALUATED":
        raise ContractValidationError("confirmationResult.baselineMetrics must not be empty")
    for name, metric_value in baselines.items():
        _text(name, "confirmationResult.baselineMetrics key")
        _number(metric_value, f"confirmationResult.baselineMetrics.{name}")
    inference = _mapping(record["inference"], "confirmationResult.inference")
    if not inference:
        raise ContractValidationError("confirmationResult.inference must not be empty")
    canonical_json_bytes(inference)
    _limitations(_mapping(record["limitations"], "confirmationResult.limitations"), "confirmationResult.limitations")
    _artifacts(record["artifacts"], "confirmationResult.artifacts")
    canonical_json_bytes(record)
    return dict(record)


def verify_artifact_hashes(artifacts: Any, root: str | Path) -> None:
    """Verify size and SHA-256 for immutable artifacts below ``root``."""

    _artifacts(artifacts, "artifacts")
    base = Path(root).resolve()
    for index, artifact in enumerate(artifacts):
        path = base.joinpath(artifact["path"])
        resolved = path.resolve()
        if not resolved.is_relative_to(base):
            raise ContractValidationError(f"artifacts[{index}] escapes artifact root")
        if path.is_symlink() or not resolved.is_file():
            raise ContractValidationError(f"artifacts[{index}] is not a regular immutable file")
        if resolved.stat().st_size != artifact["sizeBytes"]:
            raise ContractValidationError(f"artifacts[{index}] size does not match")
        if file_sha256(resolved) != artifact["sha256"]:
            raise ContractValidationError(f"artifacts[{index}] SHA-256 does not match")


__all__ = [
    "ArtifactHash",
    "CAMPAIGN_SCHEMA",
    "ContractValidationError",
    "FAMILY_SCHEMA",
    "FROZEN_SCHEMA",
    "GPUIdentity",
    "MARKER_SCHEMA",
    "OutcomePeriod",
    "RESULT_SCHEMA",
    "SEARCH_SCHEMA",
    "STAGES",
    "TRIAL_SCHEMA",
    "canonical_json_bytes",
    "canonical_sha256",
    "confirmation_marker_path",
    "confirmation_marker_payload",
    "file_sha256",
    "validate_campaign_lock",
    "validate_candidate_search_manifest",
    "validate_confirmation_marker",
    "validate_confirmation_family_manifest",
    "validate_confirmation_preflight",
    "validate_confirmation_result",
    "validate_frozen_candidate_lock",
    "validate_gpu_identity",
    "validate_trial_ledger_entry",
    "verify_artifact_hashes",
    "verify_canonical_hash",
]
