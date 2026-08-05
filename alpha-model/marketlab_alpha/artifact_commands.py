"""Command implementations that do not depend on ML libraries."""

from __future__ import annotations

from pathlib import Path
from typing import Any, Iterable

from datetime import datetime, timezone

from .artifacts import read_json, sha256_file, write_once_json
from .contracts import (
    FROZEN_SCHEMA,
    canonical_sha256,
    validate_campaign_lock,
    validate_confirmation_family_manifest,
    validate_frozen_candidate_lock,
)


def register_archives(paths: Iterable[Path], output: Path) -> dict[str, Any]:
    objects = []
    for path in sorted((value.absolute() for value in paths), key=str):
        if not path.is_file():
            raise ValueError(f"archive input is not a regular file: {path}")
        objects.append({"path": str(path), "bytes": path.stat().st_size, "sha256": sha256_file(path)})
    if not objects:
        raise ValueError("at least one archive is required")
    manifest = {"schemaVersion": "marketlab.alpha-archive-manifest.v1", "objects": objects}
    manifest["artifactSha256"] = write_once_json(output, manifest)
    return manifest


def freeze_candidate(
    search_path: Path,
    campaign_lock_path: Path,
    candidate_id: str,
    confirmation_panel_path: Path,
    output: Path,
) -> dict[str, Any]:
    search, campaign = read_json(search_path), read_json(campaign_lock_path)
    campaign = validate_campaign_lock(campaign)
    selected = [value for value in search.get("selected", []) if value.get("candidateId") == candidate_id]
    if len(selected) != 1:
        raise ValueError("candidate must appear exactly once in selected development results")
    winner = selected[0]
    search_manifest = winner["searchManifest"]
    model_path = Path(winner["modelArtifactPath"])
    if sha256_file(model_path) != winner["modelArtifactSha256"]:
        raise ValueError("selected model artifact differs from the development result")
    confirmation_panel_path = confirmation_panel_path.resolve()
    if not confirmation_panel_path.is_file():
        raise ValueError("confirmation panel must be an existing regular file")
    confirmation_panel_sha256 = sha256_file(confirmation_panel_path)
    if confirmation_panel_sha256 == winner["panelSha256"]:
        raise ValueError("confirmation panel must not be the development artifact")
    confirmation_panel_manifest_path = confirmation_panel_path.with_suffix(
        confirmation_panel_path.suffix + ".manifest.json"
    )
    if not confirmation_panel_manifest_path.is_file():
        raise ValueError("confirmation panel requires an immutable directional-panel manifest")
    confirmation_panel_manifest = read_json(confirmation_panel_manifest_path)
    if confirmation_panel_manifest.get("schemaVersion") != "marketlab.directional-panel-manifest.v1":
        raise ValueError("unsupported confirmation panel manifest")
    if confirmation_panel_manifest.get("panelSha256") != confirmation_panel_sha256:
        raise ValueError("confirmation panel differs from its manifest")
    expected_confirmation_period = {
        "startInclusive": campaign["confirmation"]["startInclusive"],
        "endExclusive": campaign["confirmation"]["endExclusive"],
    }
    if confirmation_panel_manifest.get("outcomePeriod") != expected_confirmation_period:
        raise ValueError("confirmation panel outcome period differs from the campaign lock")
    family = validate_confirmation_family_manifest(search["confirmationFamily"], campaign)
    if len(family["developmentPanelSha256s"]) != len(campaign["searchDimensions"]["basketSizes"]):
        raise ValueError("confirmation family was not selected across every locked basket size")
    if family["campaignLockFileSha256"] != sha256_file(campaign_lock_path):
        raise ValueError("confirmation family is bound to a different campaign lock artifact")
    if winner["panelSha256"] not in family["developmentPanelSha256s"]:
        raise ValueError("selected candidate panel is outside the confirmation family")
    family_sha256 = canonical_sha256(family)
    matching_family_candidates = [
        item for item in family["selectedCandidates"] if item["candidateId"] == candidate_id
    ]
    if len(matching_family_candidates) != 1 or matching_family_candidates[0]["trialId"] != winner["trialId"]:
        raise ValueError("candidate selection does not match the confirmation family")
    now = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    frozen = {
        "schemaVersion": FROZEN_SCHEMA,
        "campaignId": campaign["campaignId"],
        "candidateId": candidate_id,
        "stage": "FROZEN_CANDIDATE",
        "frozenAt": now,
        "searchManifestSha256": canonical_sha256(search_manifest),
        "selectedTrialId": winner["trialId"],
        "selectedSpecification": winner["selection"],
        "selectedConfiguration": winner.get("configuration", {}),
        "selectedSeed": winner.get("seed"),
        "featureSchemaSha256": winner["featureSchemaSha256"],
        "availabilityRules": [
            "Features use only values with availability time no later than the decision time",
            "Universe and factor estimates use only observations preceding the decision time",
        ],
        "developmentPeriod": campaign["developmentPeriod"],
        "confirmationPeriod": {
            key: campaign["confirmation"][key]
            for key in ("startInclusive", "endExclusive", "purpose")
        },
        "purgeEmbargo": {"purge": "one selected target horizon", "embargo": "one selected target horizon"},
        "baselines": campaign["validation"]["baselines"],
        "primaryLoss": campaign["validation"]["primaryLoss"],
        "inference": {"dependence": "decision-time clustered HAC", "multiplicity": campaign["validation"]["multiplicity"]},
        "costs": {"role": "diagnostic only", "doubleCostStress": True},
        "acceptanceThresholds": {"beatsStrongestBaseline": True, "adjustedPValueMaximum": 0.05, "majorityPositiveOuterFolds": True},
        "confirmationMarker": {
            "confirmationId": f"{candidate_id}-confirmation-1",
            "relativePath": f"confirmation/{campaign['campaignId']}/{candidate_id}-confirmation-1.json",
            "mustNotExistBeforeOpen": True,
        },
        "confirmationPanelPath": str(confirmation_panel_path),
        "confirmationPanelSha256": confirmation_panel_sha256,
        "confirmationPanelManifestPath": str(confirmation_panel_manifest_path.resolve()),
        "confirmationPanelManifestSha256": sha256_file(confirmation_panel_manifest_path),
        "confirmationFamily": family,
        "confirmationFamilySha256": family_sha256,
        "openedOutcomePeriods": [],
        "limitations": search_manifest["limitations"],
        "artifacts": [],
        "campaignLockPath": str(campaign_lock_path.absolute()),
        "campaignLockFileSha256": sha256_file(campaign_lock_path),
        "searchArtifactPath": str(search_path.absolute()),
        "searchArtifactFileSha256": sha256_file(search_path),
        "confirmationFamilyPath": str(search_path.absolute()),
        "confirmationFamilyFileSha256": sha256_file(search_path),
        "searchManifest": search_manifest,
        "modelArtifactPath": str(model_path.absolute()),
        "modelArtifactSha256": winner["modelArtifactSha256"],
        "panelSha256": winner["panelSha256"],
    }
    validate_frozen_candidate_lock(frozen, campaign, search_manifest)
    frozen["artifactSha256"] = write_once_json(output, frozen)
    return frozen


def verify_frozen_inputs(frozen_path: Path) -> dict[str, Any]:
    frozen = read_json(frozen_path)
    validate_frozen_candidate_lock(frozen, read_json(Path(frozen["campaignLockPath"])), frozen["searchManifest"])
    if sha256_file(Path(frozen["campaignLockPath"])) != frozen["campaignLockFileSha256"]:
        raise ValueError("campaign lock differs from frozen candidate")
    if sha256_file(Path(frozen["searchArtifactPath"])) != frozen["searchArtifactFileSha256"]:
        raise ValueError("development search artifact differs from frozen candidate")
    if sha256_file(Path(frozen["confirmationFamilyPath"])) != frozen["confirmationFamilyFileSha256"]:
        raise ValueError("confirmation family manifest differs from frozen candidate")
    family_source = read_json(Path(frozen["confirmationFamilyPath"]))
    if canonical_sha256(family_source["confirmationFamily"]) != frozen["confirmationFamilySha256"]:
        raise ValueError("confirmation family contents differ from frozen candidate")
    if sha256_file(Path(frozen["modelArtifactPath"])) != frozen["modelArtifactSha256"]:
        raise ValueError("model artifact differs from frozen candidate")
    if sha256_file(Path(frozen["confirmationPanelPath"])) != frozen["confirmationPanelSha256"]:
        raise ValueError("confirmation panel differs from frozen candidate")
    if sha256_file(Path(frozen["confirmationPanelManifestPath"])) != frozen["confirmationPanelManifestSha256"]:
        raise ValueError("confirmation panel manifest differs from frozen candidate")
    return frozen
