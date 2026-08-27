#!/usr/bin/env bash
set -euo pipefail

artifact_root=${MARKETLAB_ALPHA_ARTIFACT_ROOT:-/mnt/media/marketlab/artifacts/archive-alpha-v2}
status_path="$artifact_root/status.json"
if [[ -f "$status_path" ]]; then
    cat "$status_path"
else
    printf '%s\n' '{"schemaVersion":"marketlab.archive-alpha-operational-status.v1","campaignId":"archive-directional-gpu-v2","phase":"NOT_DISPATCHED","confirmationOpened":false}'
fi
python3 - "$artifact_root" <<'PY'
import json, sys
from pathlib import Path
artifact = Path(sys.argv[1])
data = Path(str(artifact).replace("/artifacts/", "/raw/"))
if data.name == "archive-alpha-v2":
    discovery = data / "discovery/symbols.txt"
    research = data / "research-symbols.txt"
    daily_done = len(list((data / "daily-archives/requests").glob("*/*.json")))
    five_done = len(list((data / "five-minute-archives/requests").glob("*/*.json")))
    symbols = sum(1 for _ in discovery.open()) if discovery.is_file() else 0
    research_symbols = sum(1 for _ in research.open()) if research.is_file() else 0
    print(json.dumps({
        "schemaVersion": "marketlab.archive-alpha-progress.v1",
        "dailyRequestsCompleted": daily_done,
        "dailyRequestCeiling": symbols * 79,
        "fiveMinuteRequestsCompleted": five_done,
        "fiveMinuteRequestCeiling": research_symbols * 79,
    }, sort_keys=True, separators=(",", ":")))
PY
systemctl --user show marketlab-archive-alpha-v2.service \
    --property=ActiveState,SubState,Result,NRestarts --no-pager 2>/dev/null || true
for search_status in "$artifact_root"/search-*/status.json; do
    [[ -f "$search_status" ]] || continue
    cat "$search_status"
done
python3 - "$artifact_root" <<'PY'
import json, sys
from datetime import datetime, timezone
from pathlib import Path

now = datetime.now(timezone.utc)
for search in sorted(Path(sys.argv[1]).glob("search-*")):
    records = []
    for path in (search / "checkpoints/trials").glob("*/trial.json"):
        try:
            records.append(json.loads(path.read_text(encoding="utf-8")))
        except (OSError, ValueError):
            continue
    if not records:
        continue
    latest = max(records, key=lambda value: value.get("completedAt", ""))
    latest_selection = latest.get("selection", {})
    latest_execution = latest.get("execution", {})
    completed_at = latest.get("completedAt")
    age = None
    if completed_at:
        age = max(0.0, (now - datetime.fromisoformat(completed_at.replace("Z", "+00:00"))).total_seconds())
    process_status = {}
    try:
        process_status = json.loads((search / "status.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        pass
    active = {}
    try:
        active = json.loads((search / "operations/active.json").read_text(encoding="utf-8"))
    except (OSError, ValueError):
        pass
    attempts = list((search / "operations/attempts").glob("*/*.json"))
    plans = list((search / "checkpoints/plans").glob("*.json"))
    print(json.dumps({
        "schemaVersion": "marketlab.alpha-checkpoint-health.v1",
        "search": search.name,
        "durableTrialCount": len(records),
        "successfulTrialCount": sum(value.get("status") == "COMPLETED" for value in records),
        "failedTrialCount": sum(value.get("status") == "FAILED" for value in records),
        "inFlightTrialCount": process_status.get("inFlightTrialCount"),
        "inFlightTrialIds": process_status.get("inFlightTrialIds", []),
        "activeCandidateId": process_status.get("activeCandidateId"),
        "activeRung": process_status.get("rung"),
        "activeModelId": process_status.get("activeModelId"),
        "observedWorkerCount": process_status.get("lastSelectedWorkerCount"),
        "activeTrial": active or None,
        "immutablePlanCount": len(plans),
        "operationalAttemptCount": len(attempts),
        "oomAttemptCount": len(attempts),
        "latestCheckpointAt": completed_at,
        "latestCheckpointAgeSeconds": age,
        "latestCheckpointTrialId": latest.get("trialId"),
        "latestCheckpointCandidateId": latest.get("candidateId"),
        "latestCheckpointRung": latest.get("rung") or latest.get("searchStage"),
        "latestCheckpointModelId": latest.get("modelId") or latest_selection.get("modelFamilies"),
        "latestCheckpointWorkerCount": latest_execution.get("parallelWorkers"),
        "epistemicStage": "EXPLORATORY",
        "confirmationOpened": False,
    }, sort_keys=True, separators=(",", ":")))
PY
