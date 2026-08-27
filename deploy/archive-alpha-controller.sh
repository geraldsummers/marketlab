#!/usr/bin/env bash
set -euo pipefail

die() {
    printf 'archive-alpha controller: %s\n' "$*" >&2
    exit 1
}

[[ $# -eq 1 ]] || die "usage: $0 ENV_FILE"
environment_file=$1
[[ -f "$environment_file" ]] || die "environment file is missing: $environment_file"
set -a
# shellcheck disable=SC1090
source "$environment_file"
set +a

required=(
    MARKETLAB_ALPHA_IMAGE MARKETLAB_ALPHA_SOURCE_ROOT MARKETLAB_ALPHA_DATA_ROOT
    MARKETLAB_ALPHA_ARTIFACT_ROOT MARKETLAB_ALPHA_LOCK
)
for name in "${required[@]}"; do
    [[ -n ${!name:-} ]] || die "$name is required"
done
[[ "$MARKETLAB_ALPHA_IMAGE" =~ @sha256:[0-9a-f]{64}$ ]] || die "alpha image must be digest pinned"
[[ -f "$MARKETLAB_ALPHA_LOCK" ]] || die "campaign lock is missing"

data_root=$MARKETLAB_ALPHA_DATA_ROOT
artifact_root=$MARKETLAB_ALPHA_ARTIFACT_ROOT
lock=$MARKETLAB_ALPHA_LOCK
status_path=$artifact_root/status.json
confirmation_root=$artifact_root/confirmation-ledger
search_workers=${MARKETLAB_SEARCH_WORKERS:-1}
search_memory_reserve_gib=${MARKETLAB_SEARCH_MEMORY_RESERVE_GIB:-12}
search_worker_gib=${MARKETLAB_SEARCH_WORKER_GIB:-8}
container_memory_gib=${MARKETLAB_CONTAINER_MEMORY_GIB:-40}
trial_memory_gib=${MARKETLAB_TRIAL_MEMORY_GIB:-36}
trial_retry_memory_gib=${MARKETLAB_TRIAL_RETRY_MEMORY_GIB:-40}
container_cpu_limit=${MARKETLAB_CONTAINER_CPU_LIMIT:-8}
container_name=${MARKETLAB_ALPHA_CONTAINER_NAME:-marketlab-archive-alpha-v2-worker}
for numeric_value in "$search_workers" "$search_memory_reserve_gib" "$search_worker_gib" "$container_memory_gib" "$trial_memory_gib" "$trial_retry_memory_gib" "$container_cpu_limit"; do
    [[ "$numeric_value" =~ ^[1-9][0-9]*$ ]] || die "adaptive search limits must be positive integers"
done
[[ "$search_workers" -eq 1 ]] || die "production disposable workers require MARKETLAB_SEARCH_WORKERS=1"
install -d -m 0750 "$data_root" "$artifact_root" "$confirmation_root"
exec 9>"$artifact_root/controller.lock"
flock -n 9 || die "another archive-alpha controller owns the campaign"

write_status() {
    local phase=$1
    local detail=$2
    local temporary="$status_path.tmp.$$"
    PHASE="$phase" DETAIL="$detail" STATUS_PATH="$status_path" \
        python3 - <<'PY' >"$temporary"
import json, os
from datetime import datetime, timezone
prior = {}
try:
    with open(os.environ["STATUS_PATH"], encoding="utf-8") as handle:
        prior = json.load(handle)
except FileNotFoundError:
    pass
value = {
    "schemaVersion": "marketlab.archive-alpha-operational-status.v1",
    "campaignId": "archive-directional-gpu-v2",
    "phase": os.environ["PHASE"],
    "detail": os.environ["DETAIL"],
    "updatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
    "startedAt": prior.get("startedAt", datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")),
    "confirmationOpened": False,
}
print(json.dumps(value, sort_keys=True, separators=(",", ":")))
PY
    chmod 0640 "$temporary"
    mv -f "$temporary" "$status_path"
}

failed() {
    local line=$1
    write_status "OPERATIONALLY_BLOCKED" "controller failed at line $line; inspect the journal and resume only the exact frozen service"
}
trap 'failed "$LINENO"' ERR

active_container=
cleanup_container() {
    local exit_code=$?
    trap - EXIT INT TERM
    if [[ -n "$active_container" ]]; then
        podman stop --time 20 "$active_container" >/dev/null 2>&1 || podman kill "$active_container" >/dev/null 2>&1 || true
        podman rm -f "$active_container" >/dev/null 2>&1 || true
    fi
    exit "$exit_code"
}
trap cleanup_container EXIT INT TERM

if container_state=$(podman inspect --format '{{.State.Status}}' "$container_name" 2>/dev/null); then
    [[ "$container_state" != "paused" ]] || die "refusing paused legacy container $container_name; stop and remove it before resume"
    die "refusing existing container $container_name in state $container_state"
fi

container_base=(
    podman run --rm --name "$container_name" --pull=never
    --security-opt=no-new-privileges --cap-drop=all
    --pids-limit=1024 --cpus="$container_cpu_limit"
    --volume "$data_root:/data:rw"
    --volume "$artifact_root:/artifacts:rw"
    --volume "$lock:/campaign.lock.json:ro"
)

run_container() {
    local memory_gib=$1
    shift
    "${container_base[@]}" --memory="${memory_gib}g" --memory-swap="${memory_gib}g" "$@" &
    local podman_pid=$!
    active_container=$container_name
    local result=0
    wait "$podman_pid" || result=$?
    active_container=
    return "$result"
}

run_networked() {
    run_container "$container_memory_gib" --network=slirp4netns "$MARKETLAB_ALPHA_IMAGE" "$@"
}

run_offline() {
    run_container "$container_memory_gib" --network=none "$MARKETLAB_ALPHA_IMAGE" "$@"
}

run_gpu_memory() {
    local memory_gib=$1
    shift
    local gpu_uuid driver_version image_digest
    gpu_uuid=$(nvidia-smi --query-gpu=uuid --format=csv,noheader | head -1)
    driver_version=$(nvidia-smi --query-gpu=driver_version --format=csv,noheader | head -1)
    image_digest=${MARKETLAB_ALPHA_IMAGE##*@}
    run_container "$memory_gib" --network=none --device=nvidia.com/gpu=0 \
        --env "CUBLAS_WORKSPACE_CONFIG=:4096:8" \
        --env "OMP_NUM_THREADS=1" \
        --env "MKL_NUM_THREADS=1" \
        --env "OPENBLAS_NUM_THREADS=1" \
        --env "MARKETLAB_SEARCH_WORKERS=$search_workers" \
        --env "MARKETLAB_SEARCH_MEMORY_RESERVE_GIB=$search_memory_reserve_gib" \
        --env "MARKETLAB_SEARCH_WORKER_GIB=$search_worker_gib" \
        --env "MARKETLAB_TRIAL_MEMORY_GIB=$memory_gib" \
        --env "MARKETLAB_GPU_UUID=$gpu_uuid" \
        --env "MARKETLAB_NVIDIA_DRIVER_VERSION=$driver_version" \
        --env "MARKETLAB_WORKER_IMAGE_DIGEST=$image_digest" \
        "$MARKETLAB_ALPHA_IMAGE" "$@"
}

run_gpu() {
    run_gpu_memory "$container_memory_gib" "$@"
}

active_trial_id() {
    local search_root=$1
    python3 - "$search_root/operations/active.json" <<'PY'
import json, sys
try:
    print(json.load(open(sys.argv[1], encoding="utf-8")).get("trialId", "unknown-trial"))
except (OSError, ValueError):
    print("unknown-trial")
PY
}

record_oom_attempt() {
    local search_root=$1 trial_id=$2 memory_gib=$3
    SEARCH_ROOT="$search_root" TRIAL_ID="$trial_id" MEMORY_GIB="$memory_gib" python3 - <<'PY'
import json, os
from datetime import datetime, timezone
from pathlib import Path
root = Path(os.environ["SEARCH_ROOT"]) / "operations" / "attempts" / os.environ["TRIAL_ID"]
root.mkdir(parents=True, exist_ok=True)
sequence = len(list(root.glob("attempt-*.json"))) + 1
path = root / f"attempt-{sequence:03d}.json"
value = {
    "schemaVersion": "marketlab.alpha-operational-attempt.v1",
    "trialId": os.environ["TRIAL_ID"],
    "epistemicStage": "EXPLORATORY",
    "memoryProfileGiB": int(os.environ["MEMORY_GIB"]),
    "exitCode": 137,
    "classification": "CGROUP_OOM",
    "completedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
}
with path.open("x", encoding="utf-8") as handle:
    json.dump(value, handle, sort_keys=True, separators=(",", ":"))
PY
}

run_search_step() {
    local basket=$1 search_root="$artifact_root/search-$basket" result=0 trial_id
    run_gpu_memory "$trial_memory_gib" search-step \
        --panel "/data/development-$basket.parquet" --lock /campaign.lock.json \
        --confirmation-ledger-root /artifacts/confirmation-ledger \
        --output "/artifacts/search-$basket" || result=$?
    [[ "$result" -eq 0 ]] && return 0
    [[ "$result" -eq 137 ]] || return "$result"
    trial_id=$(active_trial_id "$search_root")
    record_oom_attempt "$search_root" "$trial_id" "$trial_memory_gib"
    write_status "RUNNING_DEVELOPMENT" "trial $trial_id exceeded ${trial_memory_gib} GiB; retrying once at ${trial_retry_memory_gib} GiB"
    result=0
    run_gpu_memory "$trial_retry_memory_gib" search-step \
        --panel "/data/development-$basket.parquet" --lock /campaign.lock.json \
        --confirmation-ledger-root /artifacts/confirmation-ledger \
        --output "/artifacts/search-$basket" || result=$?
    [[ "$result" -eq 0 ]] && return 0
    [[ "$result" -eq 137 ]] || return "$result"
    trial_id=$(active_trial_id "$search_root")
    record_oom_attempt "$search_root" "$trial_id" "$trial_retry_memory_gib"
    write_status "OPERATIONALLY_BLOCKED" "trial $trial_id exceeded both ${trial_memory_gib} and ${trial_retry_memory_gib} GiB disposable-worker profiles; confirmation remains sealed"
    trap - ERR
    exit 75
}

write_status "DISCOVERING_ARCHIVE" "discovering historical USD-M USDT archive prefixes"
if [[ ! -f "$data_root/discovery/manifest.json" ]]; then
    run_networked discover-binance --output-root /data/discovery
fi
write_status "ACQUIRING_DAILY" "first immutable checkpoint is healthy; acquiring resumable daily universe archives"

if [[ ! -f "$data_root/daily-archives/manifest.json" ]]; then
    run_networked download-binance --data-type klines --interval 1d \
        --symbols /data/discovery/symbols.txt --start-month 2020-01 --end-month 2026-07 \
        --output-root /data/daily-archives --workers 8
fi
write_status "PREPARING_UNIVERSE" "normalizing daily bars and reconstructing point-in-time baskets"
if [[ ! -f "$data_root/daily.parquet" ]]; then
    run_offline normalize-binance --manifest /data/daily-archives/manifest.json --output /data/daily.parquet
fi
if [[ ! -f "$data_root/observations.jsonl" ]]; then
    run_offline universe-observations --bars /data/daily.parquet --output /data/observations.jsonl
fi
if [[ ! -f "$data_root/universes.json" ]]; then
    run_offline universe --observations /data/observations.jsonl \
        --first-as-of 2020-04-22T00:00:00Z --last-as-of 2026-07-29T00:00:00Z \
        --output /data/universes.json
fi
if [[ ! -f "$data_root/research-symbols.txt" ]]; then
    run_offline universe-symbols --universe /data/universes.json --output /data/research-symbols.txt
fi

write_status "ACQUIRING_FIVE_MINUTE" "acquiring resumable native five-minute archives for the selected historical union"
if [[ ! -f "$data_root/five-minute-archives/manifest.json" ]]; then
    run_networked download-binance --data-type klines --interval 5m \
        --symbols /data/research-symbols.txt --start-month 2020-01 --end-month 2026-07 \
        --output-root /data/five-minute-archives --workers 8
fi
write_status "PREPARING_PANELS" "normalizing five-minute bars and materializing separate development and sealed panels"
if [[ ! -f "$data_root/five-minute.parquet" ]]; then
    run_offline normalize-binance --manifest /data/five-minute-archives/manifest.json --output /data/five-minute.parquet
fi
for basket in 4 6 10; do
    if [[ ! -f "$data_root/development-$basket.parquet" ]]; then
        run_offline materialize --bars /data/five-minute.parquet --universe /data/universes.json \
            --basket-size "$basket" --period-start 2020-01-01T00:00:00Z \
            --period-end 2025-06-01T00:00:00Z --output "/data/development-$basket.parquet"
    fi
    if [[ ! -f "$data_root/confirmation-$basket.parquet" ]]; then
        run_offline materialize --bars /data/five-minute.parquet --universe /data/universes.json \
            --basket-size "$basket" --period-start 2025-06-01T00:00:00Z \
            --period-end 2026-08-01T00:00:00Z --output "/data/confirmation-$basket.parquet"
    fi
done

write_status "RUNNING_DEVELOPMENT" "running automatic checkpointed development rungs; confirmation remains sealed"
if [[ ! -f "$artifact_root/gpu.json" ]]; then
    run_gpu probe-gpu --output /artifacts/gpu.json
fi
for basket in 4 6 10; do
    while [[ ! -f "$artifact_root/search-$basket/search-result.json" ]]; do
        run_search_step "$basket"
    done
done
if [[ ! -f "$artifact_root/merged-search.json" ]]; then
    run_offline merge-searches \
        --search /artifacts/search-4/search-result.json \
        --search /artifacts/search-6/search-result.json \
        --search /artifacts/search-10/search-result.json \
        --lock /campaign.lock.json --output /artifacts/merged-search.json
fi
write_status "AWAITING_CONFIRMATION_REVIEW" "development family merged; freeze and confirmation are prohibited until explicit review"
trap - ERR
trap - EXIT INT TERM
