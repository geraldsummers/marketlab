#!/usr/bin/env bash
set -euo pipefail

env_file=${1:-/mnt/stack/marketlab/config/archive-alpha-v2.env}
artifact_root=${2:-/mnt/media/marketlab/artifacts/archive-alpha-v2}
service=${MARKETLAB_ARCHIVE_ALPHA_SERVICE:-marketlab-archive-alpha-v2.service}
active_marker="$artifact_root/search-6/operations/active.json"
drained_trial=$(cat "$artifact_root/.evidence-audit-drained-trial" 2>/dev/null || true)
[[ "$drained_trial" == *-groupn ]] && drained_trial=${drained_trial%n}
interrupted_trial=""
active_outcome="NO_ACTIVE_TRIAL"

if [[ -f "$active_marker" ]] && systemctl --user is-active --quiet "$service"; then
  drained_trial=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["trialId"])' "$active_marker")
  deadline=$((SECONDS + ${MARKETLAB_SUSPEND_TIMEOUT_SECONDS:-86400}))
  while [[ -f "$active_marker" ]]; do
    current=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("trialId", ""))' "$active_marker" 2>/dev/null || true)
    [[ "$current" == "$drained_trial" ]] || break
    systemctl --user is-active --quiet "$service" || { active_outcome="INTERRUPTED"; break; }
    (( SECONDS < deadline )) || { echo "timed out waiting for durable trial boundary" >&2; exit 1; }
    sleep 1
  done
  [[ "$active_outcome" == "INTERRUPTED" ]] || active_outcome="DURABLE_BOUNDARY_REACHED"
fi

if [[ -f "$active_marker" ]] && ! systemctl --user is-active --quiet "$service"; then
  interrupted_trial=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1])).get("trialId", ""))' "$active_marker" 2>/dev/null || true)
  active_outcome="SERVICE_STOPPED_AFTER_BOUNDARY"
fi

systemctl --user stop "$service"
if systemctl --user is-active --quiet "$service"; then
  echo "campaign service remains active after stop" >&2
  exit 1
fi
if podman container exists marketlab-archive-alpha-v2-worker 2>/dev/null; then
  echo "campaign worker remains present after service stop" >&2
  exit 1
fi

mkdir -p "$artifact_root/suspensions"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
record="$artifact_root/suspensions/evidence-audit-$stamp.json"
status="$artifact_root/status.json"
export artifact_root env_file service drained_trial interrupted_trial active_outcome record status
python3 - <<'PY'
import json, os, pathlib, tempfile, datetime

root = pathlib.Path(os.environ["artifact_root"])
confirmation = root / "confirmation-ledger"
searches = []
for basket in (4, 6, 10):
    search = root / f"search-{basket}"
    trials = list(search.glob("checkpoints/trials/*/trial.json")) if search.exists() else []
    completed = failed = 0
    for path in trials:
        state = json.load(open(path)).get("status")
        completed += state == "COMPLETED"
        failed += state == "FAILED"
    searches.append({
        "basketSize": basket,
        "durableTrialCount": len(trials),
        "completedTrialCount": completed,
        "failedTrialCount": failed,
        "searchResultPresent": (search / "search-result.json").is_file(),
    })
now = datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z")
record = {
    "schemaVersion": "marketlab.archive-alpha-suspension.v1",
    "campaignId": "archive-directional-gpu-v2",
    "state": "SUSPENDED_FOR_EVIDENCE_AUDIT",
    "suspendedAt": now,
    "service": os.environ["service"],
    "serviceActive": False,
    "workerPresent": False,
    "drainedTrialId": os.environ["drained_trial"] or None,
    "interruptedTrialId": os.environ["interrupted_trial"] or None,
    "activeTrialOutcome": os.environ["active_outcome"],
    "sourceEnvironmentFile": os.environ["env_file"],
    "confirmationOpened": any(path.is_file() for path in confirmation.rglob("*")) if confirmation.exists() else False,
    "searches": searches,
}
payload = (json.dumps(record, sort_keys=True, separators=(",", ":"), allow_nan=False) + "\n").encode()
target = pathlib.Path(os.environ["record"])
descriptor = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
with os.fdopen(descriptor, "wb") as handle:
    handle.write(payload); handle.flush(); os.fsync(handle.fileno())
status = {
    "schemaVersion": "marketlab.archive-alpha-operational-status.v1",
    "campaignId": record["campaignId"],
    "phase": record["state"],
    "confirmationOpened": record["confirmationOpened"],
    "detail": "exhaustive development suspended at a durable boundary; immutable evidence audit authorized",
    "updatedAt": now,
    "suspensionRecord": str(target),
}
status_target = pathlib.Path(os.environ["status"])
with tempfile.NamedTemporaryFile("w", dir=status_target.parent, delete=False) as handle:
    json.dump(status, handle, sort_keys=True, separators=(",", ":")); handle.write("\n")
    temporary = pathlib.Path(handle.name)
temporary.replace(status_target)
print(json.dumps({"suspensionRecord": str(target), "status": status}, sort_keys=True))
PY
