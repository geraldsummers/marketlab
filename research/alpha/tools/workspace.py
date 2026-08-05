#!/usr/bin/env python3
"""Discover and validate Marketlab's decentralized alpha workspace."""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path
from typing import Any


ALPHA_ROOT = Path(__file__).resolve().parents[1]
REPO_ROOT = Path(__file__).resolve().parents[3]
SPACES_ROOT = ALPHA_ROOT / "spaces"
INVENTORY_ROOT = REPO_ROOT / "research" / "inventory"
ID_PATTERN = re.compile(r"^[a-z0-9]+(?:-[a-z0-9]+)*$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")

SPACE_STATES = {"OPEN", "ACTIVE", "DATA_ACCUMULATING", "DATA_BLOCKED", "CLOSED"}
TASK_STATES = {"READY", "BLOCKED"}
TASK_KINDS = {"research", "data", "model", "evaluation", "infrastructure"}
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
    }


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
    except (OSError, ValueError, json.JSONDecodeError) as error:
        return [f"workspace discovery failed: {error}"]

    if not spaces:
        errors.append("workspace contains no alpha spaces")
        return errors

    space_ids: set[str] = set()
    task_ids: set[str] = set()
    for path, space in spaces:
        location = str(path.relative_to(repo_root)) if path.is_relative_to(repo_root) else str(path)
        if space.get("schemaVersion") != "marketlab.alpha-space.v1":
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
            require_string_list(task, "blockedBy", task_location, errors)
            blockers = task.get("blockedBy")
            if task.get("status") == "READY" and blockers:
                errors.append(f"{task_location}: READY work cannot have blockers")
            if task.get("status") == "BLOCKED" and blockers == []:
                errors.append(f"{task_location}: BLOCKED work must name at least one blocker")

    for path, space in spaces:
        location = str(path.relative_to(repo_root)) if path.is_relative_to(repo_root) else str(path)
        for dependency in space.get("dependencies", []):
            if dependency == space.get("id"):
                errors.append(f"{location}: a space cannot depend on itself")
            elif dependency not in space_ids:
                errors.append(f"{location}: unknown dependency {dependency!r}")

    candidate_ids: set[str] = set()
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
        actual_space = path.parents[2].name
        if candidate.get("spaceId") != actual_space:
            errors.append(f"{location}: spaceId must match parent alpha space")
        if candidate.get("spaceId") not in space_ids:
            errors.append(f"{location}: unknown spaceId {candidate.get('spaceId')!r}")
        if candidate.get("stage") not in CANDIDATE_STAGES:
            errors.append(f"{location}: invalid stage {candidate.get('stage')!r}")
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
    for path, evidence_record in inventory["evidence"]:
        location = str(path.relative_to(repo_root))
        if evidence_record.get("schemaVersion") != "marketlab.evidence-inventory.v1":
            errors.append(f"{location}: unsupported schemaVersion")
        for key in ("id", "classification", "decision", "summarySource"):
            require_string(evidence_record, key, location, errors)
        require_string_list(evidence_record, "candidateIds", location, errors)
        evidence_id = evidence_record.get("id")
        if isinstance(evidence_id, str):
            if evidence_id != path.stem:
                errors.append(f"{location}: id must match its filename")
            if evidence_id in evidence_ids:
                errors.append(f"{location}: duplicate evidence id {evidence_id}")
            evidence_ids.add(evidence_id)
        for candidate_id in evidence_record.get("candidateIds", []):
            if candidate_id not in candidate_ids:
                errors.append(f"{location}: unknown candidateId {candidate_id!r}")
        summary_source = evidence_record.get("summarySource")
        if isinstance(summary_source, str) and not (repo_root / summary_source).is_file():
            errors.append(f"{location}: summarySource does not exist: {summary_source}")
        artifacts = evidence_record.get("artifacts")
        if not isinstance(artifacts, list) or not artifacts:
            errors.append(f"{location}: artifacts must be a non-empty list")
            artifacts = []
        for index, artifact in enumerate(artifacts):
            artifact_location = f"{location}:artifacts[{index}]"
            if not isinstance(artifact, dict):
                errors.append(f"{artifact_location}: artifact must be an object")
                continue
            for key in ("role", "sha256", "location"):
                require_string(artifact, key, artifact_location, errors)
            if isinstance(artifact.get("sha256"), str) and not SHA256_PATTERN.fullmatch(artifact["sha256"]):
                errors.append(f"{artifact_location}: sha256 must be lowercase hexadecimal")

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


def inventory(alpha_root: Path = ALPHA_ROOT) -> dict[str, Any]:
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
                        {"id": candidate["id"], "title": candidate["title"], "stage": candidate["stage"]}
                        for candidate in candidates_by_space.get(space["id"], [])
                    ],
                    key=lambda candidate: candidate["id"],
                ),
            }
        )
    return {"schemaVersion": "marketlab.alpha-workspace-inventory.v1", "spaces": result}


def ready_work(alpha_root: Path = ALPHA_ROOT) -> list[dict[str, str]]:
    spaces, _ = discover(alpha_root)
    result = []
    for _, space in spaces:
        for task in space["readyWork"]:
            if task["status"] == "READY":
                result.append(
                    {
                        "spaceId": space["id"],
                        "id": task["id"],
                        "kind": task["kind"],
                        "summary": task["summary"],
                    }
                )
    return sorted(result, key=lambda item: (item["spaceId"], item["id"]))


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


def print_inventory(value: dict[str, Any]) -> None:
    for space in value["spaces"]:
        print(f"{space['id']:<22} {space['state']:<18} {space['title']}")
        for candidate in space["candidates"]:
            print(f"  {candidate['id']:<42} {candidate['stage']}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    subparsers = parser.add_subparsers(dest="command", required=True)
    list_parser = subparsers.add_parser("list", help="list spaces and candidates")
    list_parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    ready_parser = subparsers.add_parser("ready", help="list currently unblocked work")
    ready_parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
    for command in ("theories", "evidence", "data", "components", "operations"):
        inventory_parser = subparsers.add_parser(command, help=f"list {command} inventory")
        inventory_parser.add_argument("--json", action="store_true", help="emit machine-readable JSON")
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
        value = inventory()
        if args.json:
            print(json.dumps(value, indent=2, sort_keys=True))
        else:
            print_inventory(value)
        return 0
    if args.command == "ready":
        value = ready_work()
        if args.json:
            print(json.dumps(value, indent=2, sort_keys=True))
        else:
            for item in value:
                print(f"{item['spaceId']:<22} {item['kind']:<14} {item['id']}\n  {item['summary']}")
        return 0
    if args.command in {"theories", "evidence", "data", "components", "operations"}:
        value = inventory_rows(args.command)
        if args.json:
            print(json.dumps(value, indent=2, sort_keys=True))
        else:
            print_rows(args.command, value)
        return 0
    raise AssertionError(f"unhandled command {args.command}")


if __name__ == "__main__":
    raise SystemExit(main())
