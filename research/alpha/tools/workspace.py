#!/usr/bin/env python3
"""Discover and validate Marketlab's decentralized alpha workspace."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import sys
from datetime import datetime
from pathlib import Path
from typing import Any

from experiment import MAX_SECONDS, DOMAINS, validate_contract


ALPHA_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = Path(__file__).resolve().parents[3]
SPACES_ROOT = ALPHA_ROOT / "spaces"
INVENTORY_ROOT = REPO_ROOT / "research" / "inventory"
POLICY_PATH = ALPHA_ROOT / "workspace-policy.json"
ID_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")

SPACE_STATES = {"OPEN", "ACTIVE", "DATA_ACCUMULATING", "DATA_BLOCKED", "CLOSED"}
TASK_STATES = {"READY", "BLOCKED", "DONE"}
TASK_KINDS = {"research", "data", "model", "evaluation", "infrastructure"}
TASK_PRIORITIES = {"P0": 0, "P1": 1, "P2": 2}
RESOURCE_CLASSES = {"METADATA_ONLY", "DATA_IO", "CPU_MODEL"}
OUTCOME_ACCESS = {"NONE", "DEVELOPMENT_ONLY", "SINGLE_USE_HISTORICAL_CONFIRMATION"}
CANDIDATE_STAGES = {
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
}
TERMINAL_STAGES = {"REJECTED", "INCONCLUSIVE", "DATA_BLOCKED", "OPERATIONALLY_BLOCKED"}
THEORY_KINDS = {"CONTROL", "PARENT", "ADAPTATION", "PROSPECTIVE"}
THEORY_DISPOSITIONS = {"CONTROL"} | CANDIDATE_STAGES
SOURCE_STATES = {
    "ACTIVE",
    "PARTIAL",
    "IMPLEMENTED_NOT_RUNNING",
    "HISTORICAL_ONLY",
    "REGISTERED_NOT_IMPLEMENTED",
    "EXCLUDED",
    "MISSING",
}
COMPONENT_TYPES = {"GRADLE_MODULE", "PYTHON_COMPONENT", "OPERATIONS_COMPONENT", "GOVERNANCE_COMPONENT"}
RESEARCH_MODES = {"HISTORICAL", "PROSPECTIVE"}
ARTIFACT_VERIFICATION_MODES = {"LOCAL_FILE", "REMOTE_FILE", "CONTENT_ADDRESS_ONLY"}
PERIOD_STATUSES = {"EXACT", "UNKNOWN"}
DISCOVERY_BIASES = {"AVAILABILITY_TIMESTAMP_PROXY", "OPENED_OUTCOMES", "REVISED_VALUES", "SURVIVOR_UNIVERSE"}


def load_json(path: Path) -> dict[str, Any]:
    def reject_duplicate(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError(f"duplicate JSON key {key!r}")
            result[key] = value
        return result

    with path.open(encoding="utf-8") as handle:
        value = json.load(handle, object_pairs_hook=reject_duplicate)
    if not isinstance(value, dict):
        raise ValueError("top-level JSON value must be an object")
    return value


def discover(alpha_root: Path = ALPHA_ROOT) -> tuple[list[tuple[Path, dict[str, Any]]], list[tuple[Path, dict[str, Any]]]]:
    spaces: list[tuple[Path, dict[str, Any]]] = []
    candidates: list[tuple[Path, dict[str, Any]]] = []
    spaces_root = alpha_root / "spaces"
    for path in sorted(spaces_root.glob("*/space.json")):
        spaces.append((path, load_json(path)))
    for path in sorted(spaces_root.glob("*/candidates/*/candidate.json")):
        candidates.append((path, load_json(path)))
    return spaces, candidates


def discover_inventory(inventory_root: Path = INVENTORY_ROOT) -> dict[str, Any]:
    return {
        "theories": [(path, load_json(path)) for path in sorted((inventory_root / "theories").glob("*.json"))],
        "evidence": [(path, load_json(path)) for path in sorted((inventory_root / "evidence").glob("*.json"))],
        "dataSources": [(path, load_json(path)) for path in sorted((inventory_root / "data-sources").glob("*.json"))],
        "components": load_json(inventory_root / "components.json"),
        "operations": load_json(inventory_root / "operations.json"),
        "computeRuns": [
            (path, load_json(path)) for path in sorted((inventory_root / "compute-runs").glob("*.json"))
        ],
    }


def load_policy(alpha_root: Path = ALPHA_ROOT) -> dict[str, Any]:
    return load_json(alpha_root / "workspace-policy.json")


def parse_timestamp(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def validate_modes(value: dict[str, Any], location: str, errors: list[str]) -> None:
    modes = value.get("researchModes")
    if not isinstance(modes, list) or not modes or any(mode not in RESEARCH_MODES for mode in modes):
        errors.append(f"{location}: researchModes must be a non-empty list containing HISTORICAL or PROSPECTIVE")


def require_string(value: dict[str, Any], key: str, location: str, errors: list[str]) -> None:
    if not isinstance(value.get(key), str) or not value[key].strip():
        errors.append(f"{location}: {key} must be a non-empty string")


def require_string_list(value: dict[str, Any], key: str, location: str, errors: list[str]) -> None:
    field = value.get(key)
    if not isinstance(field, list) or any(not isinstance(item, str) or not item.strip() for item in field):
        errors.append(f"{location}: {key} must be a list of non-empty strings")


def validate(alpha_root: Path = ALPHA_ROOT, repo_root: Path = REPO_ROOT) -> list[str]:
    errors: list[str] = []
    try:
        spaces, candidates = discover(alpha_root)
        inventory_root = repo_root / "research" / "inventory"
        inventory = discover_inventory(inventory_root)
        policy = load_policy(alpha_root)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        return [f"workspace discovery failed: {error}"]

    if not spaces:
        errors.append("workspace contains no alpha spaces")
        return errors

    if policy.get("schemaVersion") != "marketlab.alpha-workspace-policy.v2":
        errors.append("research/alpha/workspace-policy.json: unsupported schemaVersion")
    if policy.get("activeResearchMode") not in RESEARCH_MODES:
        errors.append("research/alpha/workspace-policy.json: activeResearchMode must be HISTORICAL or PROSPECTIVE")
    if not isinstance(policy.get("futureOutcomeWaitingAllowed"), bool):
        errors.append("research/alpha/workspace-policy.json: futureOutcomeWaitingAllowed must be boolean")
    discovery_policy = policy.get("discoveryPolicy")
    if not isinstance(discovery_policy, dict):
        errors.append("research/alpha/workspace-policy.json: discoveryPolicy must be an object")
    else:
        if discovery_policy.get("availabilityBiasedHistoricalDiscoveryAllowed") is not True:
            errors.append("research/alpha/workspace-policy.json: biased historical discovery must be explicit")
        if not isinstance(discovery_policy.get("allowedBiases"), list) or set(discovery_policy["allowedBiases"]) != DISCOVERY_BIASES:
            errors.append("research/alpha/workspace-policy.json: discoveryPolicy.allowedBiases is invalid")
        if discovery_policy.get("targetLeakageAllowed") is not False:
            errors.append("research/alpha/workspace-policy.json: target leakage must remain forbidden")
        if discovery_policy.get("promotionCeiling") != "EXPLORATORY" or discovery_policy.get("requireBiasLedger") is not True:
            errors.append("research/alpha/workspace-policy.json: discovery ceiling and bias ledger are required")
    activation = policy.get("prospectiveActivationPolicy")
    if not isinstance(activation, dict):
        errors.append("research/alpha/workspace-policy.json: prospectiveActivationPolicy must be an object")
    else:
        for key in ("requiresFrozenCandidate", "requiresPassedDevelopmentGates", "requiresOutcomeSealing", "requiresNoInterimOutcomeAccess"):
            if activation.get(key) is not True:
                errors.append(f"research/alpha/workspace-policy.json: prospectiveActivationPolicy.{key} must be true")
        if activation.get("ordinaryFutureWaitingAllowed") is not False:
            errors.append("research/alpha/workspace-policy.json: ordinary future waiting must be false")
        for key in ("minimumQualityValidDays", "minimumActedOnEvents", "maximumCalendarDays"):
            if not isinstance(activation.get(key), int) or activation[key] <= 0:
                errors.append(f"research/alpha/workspace-policy.json: prospectiveActivationPolicy.{key} must be positive")
    compute_defaults = policy.get("computeDefaults")
    if not isinstance(compute_defaults, dict):
        errors.append("research/alpha/workspace-policy.json: computeDefaults must be an object")
    else:
        for key in ("maxConcurrentTrials", "blasThreads", "maxBlasThreads", "defaultCandidateTrialBudget"):
            if not isinstance(compute_defaults.get(key), int) or compute_defaults[key] <= 0:
                errors.append(f"research/alpha/workspace-policy.json: computeDefaults.{key} must be positive")
        if not isinstance(compute_defaults.get("gpuRequiresJustification"), bool):
            errors.append("research/alpha/workspace-policy.json: computeDefaults.gpuRequiresJustification must be boolean")
    routing = policy.get("routing")
    if not isinstance(routing, dict):
        errors.append("research/alpha/workspace-policy.json: routing must be an object")
    else:
        for key in ("maxConcurrentDataAudits", "maxConcurrentModelJobs"):
            if not isinstance(routing.get(key), int) or routing[key] <= 0:
                errors.append(f"research/alpha/workspace-policy.json: routing.{key} must be positive")
        if routing.get("defaultReadyView") != "HIGHEST_PRIORITY_ONLY":
            errors.append("research/alpha/workspace-policy.json: routing.defaultReadyView must be HIGHEST_PRIORITY_ONLY")
    confirmation = policy.get("historicalConfirmation")
    if not isinstance(confirmation, dict):
        errors.append("research/alpha/workspace-policy.json: historicalConfirmation must be an object")
    else:
        if confirmation.get("authorization") != "AUTONOMOUS_AFTER_VALIDATED_FREEZE":
            errors.append("research/alpha/workspace-policy.json: unsupported historical confirmation authorization")
        for key in ("requireEmptyLedger", "requireNoOpenedOutcomeOverlap", "requireExactArtifactHashes"):
            if confirmation.get(key) is not True:
                errors.append(f"research/alpha/workspace-policy.json: historicalConfirmation.{key} must be true")

    experiment_policy = policy.get("experimentPolicy", {})
    if (experiment_policy.get("maximumBudgetSeconds") != MAX_SECONDS
            or experiment_policy.get("defaultBudgetSeconds") != MAX_SECONDS
            or experiment_policy.get("defaultReportReserveSeconds") != 900
            or experiment_policy.get("clockStartsBeforeAcquisition") is not True
            or experiment_policy.get("restartResetsDeadline") is not False):
        errors.append("workspace policy must enforce the persistent 42300-second experiment budget")
    agenda = policy.get("researchAgenda", {})
    if set(agenda.get("initialDomains", [])) != DOMAINS - {"crypto"} or agenda.get("coverage") != "BALANCED":
        errors.append("research agenda must include balanced coverage of all four new domains")

    space_ids: set[str] = set()
    task_ids: set[str] = set()
    task_records: list[tuple[dict[str, Any], str]] = []
    for path, space in spaces:
        location = str(path.relative_to(repo_root)) if path.is_relative_to(repo_root) else str(path)
        if space.get("schemaVersion") != "marketlab.alpha-space.v2":
            errors.append(f"{location}: unsupported schemaVersion")
        for key in ("id", "title", "purpose", "state"):
            require_string(space, key, location, errors)
        space_id = space.get("id")
        if isinstance(space_id, str):
            if not ID_PATTERN.fullmatch(space_id):
                errors.append(f"{location}: id must be kebab-case")
            if space_id != path.parent.name:
                errors.append(f"{location}: id must match its directory name")
            if space_id in space_ids:
                errors.append(f"{location}: duplicate space id {space_id}")
            space_ids.add(space_id)
        if space.get("state") not in SPACE_STATES:
            errors.append(f"{location}: invalid state {space.get('state')!r}")
        require_string_list(space, "dependencies", location, errors)
        work = space.get("readyWork")
        if not isinstance(work, list):
            errors.append(f"{location}: readyWork must be a list")
            continue
        for index, task in enumerate(work):
            task_location = f"{location}:readyWork[{index}]"
            if not isinstance(task, dict):
                errors.append(f"{task_location}: task must be an object")
                continue
            for key in ("id", "status", "kind", "summary"):
                require_string(task, key, task_location, errors)
            task_id = task.get("id")
            if isinstance(task_id, str):
                if not ID_PATTERN.fullmatch(task_id):
                    errors.append(f"{task_location}: id must be kebab-case")
                if task_id in task_ids:
                    errors.append(f"{task_location}: duplicate task id {task_id}")
                task_ids.add(task_id)
            if task.get("status") not in TASK_STATES:
                errors.append(f"{task_location}: invalid status {task.get('status')!r}")
            if task.get("kind") not in TASK_KINDS:
                errors.append(f"{task_location}: invalid kind {task.get('kind')!r}")
            if task.get("priority") not in TASK_PRIORITIES:
                errors.append(f"{task_location}: invalid priority {task.get('priority')!r}")
            if task.get("resourceClass") not in RESOURCE_CLASSES:
                errors.append(f"{task_location}: invalid resourceClass {task.get('resourceClass')!r}")
            if task.get("outcomeAccess") not in OUTCOME_ACCESS:
                errors.append(f"{task_location}: invalid outcomeAccess {task.get('outcomeAccess')!r}")
            validate_modes(task, task_location, errors)
            for key in ("blockedBy", "externalBlockers", "candidateIds", "deliverables", "acceptanceCriteria", "claimSurfaces", "completionEvidenceIds"):
                require_string_list(task, key, task_location, errors)
            for key in ("deliverables", "acceptanceCriteria", "claimSurfaces"):
                if task.get(key) == []:
                    errors.append(f"{task_location}: {key} must not be empty")
            compute_limits = task.get("computeLimits")
            if not isinstance(compute_limits, dict):
                errors.append(f"{task_location}: computeLimits must be an object")
            else:
                for key in ("maxProcesses", "blasThreads", "maxTrials"):
                    if not isinstance(compute_limits.get(key), int) or compute_limits[key] < 0:
                        errors.append(f"{task_location}: computeLimits.{key} must be a non-negative integer")
                if compute_limits.get("maxProcesses", 0) > policy.get("computeDefaults", {}).get("maxConcurrentTrials", 0):
                    errors.append(f"{task_location}: maxProcesses exceeds workspace policy")
                if compute_limits.get("blasThreads", 0) > policy.get("computeDefaults", {}).get("maxBlasThreads", 0):
                    errors.append(f"{task_location}: blasThreads exceeds workspace policy")
                if compute_limits.get("maxTrials", 0) > policy.get("computeDefaults", {}).get("defaultCandidateTrialBudget", 0):
                    errors.append(f"{task_location}: maxTrials exceeds workspace policy")
                if not isinstance(compute_limits.get("gpu"), bool):
                    errors.append(f"{task_location}: computeLimits.gpu must be boolean")
            if task.get("status") != "DONE" and "HISTORICAL" in task.get("researchModes", []):
                limits = task.get("computeLimits", {})
                budget, reserve = limits.get("budgetSeconds"), limits.get("reportReserveSeconds")
                if type(budget) is not int or not 0 < budget <= MAX_SECONDS:
                    errors.append(f"{task_location}: invalid historical experiment budgetSeconds")
                if type(reserve) is not int or type(budget) is not int or not 0 < reserve < budget:
                    errors.append(f"{task_location}: invalid reportReserveSeconds")
                if type(limits.get("memoryMiB")) is not int or not 64 <= limits["memoryMiB"] <= 32768:
                    errors.append(f"{task_location}: invalid memoryMiB")
            if task.get("experimentContract"):
                try:
                    contract = validate_contract(load_json(repo_root / task["experimentContract"]))
                    if contract["candidateId"] not in task.get("candidateIds", []):
                        errors.append(f"{task_location}: experiment candidate mismatch")
                    if contract["outcomeAccess"] != task.get("outcomeAccess") or contract["domains"] != task.get("domains"):
                        errors.append(f"{task_location}: experiment access/domain mismatch")
                    for key in ("budgetSeconds", "reportReserveSeconds", "maxTrials", "blasThreads", "gpu", "memoryMiB"):
                        if contract[key] != task.get("computeLimits", {}).get(key):
                            errors.append(f"{task_location}: experiment {key} differs from task")
                except (OSError, ValueError, KeyError, TypeError) as error:
                    errors.append(f"{task_location}: invalid experiment contract: {error}")
            blockers = task.get("blockedBy")
            external_blockers = task.get("externalBlockers")
            if task.get("status") == "READY" and (blockers or external_blockers):
                errors.append(f"{task_location}: READY work cannot have blockers")
            if task.get("status") == "BLOCKED" and blockers == [] and external_blockers == []:
                errors.append(f"{task_location}: BLOCKED work must name at least one blocker")
            if task.get("status") == "DONE" and task.get("completionEvidenceIds") == []:
                errors.append(f"{task_location}: DONE work must link completion evidence")
            if task.get("status") != "DONE" and task.get("completionEvidenceIds"):
                errors.append(f"{task_location}: only DONE work may link completion evidence")
            task_records.append((task, task_location))

    task_dependencies: dict[str, list[str]] = {}
    for task, location in task_records:
        task_id = task.get("id")
        if not isinstance(task_id, str):
            continue
        task_dependencies[task_id] = task.get("blockedBy", [])
        for blocker in task.get("blockedBy", []):
            if blocker not in task_ids:
                errors.append(f"{location}: unknown task blocker {blocker!r}")
    visiting: set[str] = set()
    visited: set[str] = set()

    def visit_task(task_id: str) -> None:
        if task_id in visiting:
            errors.append(f"task dependency cycle includes {task_id}")
            return
        if task_id in visited:
            return
        visiting.add(task_id)
        for blocker in task_dependencies.get(task_id, []):
            visit_task(blocker)
        visiting.remove(task_id)
        visited.add(task_id)

    for task_id in sorted(task_dependencies):
        visit_task(task_id)

    for path, space in spaces:
        location = str(path.relative_to(repo_root)) if path.is_relative_to(repo_root) else str(path)
        for dependency in space.get("dependencies", []):
            if dependency == space.get("id"):
                errors.append(f"{location}: a space cannot depend on itself")
            elif dependency not in space_ids:
                errors.append(f"{location}: unknown dependency {dependency!r}")

    candidate_ids: set[str] = set()
    candidates_by_id: dict[str, dict[str, Any]] = {}
    candidate_evidence_links: dict[str, list[str]] = {}
    for path, candidate in candidates:
        location = str(path.relative_to(repo_root)) if path.is_relative_to(repo_root) else str(path)
        if candidate.get("schemaVersion") != "marketlab.alpha-candidate.v1":
            errors.append(f"{location}: unsupported schemaVersion")
        for key in (
            "id",
            "spaceId",
            "title",
            "stage",
            "claim",
            "mechanism",
            "target",
            "horizon",
            "primaryBaseline",
            "nextAction",
        ):
            require_string(candidate, key, location, errors)
        for key in ("informationSet", "frozenLocks", "touchPoints"):
            require_string_list(candidate, key, location, errors)
        candidate_id = candidate.get("id")
        if isinstance(candidate_id, str):
            if not ID_PATTERN.fullmatch(candidate_id):
                errors.append(f"{location}: id must be kebab-case")
            if candidate_id != path.parent.name:
                errors.append(f"{location}: id must match its directory name")
            if candidate_id in candidate_ids:
                errors.append(f"{location}: duplicate candidate id {candidate_id}")
            candidate_ids.add(candidate_id)
            candidates_by_id[candidate_id] = candidate
        actual_space = path.parents[2].name
        if candidate.get("spaceId") != actual_space:
            errors.append(f"{location}: spaceId must match parent alpha space")
        if candidate.get("spaceId") not in space_ids:
            errors.append(f"{location}: unknown spaceId {candidate.get('spaceId')!r}")
        if candidate.get("stage") not in CANDIDATE_STAGES:
            errors.append(f"{location}: invalid stage {candidate.get('stage')!r}")
        validate_modes(candidate, location, errors)
        if candidate.get("experimentContract"):
            try:
                contract = validate_contract(load_json(path.parent / candidate["experimentContract"]))
                if contract["candidateId"] != candidate.get("id") or contract["searchFamilyId"] != candidate.get("searchFamilyId"):
                    errors.append(f"{location}: experiment identity mismatch")
                for key in ("domains", "targetMarket", "targetVenue"):
                    if candidate.get(key) != contract[key]:
                        errors.append(f"{location}: experiment {key} mismatch")
            except (OSError, ValueError, KeyError, TypeError) as error:
                errors.append(f"{location}: invalid experiment contract: {error}")
        data_biases = candidate.get("dataBiases")
        if data_biases is not None:
            if not isinstance(data_biases, list) or not data_biases or any(item not in DISCOVERY_BIASES for item in data_biases):
                errors.append(f"{location}: dataBiases must contain recognized discovery biases")
            if candidate.get("promotionCeiling") != "EXPLORATORY":
                errors.append(f"{location}: biased discovery promotionCeiling must be EXPLORATORY")
            if candidate.get("openedOutcomeReuse") not in {"NONE", "DISCOVERY_ONLY"}:
                errors.append(f"{location}: biased discovery openedOutcomeReuse must be NONE or DISCOVERY_ONLY")
        activation_gate = candidate.get("prospectiveActivationGate")
        if activation_gate is not None:
            if not isinstance(activation_gate, dict):
                errors.append(f"{location}: prospectiveActivationGate must be an object")
            else:
                for key in ("developmentPassed", "candidateFrozen", "outcomeSealingReady"):
                    if not isinstance(activation_gate.get(key), bool):
                        errors.append(f"{location}: prospectiveActivationGate.{key} must be boolean")
                eligible = all(activation_gate.get(key) is True for key in ("developmentPassed", "candidateFrozen", "outcomeSealingReady"))
                if activation_gate.get("eligible") is not eligible:
                    errors.append(f"{location}: prospectiveActivationGate.eligible is inconsistent")
        evidence = candidate.get("evidence")
        if not isinstance(evidence, list):
            errors.append(f"{location}: evidence must be a list")
            evidence = []
        if candidate.get("stage") in TERMINAL_STAGES and not evidence:
            errors.append(f"{location}: terminal candidates must retain evidence")
        for index, item in enumerate(evidence):
            evidence_location = f"{location}:evidence[{index}]"
            if not isinstance(item, dict):
                errors.append(f"{evidence_location}: evidence must be an object")
                continue
            for key in ("classification", "decision", "summary"):
                require_string(item, key, evidence_location, errors)
            require_string(item, "evidenceId", evidence_location, errors)
            evidence_id = item.get("evidenceId")
            if isinstance(candidate_id, str) and isinstance(evidence_id, str):
                candidate_evidence_links.setdefault(candidate_id, []).append(evidence_id)
            source_path = item.get("sourcePath")
            if source_path is not None:
                if not isinstance(source_path, str) or not source_path:
                    errors.append(f"{evidence_location}: sourcePath must be a non-empty string")
                elif not (repo_root / source_path).is_file():
                    errors.append(f"{evidence_location}: sourcePath does not exist: {source_path}")
        for key in ("frozenLocks", "touchPoints"):
            for referenced in candidate.get(key, []):
                if not (repo_root / referenced).exists():
                    errors.append(f"{location}: {key} path does not exist: {referenced}")

    for task, location in task_records:
        linked_candidates = task.get("candidateIds", [])
        for candidate_id in linked_candidates:
            if candidate_id not in candidate_ids:
                errors.append(f"{location}: unknown candidateId {candidate_id!r}")
        if task.get("outcomeAccess") == "SINGLE_USE_HISTORICAL_CONFIRMATION" and task.get("status") == "READY":
            if not linked_candidates:
                errors.append(f"{location}: confirmation work must name a candidate")
            for candidate_id in linked_candidates:
                if candidates_by_id.get(candidate_id, {}).get("stage") != "FROZEN_CANDIDATE":
                    errors.append(f"{location}: confirmation candidate {candidate_id!r} must be FROZEN_CANDIDATE")
            confirmation_contract = task.get("confirmationContract")
            if not isinstance(confirmation_contract, dict):
                errors.append(f"{location}: READY confirmation work must define confirmationContract")
            else:
                for key in ("venue", "startInclusive", "endExclusive", "ledgerPath", "freezeSha256"):
                    require_string(confirmation_contract, key, location, errors)
                freeze_sha = confirmation_contract.get("freezeSha256")
                if isinstance(freeze_sha, str) and not SHA256_PATTERN.fullmatch(freeze_sha):
                    errors.append(f"{location}: confirmation freezeSha256 must be lowercase SHA-256")

    components = inventory["components"]
    component_location = "research/inventory/components.json"
    if components.get("schemaVersion") != "marketlab.component-inventory.v1":
        errors.append(f"{component_location}: unsupported schemaVersion")
    component_values = components.get("components")
    component_values = component_values if isinstance(component_values, list) else []
    if not isinstance(components.get("components"), list):
        errors.append(f"{component_location}: components must be a list")
    component_ids: set[str] = set()
    gradle_component_ids: set[str] = set()
    for index, component in enumerate(component_values):
        location = f"{component_location}:components[{index}]"
        if not isinstance(component, dict):
            errors.append(f"{location}: component must be an object")
            continue
        for key in ("id", "path", "type", "layer", "role"):
            require_string(component, key, location, errors)
        require_string_list(component, "alphaSpaces", location, errors)
        component_id = component.get("id")
        if isinstance(component_id, str):
            if component_id in component_ids:
                errors.append(f"{location}: duplicate component id {component_id}")
            component_ids.add(component_id)
        if component.get("type") not in COMPONENT_TYPES:
            errors.append(f"{location}: invalid type {component.get('type')!r}")
        if component.get("type") == "GRADLE_MODULE" and isinstance(component_id, str):
            gradle_component_ids.add(component_id)
        if not isinstance(component.get("shared"), bool):
            errors.append(f"{location}: shared must be boolean")
        path_value = component.get("path")
        if isinstance(path_value, str) and not (repo_root / path_value).exists():
            errors.append(f"{location}: path does not exist: {path_value}")
        for space_id in component.get("alphaSpaces", []):
            if space_id not in space_ids:
                errors.append(f"{location}: unknown alpha space {space_id!r}")

    settings_text = (repo_root / "settings.gradle.kts").read_text(encoding="utf-8")
    included_modules = set(re.findall(r'^\s*"([a-z0-9-]+)",?\s*$', settings_text, flags=re.MULTILINE))
    if gradle_component_ids != included_modules:
        missing = sorted(included_modules - gradle_component_ids)
        extra = sorted(gradle_component_ids - included_modules)
        errors.append(f"{component_location}: Gradle module coverage mismatch missing={missing} extra={extra}")

    theory_ids: set[str] = set()
    theory_candidate_ids: set[str] = set()
    for path, theory in inventory["theories"]:
        location = str(path.relative_to(repo_root))
        if theory.get("schemaVersion") != "marketlab.theory-inventory.v1":
            errors.append(f"{location}: unsupported schemaVersion")
        require_string(theory, "id", location, errors)
        require_string(theory, "kind", location, errors)
        require_string(theory, "disposition", location, errors)
        require_string(theory, "implementationPath", location, errors)
        theory_id = theory.get("id")
        if isinstance(theory_id, str):
            if theory_id != path.stem:
                errors.append(f"{location}: id must match its filename")
            if theory_id in theory_ids:
                errors.append(f"{location}: duplicate theory id {theory_id}")
            theory_ids.add(theory_id)
        if theory.get("kind") not in THEORY_KINDS:
            errors.append(f"{location}: invalid kind {theory.get('kind')!r}")
        if theory.get("disposition") not in THEORY_DISPOSITIONS:
            errors.append(f"{location}: invalid disposition {theory.get('disposition')!r}")
        implementation_path = theory.get("implementationPath")
        if isinstance(implementation_path, str) and not (repo_root / implementation_path).is_file():
            errors.append(f"{location}: implementationPath does not exist: {implementation_path}")
        alpha_space = theory.get("alphaSpace")
        candidate_id = theory.get("candidateId")
        if theory.get("kind") == "CONTROL":
            if alpha_space is not None or candidate_id is not None or theory.get("disposition") != "CONTROL":
                errors.append(f"{location}: controls must have null alphaSpace/candidateId and CONTROL disposition")
        else:
            if alpha_space not in space_ids:
                errors.append(f"{location}: unknown alphaSpace {alpha_space!r}")
            if candidate_id not in candidate_ids:
                errors.append(f"{location}: candidateId does not exist: {candidate_id!r}")
            elif isinstance(candidate_id, str):
                theory_candidate_ids.add(candidate_id)
                matching = next(value for _, value in candidates if value.get("id") == candidate_id)
                if matching.get("spaceId") != alpha_space:
                    errors.append(f"{location}: candidate alpha space does not match theory inventory")
                if matching.get("stage") != theory.get("disposition"):
                    errors.append(f"{location}: candidate stage does not match disposition")

    compiled_theory_ids: set[str] = set()
    for source in (
        repo_root / "theories/src/main/kotlin/dev/marketlab/theories/AcademicTheoryRegistry.kt",
        repo_root / "theories/src/main/kotlin/dev/marketlab/theories/SocialInformationTheories.kt",
    ):
        compiled_theory_ids.update(re.findall(r'id\s*=\s*"([a-z0-9-]+)"', source.read_text(encoding="utf-8")))
    if theory_ids != compiled_theory_ids:
        missing = sorted(compiled_theory_ids - theory_ids)
        extra = sorted(theory_ids - compiled_theory_ids)
        errors.append(f"research/inventory/theories: compiled theory coverage mismatch missing={missing} extra={extra}")

    evidence_ids: set[str] = set()
    evidence_by_id: dict[str, dict[str, Any]] = {}
    current_decisions: dict[str, list[dict[str, Any]]] = {}
    for path, evidence_record in inventory["evidence"]:
        location = str(path.relative_to(repo_root))
        if evidence_record.get("schemaVersion") != "marketlab.evidence-inventory.v2":
            errors.append(f"{location}: unsupported schemaVersion")
        for key in ("id", "classification", "decision", "summarySource", "summary"):
            require_string(evidence_record, key, location, errors)
        require_string_list(evidence_record, "candidateIds", location, errors)
        evidence_id = evidence_record.get("id")
        if isinstance(evidence_id, str):
            if evidence_id != path.stem:
                errors.append(f"{location}: id must match its filename")
            if evidence_id in evidence_ids:
                errors.append(f"{location}: duplicate evidence id {evidence_id}")
            evidence_ids.add(evidence_id)
            evidence_by_id[evidence_id] = evidence_record
        for candidate_id in evidence_record.get("candidateIds", []):
            if candidate_id not in candidate_ids:
                errors.append(f"{location}: unknown candidateId {candidate_id!r}")
        summary_source = evidence_record.get("summarySource")
        if isinstance(summary_source, str) and not (repo_root / summary_source).is_file():
            errors.append(f"{location}: summarySource does not exist: {summary_source}")
        decisions = evidence_record.get("candidateDecisions")
        if not isinstance(decisions, list) or not decisions:
            errors.append(f"{location}: candidateDecisions must be a non-empty list")
            decisions = []
        for index, decision in enumerate(decisions):
            decision_location = f"{location}:candidateDecisions[{index}]"
            if not isinstance(decision, dict):
                errors.append(f"{decision_location}: decision must be an object")
                continue
            for key in ("candidateId", "stage", "decision"):
                require_string(decision, key, decision_location, errors)
            if decision.get("candidateId") not in evidence_record.get("candidateIds", []):
                errors.append(f"{decision_location}: candidateId must be listed by the evidence record")
            if decision.get("stage") not in CANDIDATE_STAGES:
                errors.append(f"{decision_location}: invalid stage {decision.get('stage')!r}")
            if not isinstance(decision.get("current"), bool):
                errors.append(f"{decision_location}: current must be boolean")
            elif decision["current"]:
                current_decisions.setdefault(decision.get("candidateId"), []).append(decision)
        outcomes_opened = evidence_record.get("outcomesOpened")
        if not isinstance(outcomes_opened, bool):
            errors.append(f"{location}: outcomesOpened must be boolean")
        opened_outcomes = evidence_record.get("openedOutcomes")
        if not isinstance(opened_outcomes, list):
            errors.append(f"{location}: openedOutcomes must be a list")
            opened_outcomes = []
        if outcomes_opened is True and not opened_outcomes:
            errors.append(f"{location}: opened evidence must identify at least one outcome exposure")
        if outcomes_opened is False and opened_outcomes:
            errors.append(f"{location}: unopened evidence cannot list outcome exposures")
        for index, outcome in enumerate(opened_outcomes):
            outcome_location = f"{location}:openedOutcomes[{index}]"
            if not isinstance(outcome, dict):
                errors.append(f"{outcome_location}: outcome must be an object")
                continue
            for key in ("id", "venue", "target", "periodStatus", "reuseStatus"):
                require_string(outcome, key, outcome_location, errors)
            require_string_list(outcome, "candidateIds", outcome_location, errors)
            for linked_candidate in outcome.get("candidateIds", []):
                if linked_candidate not in evidence_record.get("candidateIds", []):
                    errors.append(f"{outcome_location}: candidateId {linked_candidate!r} is not owned by the evidence record")
            if outcome.get("periodStatus") not in PERIOD_STATUSES:
                errors.append(f"{outcome_location}: invalid periodStatus {outcome.get('periodStatus')!r}")
            if outcome.get("reuseStatus") != "OPENED_NOT_CONFIRMATION_ELIGIBLE":
                errors.append(f"{outcome_location}: reuseStatus must be OPENED_NOT_CONFIRMATION_ELIGIBLE")
            if outcome.get("periodStatus") == "EXACT":
                for key in ("startInclusive", "endExclusive"):
                    require_string(outcome, key, outcome_location, errors)
                try:
                    if parse_timestamp(outcome["startInclusive"]) >= parse_timestamp(outcome["endExclusive"]):
                        errors.append(f"{outcome_location}: startInclusive must precede endExclusive")
                except (KeyError, TypeError, ValueError):
                    errors.append(f"{outcome_location}: exact period timestamps must be ISO-8601")
            elif outcome.get("periodStatus") == "UNKNOWN":
                require_string(outcome, "unknownReason", outcome_location, errors)
        artifacts = evidence_record.get("artifacts")
        if not isinstance(artifacts, list):
            errors.append(f"{location}: artifacts must be a list")
            artifacts = []
        for index, artifact in enumerate(artifacts):
            artifact_location = f"{location}:artifacts[{index}]"
            if not isinstance(artifact, dict):
                errors.append(f"{artifact_location}: artifact must be an object")
                continue
            for key in ("role", "sha256", "location", "verification"):
                require_string(artifact, key, artifact_location, errors)
            if isinstance(artifact.get("sha256"), str) and not SHA256_PATTERN.fullmatch(artifact["sha256"]):
                errors.append(f"{artifact_location}: sha256 must be lowercase hexadecimal")
            if artifact.get("verification") not in ARTIFACT_VERIFICATION_MODES:
                errors.append(f"{artifact_location}: invalid verification mode {artifact.get('verification')!r}")

    for candidate_id, links in candidate_evidence_links.items():
        for evidence_id in links:
            record = evidence_by_id.get(evidence_id)
            if record is None:
                errors.append(f"candidate {candidate_id}: unknown evidenceId {evidence_id!r}")
            elif candidate_id not in record.get("candidateIds", []):
                errors.append(f"candidate {candidate_id}: evidence {evidence_id!r} does not link back to the candidate")
        decisions = current_decisions.get(candidate_id, [])
        if len(decisions) != 1:
            errors.append(f"candidate {candidate_id}: expected exactly one current evidence decision, found {len(decisions)}")
        elif decisions[0].get("stage") != candidates_by_id[candidate_id].get("stage"):
            errors.append(f"candidate {candidate_id}: stage does not match current evidence decision")

    compute_ids: set[str] = set()
    for path, record in inventory["computeRuns"]:
        location = str(path.relative_to(repo_root))
        if record.get("schemaVersion") != "marketlab.compute-run.v1":
            errors.append(f"{location}: unsupported schemaVersion")
        for key in ("id", "campaignId", "workload", "sourcePath"):
            require_string(record, key, location, errors)
        require_string_list(record, "candidateIds", location, errors)
        require_string_list(record, "evidenceIds", location, errors)
        record_id = record.get("id")
        if isinstance(record_id, str):
            if record_id != path.stem:
                errors.append(f"{location}: id must match its filename")
            if record_id in compute_ids:
                errors.append(f"{location}: duplicate compute id {record_id}")
            compute_ids.add(record_id)
        for linked_candidate in record.get("candidateIds", []):
            if linked_candidate not in candidate_ids:
                errors.append(f"{location}: unknown candidateId {linked_candidate!r}")
        for linked_evidence in record.get("evidenceIds", []):
            if linked_evidence not in evidence_ids:
                errors.append(f"{location}: unknown evidenceId {linked_evidence!r}")
        source_path = record.get("sourcePath")
        if isinstance(source_path, str) and not (repo_root / source_path).is_file():
            errors.append(f"{location}: sourcePath does not exist: {source_path}")
        observations = record.get("observations")
        if not isinstance(observations, list) or not observations:
            errors.append(f"{location}: observations must be a non-empty list")
        if not isinstance(record.get("recommendation"), dict):
            errors.append(f"{location}: recommendation must be an object")

    for task, location in task_records:
        for evidence_id in task.get("completionEvidenceIds", []):
            if evidence_id not in evidence_ids:
                errors.append(f"{location}: unknown completion evidence {evidence_id!r}")

    source_ids: set[str] = set()
    for path, source in inventory["dataSources"]:
        location = str(path.relative_to(repo_root))
        if source.get("schemaVersion") != "marketlab.data-source-inventory.v1":
            errors.append(f"{location}: unsupported schemaVersion")
        for key in ("id", "title", "category", "status", "pointInTimeUse", "nextAction"):
            require_string(source, key, location, errors)
        for key in ("implementations", "alphaSpaces", "limitations"):
            require_string_list(source, key, location, errors)
        source_id = source.get("id")
        if isinstance(source_id, str):
            if source_id != path.stem:
                errors.append(f"{location}: id must match its filename")
            if source_id in source_ids:
                errors.append(f"{location}: duplicate data source id {source_id}")
            source_ids.add(source_id)
        if source.get("status") not in SOURCE_STATES:
            errors.append(f"{location}: invalid status {source.get('status')!r}")
        for component_id in source.get("implementations", []):
            if component_id not in component_ids:
                errors.append(f"{location}: unknown implementation component {component_id!r}")
        for space_id in source.get("alphaSpaces", []):
            if space_id not in space_ids:
                errors.append(f"{location}: unknown alpha space {space_id!r}")

    operations = inventory["operations"]
    operation_location = "research/inventory/operations.json"
    if operations.get("schemaVersion") != "marketlab.operations-inventory.v1":
        errors.append(f"{operation_location}: unsupported schemaVersion")
    operation_values = operations.get("operations")
    operation_values = operation_values if isinstance(operation_values, list) else []
    if not isinstance(operations.get("operations"), list):
        errors.append(f"{operation_location}: operations must be a list")
    operation_ids: set[str] = set()
    for index, operation in enumerate(operation_values):
        location = f"{operation_location}:operations[{index}]"
        if not isinstance(operation, dict):
            errors.append(f"{location}: operation must be an object")
            continue
        for key in ("id", "kind", "path", "purpose"):
            require_string(operation, key, location, errors)
        operation_id = operation.get("id")
        if isinstance(operation_id, str):
            if operation_id in operation_ids:
                errors.append(f"{location}: duplicate operation id {operation_id}")
            operation_ids.add(operation_id)
        operation_path = operation.get("path")
        if isinstance(operation_path, str) and not (repo_root / operation_path).exists():
            errors.append(f"{location}: path does not exist: {operation_path}")
        if not isinstance(operation.get("mutatesExternalState"), bool):
            errors.append(f"{location}: mutatesExternalState must be boolean")

    return errors


def mode_matches(modes: list[str], mode: str) -> bool:
    return mode == "ALL" or mode in modes


def inventory(alpha_root: Path = ALPHA_ROOT, mode: str = "ALL") -> dict[str, Any]:
    spaces, candidates = discover(alpha_root)
    candidates_by_space: dict[str, list[dict[str, Any]]] = {}
    for _, candidate in candidates:
        candidates_by_space.setdefault(candidate["spaceId"], []).append(candidate)
    result = []
    for _, space in spaces:
        result.append(
            {
                "id": space["id"],
                "title": space["title"],
                "state": space["state"],
                "candidates": sorted(
                    [
                        {"id": candidate["id"], "title": candidate["title"], "stage": candidate["stage"], "researchModes": candidate["researchModes"]}
                        for candidate in candidates_by_space.get(space["id"], [])
                        if mode_matches(candidate["researchModes"], mode)
                    ],
                    key=lambda candidate: candidate["id"],
                ),
            }
        )
    return {"schemaVersion": "marketlab.alpha-workspace-inventory.v2", "mode": mode, "spaces": result}


def ready_work(alpha_root: Path = ALPHA_ROOT, mode: str | None = None, all_priorities: bool = False) -> list[dict[str, Any]]:
    selected_mode = mode or load_policy(alpha_root)["activeResearchMode"]
    spaces, _ = discover(alpha_root)
    result = []
    for _, space in spaces:
        for task in space["readyWork"]:
            if task["status"] == "READY" and mode_matches(task["researchModes"], selected_mode):
                result.append({"spaceId": space["id"], **task})
    if result and not all_priorities:
        highest = min(TASK_PRIORITIES[item["priority"]] for item in result)
        result = [item for item in result if TASK_PRIORITIES[item["priority"]] == highest]
    return sorted(result, key=lambda item: (TASK_PRIORITIES[item["priority"]], item["spaceId"], item["id"]))


def task_inventory(task_id: str, alpha_root: Path = ALPHA_ROOT) -> dict[str, Any]:
    matches = [
        {"spaceId": space["id"], **task}
        for _, space in discover(alpha_root)[0]
        for task in space["readyWork"]
        if task["id"] == task_id
    ]
    if len(matches) != 1:
        raise ValueError(f"unknown task id {task_id!r}")
    return matches[0]


def inventory_rows(kind: str, inventory_root: Path = INVENTORY_ROOT) -> list[dict[str, Any]]:
    value = discover_inventory(inventory_root)
    if kind == "theories":
        return [record for _, record in value["theories"]]
    if kind == "evidence":
        return [record for _, record in value["evidence"]]
    if kind == "data":
        return [record for _, record in value["dataSources"]]
    if kind == "components":
        return value["components"]["components"]
    if kind == "operations":
        return value["operations"]["operations"]
    if kind == "compute":
        return [record for _, record in value["computeRuns"]]
    raise ValueError(f"unknown inventory kind {kind}")


def print_rows(kind: str, rows: list[dict[str, Any]]) -> None:
    if kind == "theories":
        for row in rows:
            print(f"{row['id']:<62} {row['kind']:<12} {row['disposition']}")
    elif kind == "evidence":
        for row in rows:
            print(f"{row['id']:<48} {row['classification']:<32} {row['decision']}")
    elif kind == "data":
        for row in rows:
            print(f"{row['id']:<28} {row['status']:<28} {row['title']}")
    elif kind == "components":
        for row in rows:
            print(f"{row['id']:<22} {row['type']:<22} {row['role']}")
    elif kind == "operations":
        for row in rows:
            mutation = "MUTATES" if row["mutatesExternalState"] else "READ_ONLY"
            print(f"{row['id']:<30} {row['kind']:<14} {mutation:<10} {row['path']}")
    elif kind == "compute":
        for row in rows:
            print(f"{row['id']:<48} {row['campaignId']:<32} {row['workload']}")


def print_inventory(value: dict[str, Any]) -> None:
    for space in value["spaces"]:
        print(f"{space['id']:<22} {space['state']:<18} {space['title']}")
        for candidate in space["candidates"]:
            print(f"  {candidate['id']:<42} {candidate['stage']}")


def outcome_inventory(candidate_id: str | None = None, start: str | None = None, end: str | None = None, inventory_root: Path = INVENTORY_ROOT) -> dict[str, Any]:
    requested_start = parse_timestamp(start) if start else None
    requested_end = parse_timestamp(end) if end else None
    outcomes: list[dict[str, Any]] = []
    unknown_excluded = 0
    for _, record in discover_inventory(inventory_root)["evidence"]:
        for outcome in record["openedOutcomes"]:
            if candidate_id and candidate_id not in outcome["candidateIds"]:
                continue
            row = {"evidenceId": record["id"], **outcome}
            if requested_start or requested_end:
                if outcome["periodStatus"] != "EXACT":
                    unknown_excluded += 1
                    continue
                outcome_start = parse_timestamp(outcome["startInclusive"])
                outcome_end = parse_timestamp(outcome["endExclusive"])
                if requested_start and outcome_end <= requested_start:
                    continue
                if requested_end and outcome_start >= requested_end:
                    continue
            outcomes.append(row)
    return {"schemaVersion": "marketlab.alpha-outcome-exposure-inventory.v1", "filters": {"candidateId": candidate_id, "start": start, "end": end}, "outcomes": sorted(outcomes, key=lambda row: (row.get("startInclusive", ""), row["id"])), "unknownExcluded": unknown_excluded}


def synthesis(alpha_root: Path = ALPHA_ROOT, mode: str | None = None) -> dict[str, Any]:
    selected_mode = mode or load_policy(alpha_root)["activeResearchMode"]
    _, candidates = discover(alpha_root)
    selected = [candidate for _, candidate in candidates if mode_matches(candidate["researchModes"], selected_mode)]
    stages: dict[str, list[dict[str, str]]] = {}
    for candidate in selected:
        stages.setdefault(candidate["stage"], []).append({"id": candidate["id"], "spaceId": candidate["spaceId"], "title": candidate["title"]})
    for values in stages.values():
        values.sort(key=lambda value: value["id"])
    return {"schemaVersion": "marketlab.alpha-knowledge-synthesis.v1", "mode": selected_mode, "policy": load_policy(alpha_root), "candidateCount": len(selected), "outOfScopeCandidateCount": len(candidates) - len(selected), "stages": dict(sorted(stages.items())), "readyWork": ready_work(alpha_root, selected_mode), "outcomeExposure": outcome_inventory(), "computeRuns": inventory_rows("compute")}


def print_outcomes(value: dict[str, Any]) -> None:
    for row in value["outcomes"]:
        period = f"{row['startInclusive']}/{row['endExclusive']}" if row["periodStatus"] == "EXACT" else f"UNKNOWN ({row['unknownReason']})"
        print(f"{row['id']:<48} {row['venue']:<16} {period}")
    if value["unknownExcluded"]:
        print(f"unknown-period exposures excluded by range filter: {value['unknownExcluded']}")


def print_synthesis(value: dict[str, Any]) -> None:
    print(f"mode: {value['mode']}")
    print(f"candidates: {value['candidateCount']} ({value['outOfScopeCandidateCount']} out of scope)")
    for stage, candidates in value["stages"].items():
        print(f"{stage}: {len(candidates)}")
        for candidate in candidates:
            print(f"  {candidate['spaceId']}/{candidate['id']}")
    print(f"ready work: {len(value['readyWork'])}")
    print(f"opened outcome exposures: {len(value['outcomeExposure']['outcomes'])}")
    defaults = value["policy"]["computeDefaults"]
    print(f"compute defaults: processes={defaults['maxConcurrentTrials']} blasThreads={defaults['blasThreads']} maxBlasThreads={defaults['maxBlasThreads']} trialBudget={defaults['defaultCandidateTrialBudget']}")
    discovery = value["policy"]["discoveryPolicy"]
    activation = value["policy"]["prospectiveActivationPolicy"]
    print(f"biased discovery: allowed={discovery['availabilityBiasedHistoricalDiscoveryAllowed']} ceiling={discovery['promotionCeiling']} targetLeakage={discovery['targetLeakageAllowed']}")
    print(f"prospective activation: frozen-and-gated only, minDays={activation['minimumQualityValidDays']} minEvents={activation['minimumActedOnEvents']} deadlineDays={activation['maximumCalendarDays']}")


def verify_artifacts(rows: list[dict[str, Any]], ssh_host: str, repo_root: Path = REPO_ROOT, runner: Any = subprocess.run) -> tuple[list[dict[str, str]], int]:
    results: list[dict[str, str]] = []
    exit_code = 0
    for record in rows:
        for artifact in record["artifacts"]:
            mode = artifact["verification"]
            status = "SKIPPED"
            actual = ""
            if mode == "LOCAL_FILE":
                path = Path(artifact["location"])
                path = path if path.is_absolute() else repo_root / path
                if not path.is_file():
                    status, exit_code = "MISSING", max(exit_code, 1)
                else:
                    actual = hashlib.sha256(path.read_bytes()).hexdigest()
                    status = "PASS" if actual == artifact["sha256"] else "MISMATCH"
                    exit_code = max(exit_code, 0 if status == "PASS" else 1)
            elif mode == "REMOTE_FILE":
                try:
                    completed = runner(["ssh", ssh_host, "sha256sum", "--", artifact["location"]], capture_output=True, text=True, timeout=30, check=False)
                except (OSError, subprocess.SubprocessError):
                    status, exit_code = "TRANSPORT_ERROR", 2
                else:
                    if completed.returncode != 0:
                        status = "MISSING" if completed.returncode == 1 else "TRANSPORT_ERROR"
                        exit_code = max(exit_code, 1 if status == "MISSING" else 2)
                    else:
                        actual = completed.stdout.split()[0]
                        status = "PASS" if actual == artifact["sha256"] else "MISMATCH"
                        exit_code = max(exit_code, 0 if status == "PASS" else 1)
            results.append({"evidenceId": record["id"], "role": artifact["role"], "location": artifact["location"], "status": status, "expectedSha256": artifact["sha256"], "actualSha256": actual})
    return results, exit_code


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    list_parser = subparsers.add_parser("list", help="list spaces and candidates")
    list_parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    list_parser.add_argument("--mode", choices=("historical", "prospective", "all"), default="all")
    ready_parser = subparsers.add_parser("ready", help="list currently unblocked work")
    ready_parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    ready_parser.add_argument("--mode", choices=("historical", "prospective", "all"))
    ready_parser.add_argument("--all-priorities", action="store_true", help="include lower-priority runnable work")
    task_parser = subparsers.add_parser("task", help="show one complete successor task contract")
    task_parser.add_argument("task_id")
    task_parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    for command in ("theories", "evidence", "data", "components", "operations", "compute"):
        inventory_parser = subparsers.add_parser(command, help=f"list {command} inventory")
        inventory_parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    outcomes_parser = subparsers.add_parser("outcomes", help="list opened outcome exposures")
    outcomes_parser.add_argument("--candidate")
    outcomes_parser.add_argument("--start")
    outcomes_parser.add_argument("--end")
    outcomes_parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    synthesis_parser = subparsers.add_parser("synthesis", help="derive a stage-aware research knowledge summary")
    synthesis_parser.add_argument("--mode", choices=("historical", "prospective", "all"))
    synthesis_parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    verify_parser = subparsers.add_parser("verify-artifacts", help="verify immutable artifact hashes")
    verify_parser.add_argument("--ssh-host", required=True)
    verify_parser.add_argument("--evidence")
    verify_parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    subparsers.add_parser("validate", help="validate workspace manifests and references")
    args = parser.parse_args(argv)

    if args.command == "validate":
        errors = validate()
        if errors:
            for error in errors:
                print(error, file=sys.stderr)
            return 1
        spaces, candidates = discover()
        print(f"alpha workspace valid: {len(spaces)} spaces, {len(candidates)} candidates")
        return 0
    if args.command == "list":
        value = inventory(mode=args.mode.upper())
        if args.json:
            print(json.dumps(value, indent=2, sort_keys=True))
        else:
            print_inventory(value)
        return 0
    if args.command == "ready":
        value = ready_work(mode=args.mode.upper() if args.mode else None, all_priorities=args.all_priorities)
        if args.json:
            print(json.dumps(value, indent=2, sort_keys=True))
        else:
            for item in value:
                candidates = ",".join(item["candidateIds"]) or "none"
                print(f"{item['priority']:<3} {item['spaceId']:<22} {item['kind']:<14} {item['id']}\n  {item['summary']}\n  candidates={candidates} resource={item['resourceClass']} outcome={item['outcomeAccess']}")
        return 0
    if args.command == "task":
        try:
            value = task_inventory(args.task_id)
        except ValueError as error:
            parser.error(str(error))
        print(json.dumps(value, indent=2, sort_keys=True))
        return 0
    if args.command in {"theories", "evidence", "data", "components", "operations", "compute"}:
        value = inventory_rows(args.command)
        if args.json:
            print(json.dumps(value, indent=2, sort_keys=True))
        else:
            print_rows(args.command, value)
        return 0
    if args.command == "outcomes":
        try:
            value = outcome_inventory(args.candidate, args.start, args.end)
        except ValueError as error:
            parser.error(str(error))
        if args.json:
            print(json.dumps(value, indent=2, sort_keys=True))
        else:
            print_outcomes(value)
        return 0
    if args.command == "synthesis":
        value = synthesis(mode=args.mode.upper() if args.mode else None)
        if args.json:
            print(json.dumps(value, indent=2, sort_keys=True))
        else:
            print_synthesis(value)
        return 0
    if args.command == "verify-artifacts":
        rows = inventory_rows("evidence")
        if args.evidence:
            rows = [row for row in rows if row["id"] == args.evidence]
            if not rows:
                parser.error(f"unknown evidence id {args.evidence!r}")
        results, exit_code = verify_artifacts(rows, args.ssh_host)
        if args.json:
            print(json.dumps(results, indent=2, sort_keys=True))
        else:
            for result in results:
                print(f"{result['status']:<16} {result['evidenceId']:<48} {result['role']:<20} {result['location']}")
        return exit_code
    raise AssertionError(f"unhandled command {args.command}")


if __name__ == "__main__":
    raise SystemExit(main())
