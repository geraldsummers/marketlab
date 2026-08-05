#!/usr/bin/env bash
set -euo pipefail

SCRIPT_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
readonly SCRIPT_ROOT
readonly RELEASE_ROOT=/mnt/stack/marketlab/releases
readonly CONFIG_ROOT="${XDG_CONFIG_HOME:-$HOME/.config}"
readonly STATE_ROOT="${XDG_STATE_HOME:-$HOME/.local/state}/marketlab"
readonly QUADLET_ROOT="$CONFIG_ROOT/containers/systemd"
readonly USER_BIN_ROOT="$HOME/.local/bin"
readonly STORAGE_CONFIG="$CONFIG_ROOT/marketlab/storage.conf"

die() {
    printf 'verify: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "required command is unavailable: $1"
}

release_value() {
    local key=$1
    awk -v wanted="$key" '
        index($0, wanted "=") == 1 {
            print substr($0, length(wanted) + 2)
            found = 1
        }
        END { if (!found) exit 1 }
    ' "$release_manifest"
}

environment_value() {
    local file=$1
    local key=$2
    awk -v wanted="$key" '
        index($0, wanted "=") == 1 {
            print substr($0, length(wanted) + 2)
            found = 1
        }
        END { if (!found) exit 1 }
    ' "$file"
}

expected_image_id() {
    podman image inspect --format '{{.Id}}' "$1"
}

assert_container_image() {
    local container=$1
    local expected=$2
    local actual_id
    local expected_id
    actual_id=$(podman inspect --format '{{.Image}}' "$container")
    expected_id=$(expected_image_id "$expected")
    [[ "$actual_id" == "$expected_id" ]] ||
        die "$container is not running the release-pinned image"
}

assert_mount() {
    local container=$1
    local source=$2
    local destination=$3
    local writable=$4
    podman inspect "$container" |
        jq -e \
            --arg source "$source" \
            --arg destination "$destination" \
            --argjson writable "$writable" \
            '.[0].Mounts | any(.Source == $source and .Destination == $destination and .RW == $writable)' \
            >/dev/null ||
        die "$container mount policy is incorrect for $destination"
}

for command_name in awk curl findmnt grep jq podman sleep ss stat systemctl; do
    require_command "$command_name"
done

[[ $(id -u) -ne 0 ]] || die "verification must run as the rootless Podman user"
[[ -f "$STORAGE_CONFIG" && ! -L "$STORAGE_CONFIG" ]] ||
    die "Marketlab Podman storage configuration is missing"
export CONTAINERS_STORAGE_CONF="$STORAGE_CONFIG"
[[ $(podman info --format '{{.Host.Security.Rootless}}') == true ]] ||
    die "Podman is not running rootless"

release_id=${1:-}
if [[ -z "$release_id" ]]; then
    [[ -f "$STATE_ROOT/active-release" ]] || die "there is no active release marker"
    release_id=$(<"$STATE_ROOT/active-release")
fi
[[ "$release_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || die "invalid release id"
release_manifest="$RELEASE_ROOT/$release_id/images.env"
[[ -f "$release_manifest" ]] || die "release manifest is missing"
[[ $(release_value RELEASE_ID) == "$release_id" ]] || die "release manifest id mismatch"

postgres_image=$(release_value POSTGRES_IMAGE)
service_image=$(release_value SERVICE_IMAGE)
worker_image=$(release_value WORKER_IMAGE)
alpha_model_image=$(release_value ALPHA_MODEL_IMAGE)
coordinator_image=$(release_value COORDINATOR_IMAGE)
collector_image=$(release_value COLLECTOR_IMAGE 2>/dev/null || true)
social_collector_image=$(release_value SOCIAL_COLLECTOR_IMAGE 2>/dev/null || true)
sentiment_worker_image=$(release_value SENTIMENT_WORKER_IMAGE 2>/dev/null || true)
research_image=$(release_value RESEARCH_IMAGE)
runner_image=$(release_value RUNNER_IMAGE)
source_sha256=$(release_value SOURCE_SHA256)
[[ "$source_sha256" =~ ^[0-9a-f]{64}$ ]] || die "release source digest is invalid"
collector_enabled=false
if [[ -n "$collector_image" ]]; then
    [[ "$collector_image" =~ ^[a-z0-9.-]+/[a-z0-9._/-]+@sha256:[0-9a-f]{64}$ ]] ||
        die "collector image is not an immutable sha256 reference"
    collector_enabled=true
fi
social_enabled=false
if [[ -n "$social_collector_image" || -n "$sentiment_worker_image" ]]; then
    [[ -n "$social_collector_image" && -n "$sentiment_worker_image" ]] ||
        die "social image manifest is incomplete"
    social_enabled=true
fi

environment_files=(api.env collector.env coordinator.env migrator.env postgres.env runner.env)
if [[ "$social_enabled" == true ]]; then
    environment_files+=(social-collector.env social-market.env sentiment-worker.env)
fi
for environment_file in "${environment_files[@]}"; do
    path="$CONFIG_ROOT/marketlab/$environment_file"
    [[ -f "$path" && ! -L "$path" && -O "$path" ]] ||
        die "environment file is not an owned regular file: $path"
    [[ $(stat -c '%a' "$path") == 600 ]] || die "environment file is not mode 0600: $path"
    if grep -Eq 'replace-with|@[A-Z0-9_]+@' "$path"; then
        die "environment file contains a placeholder: $path"
    fi
done

owner_password=$(environment_value "$CONFIG_ROOT/marketlab/postgres.env" POSTGRES_PASSWORD)
migrator_password=$(environment_value "$CONFIG_ROOT/marketlab/migrator.env" MARKETLAB_DATABASE_PASSWORD)
api_password=$(environment_value "$CONFIG_ROOT/marketlab/api.env" MARKETLAB_DATABASE_PASSWORD)
coordinator_password=$(environment_value "$CONFIG_ROOT/marketlab/coordinator.env" MARKETLAB_DATABASE_PASSWORD)
[[ "$owner_password" == "$migrator_password" ]] ||
    die "migrator does not use the PostgreSQL owner credential"
[[ "$owner_password" != "$api_password" && "$owner_password" != "$coordinator_password" ]] ||
    die "owner database credential is shared with an application"
[[ "$api_password" != "$coordinator_password" ]] ||
    die "API and coordinator database credentials are shared"
[[ $(environment_value "$CONFIG_ROOT/marketlab/api.env" MARKETLAB_DATABASE_USER) == marketlab_api ]] ||
    die "API environment does not use marketlab_api"
[[ $(environment_value "$CONFIG_ROOT/marketlab/coordinator.env" MARKETLAB_DATABASE_USER) == marketlab_coordinator ]] ||
    die "coordinator environment does not use marketlab_coordinator"
[[ $(environment_value "$CONFIG_ROOT/marketlab/api.env" MARKETLAB_DATABASE_MIGRATE) == false ]] ||
    die "API is allowed to run owner migrations"
[[ $(environment_value "$CONFIG_ROOT/marketlab/coordinator.env" MARKETLAB_DATABASE_MIGRATE) == false ]] ||
    die "coordinator is allowed to run owner migrations"
[[ $(environment_value "$CONFIG_ROOT/marketlab/collector.env" MARKETLAB_COLLECTOR_RAW_ROOT) == /mnt/media/marketlab/raw/hyperliquid-stream ]] ||
    die "collector raw-data root is outside its narrow cold-storage mount"
[[ $(environment_value "$CONFIG_ROOT/marketlab/collector.env" MARKETLAB_COLLECTOR_COIN) == BTC ]] ||
    die "collector is not the single approved BTC instance"
[[ $(environment_value "$CONFIG_ROOT/marketlab/collector.env" MARKETLAB_COLLECTOR_MAX_SEGMENT_BYTES) == 134217728 ]] ||
    die "collector segment-size boundary differs from the reviewed deployment"
[[ $(environment_value "$CONFIG_ROOT/marketlab/collector.env" MARKETLAB_COLLECTOR_MAX_SEGMENT_MILLIS) == 900000 ]] ||
    die "collector segment-time boundary differs from the reviewed deployment"
[[ $(environment_value "$CONFIG_ROOT/marketlab/collector.env" MARKETLAB_COLLECTOR_QUEUE_CAPACITY) == 64 ]] ||
    die "collector queue boundary differs from the reviewed deployment"
[[ $(environment_value "$CONFIG_ROOT/marketlab/collector.env" MARKETLAB_COLLECTOR_MAX_FRAME_BYTES) == 2097152 ]] ||
    die "collector frame-size boundary differs from the reviewed deployment"
if grep -Eq '(^|_)(DATABASE|PASSWORD|TOKEN|SECRET)(_|=)' \
    "$CONFIG_ROOT/marketlab/collector.env"; then
    die "collector environment contains a database credential or secret"
fi

[[ $(findmnt -n -o TARGET -T /mnt/stack) == /mnt/stack ]] ||
    die "hot storage mount is absent"
[[ $(findmnt -n -o TARGET -T /mnt/media) == /mnt/media ]] ||
    die "cold storage mount is absent"
[[ $(findmnt -n -o SOURCE -T /mnt/stack) != "$(findmnt -n -o SOURCE -T /mnt/media)" ]] ||
    die "hot and cold storage resolve to the same filesystem"

for service in \
    marketlab-postgres.service \
    marketlab-runner.service \
    marketlab-api.service \
    marketlab-coordinator.service; do
    systemctl --user is-active --quiet "$service" || die "service is not active: $service"
done
if [[ "$social_enabled" == true ]]; then
    for service in \
        marketlab-social-collector.service \
        marketlab-social-market.service \
        marketlab-sentiment-worker.service; do
        systemctl --user is-active --quiet "$service" || die "service is not active: $service"
    done
fi
if [[ "$collector_enabled" == true ]]; then
    collector_running=false
    for ((attempt = 1; attempt <= 30; attempt++)); do
        if systemctl --user is-active --quiet marketlab-collector.service &&
            [[ $(podman inspect --format '{{.State.Running}}' marketlab-collector 2>/dev/null) == true ]]; then
            collector_container_id=$(podman inspect --format '{{.Id}}' marketlab-collector)
            collector_started_at_millis=$(
                podman inspect --format '{{.State.StartedAt.UnixMilli}}' marketlab-collector
            )
            [[ "$collector_started_at_millis" =~ ^[0-9]{13}$ ]] ||
                die "collector start time is unavailable in epoch milliseconds"
            collector_restart_count=$(systemctl --user show \
                marketlab-collector.service --property=NRestarts --value)
            collector_running=true
            break
        fi
        sleep 1
    done
    [[ "$collector_running" == true ]] ||
        die "collector did not start within 30 seconds"

    collector_ready=false
    collector_ready_marker=/mnt/media/marketlab/raw/hyperliquid-stream/.collector-ready.json
    for ((attempt = 1; attempt <= 30; attempt++)); do
        current_collector_id=$(podman inspect --format '{{.Id}}' marketlab-collector 2>/dev/null || true)
        current_restart_count=$(systemctl --user show \
            marketlab-collector.service --property=NRestarts --value)
        [[ "$current_collector_id" == "$collector_container_id" ]] ||
            die "collector restarted while establishing mainnet readiness"
        [[ "$current_restart_count" == "$collector_restart_count" ]] ||
            die "collector service restarted while establishing mainnet readiness"
        if [[ -f "$collector_ready_marker" &&
            ! -L "$collector_ready_marker" &&
            -O "$collector_ready_marker" ]] &&
            jq -e \
                --arg source_revision "$source_sha256" \
                --argjson started_at "$collector_started_at_millis" \
                '
                    .schemaVersion == "marketlab.hyperliquid.websocket-ready.v1"
                    and .source == "hyperliquid-mainnet"
                    and .production == true
                    and .sourceRevision == $source_revision
                    and .coin == "BTC"
                    and ((.subscriptions | sort) == ["bbo", "l2Book", "trades"])
                    and (.startedAtEpochMillis >= $started_at)
                    and (.readyAtEpochMillis >= .startedAtEpochMillis)
                ' "$collector_ready_marker" >/dev/null; then
            collector_ready=true
            break
        fi
        sleep 1
    done
    [[ "$collector_ready" == true ]] ||
        die "collector did not persist acknowledgements for all three mainnet streams within 30 seconds"
else
    if systemctl --user is-active --quiet marketlab-collector.service; then
        die "legacy release unexpectedly left the collector active"
    fi
    if podman container exists marketlab-collector &&
        [[ $(podman inspect --format '{{.State.Running}}' marketlab-collector) == true ]]; then
        die "legacy release left a collector container running"
    fi
fi
if [[ "$social_enabled" == true ]]; then
    sentiment_ready=false
    sentiment_ready_marker=/mnt/stack/marketlab/social-features/.sentiment-worker-ready
    for ((attempt = 1; attempt <= 30; attempt++)); do
        if [[ $(podman inspect --format '{{.State.Running}}' marketlab-social-collector 2>/dev/null) == true ]] &&
            [[ $(podman inspect --format '{{.State.Running}}' marketlab-social-market 2>/dev/null) == true ]] &&
            [[ $(podman inspect --format '{{.State.Running}}' marketlab-sentiment-worker 2>/dev/null) == true ]] &&
            [[ -f "$sentiment_ready_marker" && ! -L "$sentiment_ready_marker" ]] &&
            jq -e \
                --arg source_revision "$source_sha256" \
                '
                    .schemaVersion == "marketlab.sentiment-worker-ready.v1"
                    and .sourceRevision == $source_revision
                    and (.readyAtEpochMillis > 0)
                ' "$sentiment_ready_marker" >/dev/null; then
            sentiment_ready=true
            break
        fi
        sleep 1
    done
    [[ "$sentiment_ready" == true ]] ||
        die "social services did not establish fixed-model readiness within 30 seconds"
fi

[[ $(podman inspect --format '{{.State.Health.Status}}' marketlab-postgres) == healthy ]] ||
    die "PostgreSQL container is not healthy"
[[ $(podman inspect --format '{{.State.Health.Status}}' marketlab-api) == healthy ]] ||
    die "API container is not healthy"
[[ $(podman inspect --format '{{.State.Running}}' marketlab-coordinator) == true ]] ||
    die "coordinator container is not running"
if [[ "$collector_enabled" == true ]]; then
    [[ $(podman inspect --format '{{.State.Running}}' marketlab-collector) == true ]] ||
        die "collector container is not running"
fi

podman inspect marketlab-api |
    jq -e '
        .[0].Config.Env
        | any(. == "MARKETLAB_DATABASE_USER=marketlab_api")
          and any(. == "MARKETLAB_DATABASE_MIGRATE=false")
          and all(. != "MARKETLAB_DATABASE_USER=marketlab_owner")
    ' >/dev/null ||
    die "API container has an unsafe database identity"
podman inspect marketlab-coordinator |
    jq -e --arg source_revision "$source_sha256" '
        .[0].Config.Env
        | any(. == "MARKETLAB_DATABASE_USER=marketlab_coordinator")
          and any(. == "MARKETLAB_DATABASE_MIGRATE=false")
          and any(. == ("MARKETLAB_SOURCE_REVISION=" + $source_revision))
          and all(. != "MARKETLAB_DATABASE_USER=marketlab_owner")
    ' >/dev/null ||
    die "coordinator container has an unsafe database identity or wrong source revision"
if [[ "$collector_enabled" == true ]]; then
    podman inspect marketlab-collector |
        jq -e --arg source_revision "$source_sha256" '
            .[0].Config.Env
            | any(. == "MARKETLAB_COLLECTOR_COIN=BTC")
              and any(. == "MARKETLAB_COLLECTOR_RAW_ROOT=/mnt/media/marketlab/raw/hyperliquid-stream")
              and any(. == ("MARKETLAB_SOURCE_REVISION=" + $source_revision))
              and all(
                  test("(^|_)(DATABASE|PASSWORD|TOKEN|SECRET)(_|=)") | not
              )
        ' >/dev/null ||
        die "collector environment is unscoped, secret-bearing, or reports the wrong revision"
fi

podman exec --interactive marketlab-postgres \
    psql --no-psqlrc --set=ON_ERROR_STOP=1 --username=marketlab_owner --dbname=marketlab \
    <"$SCRIPT_ROOT/sql/verify-roles.sql" >/dev/null ||
    die "database least-privilege verification failed"

assert_container_image marketlab-postgres "$postgres_image"
assert_container_image marketlab-api "$service_image"
assert_container_image marketlab-coordinator "$coordinator_image"
if [[ "$collector_enabled" == true ]]; then
    assert_container_image marketlab-collector "$collector_image"
fi
if [[ "$social_enabled" == true ]]; then
    assert_container_image marketlab-social-collector "$social_collector_image"
    assert_container_image marketlab-social-market "$collector_image"
    assert_container_image marketlab-sentiment-worker "$sentiment_worker_image"
fi

assert_mount marketlab-postgres \
    /mnt/stack/marketlab/postgres /var/lib/postgresql/data true
assert_mount marketlab-api \
    /mnt/media/marketlab/raw/public-information /mnt/media/marketlab/raw/public-information false
assert_mount marketlab-api \
    /mnt/stack/marketlab/social-features /mnt/stack/marketlab/social-features false
assert_mount marketlab-api \
    /mnt/stack/marketlab/social-universe /mnt/stack/marketlab/social-universe false
assert_mount marketlab-api \
    /mnt/stack/marketlab/models /mnt/stack/marketlab/models false
assert_mount marketlab-coordinator \
    /mnt/media/marketlab/raw /mnt/media/marketlab/raw true
assert_mount marketlab-coordinator \
    /mnt/stack/marketlab/active-artifacts /mnt/stack/marketlab/active-artifacts true
if [[ "$collector_enabled" == true ]]; then
    assert_mount marketlab-collector \
        /mnt/media/marketlab/raw/hyperliquid-stream \
        /mnt/media/marketlab/raw/hyperliquid-stream true
    podman inspect marketlab-collector |
        jq -e '
            .[0] as $container
            | $container.HostConfig.ReadonlyRootfs == true
              and $container.HostConfig.Privileged == false
              and ($container.HostConfig.SecurityOpt | any(. == "no-new-privileges"))
              and (($container.HostConfig.CapAdd // []) | length == 0)
              and ($container.Mounts | map(select(.Type == "bind")) | length == 1)
              and (($container.NetworkSettings.Ports // {}) | length == 0)
              and ($container.NetworkSettings.Networks | has("marketlab"))
        ' >/dev/null ||
        die "collector root filesystem, capability, mount, or port policy is unsafe"
    podman top marketlab-collector capeff |
        awk '
            NR == 1 { next }
            { seen = 1; if ($1 != "none") unsafe = 1 }
            END { exit !(seen && !unsafe) }
        ' ||
        die "collector has an effective Linux capability"
    [[ $(systemctl --user show marketlab-collector.service --property=Restart --value) == always ]] ||
        die "collector does not have rootless automatic restart"
    [[ -d /mnt/media/marketlab/raw/hyperliquid-stream/.partial ]] ||
        die "collector has not initialized its crash-safe raw-data store"
fi
if [[ "$social_enabled" == true ]]; then
    assert_mount marketlab-social-collector \
        /mnt/media/marketlab/raw/public-information /mnt/media/marketlab/raw/public-information true
    assert_mount marketlab-social-market \
        /mnt/media/marketlab/raw/social-market /mnt/media/marketlab/raw/social-market true
    assert_mount marketlab-social-market \
        /mnt/stack/marketlab/social-universe /mnt/stack/marketlab/social-universe true
    assert_mount marketlab-sentiment-worker \
        /mnt/media/marketlab/raw/public-information /mnt/media/marketlab/raw/public-information false
    assert_mount marketlab-sentiment-worker \
        /mnt/stack/marketlab/social-features /mnt/stack/marketlab/social-features true
    assert_mount marketlab-sentiment-worker \
        /mnt/stack/marketlab/social-universe /mnt/stack/marketlab/social-universe false
    assert_mount marketlab-sentiment-worker \
        /mnt/stack/marketlab/models /mnt/stack/marketlab/models false
fi

mapfile -t containers < <(podman ps -aq --filter name=marketlab)
if [[ ${#containers[@]} -gt 0 ]]; then
    podman inspect "${containers[@]}" |
        jq -e '
            all(
                .[];
                all(
                    .Mounts[]?;
                    ((.Source // "") | test("(^|/)(podman|docker)\\.sock$")) | not
                )
            )
        ' >/dev/null ||
        die "a Marketlab container has a container-engine socket mount"
fi

published_api=$(podman port marketlab-api 8080/tcp)
[[ "$published_api" == 127.0.0.1:8080 ]] ||
    die "API is not published exclusively on 127.0.0.1:8080"
[[ -z $(podman port marketlab-postgres) ]] || die "PostgreSQL unexpectedly publishes a host port"
if [[ "$collector_enabled" == true ]]; then
    [[ -z $(podman port marketlab-collector) ]] ||
        die "collector unexpectedly publishes a host port"
fi
ss -lntH 'sport = :8080' | grep -Eq '127\.0\.0\.1:8080([^0-9]|$)' ||
    die "host loopback API listener is absent"
if ss -lntH 'sport = :8080' | grep -Eq '(^|[[:space:]])(0\.0\.0\.0|\*|\[::\]):8080'; then
    die "API is also exposed on a wildcard host address"
fi

curl --fail --silent --show-error --max-time 10 \
    http://127.0.0.1:8080/health/ready >/dev/null ||
    die "API readiness endpoint failed"
curl --fail --silent --show-error --max-time 10 \
    http://127.0.0.1:8081/health/live >/dev/null ||
    die "runner liveness endpoint failed"

runner_properties=$(systemctl --user show marketlab-runner.service \
    --property=ExecStart --property=Environment)
grep -Fq "$RELEASE_ROOT/$release_id/runner/bin/runner" <<<"$runner_properties" ||
    die "runner executable is not from the requested release"
grep -Fq "MARKETLAB_IMAGE_KOTLIN=$worker_image" <<<"$runner_properties" ||
    die "runner worker image is not pinned to the requested release"
grep -Fq "MARKETLAB_IMAGE_PYTHON_GPU=$alpha_model_image" <<<"$runner_properties" ||
    die "runner GPU alpha-model image is not pinned to the requested release"

grep -Fq "$research_image" "$USER_BIN_ROOT/marketlab-research" ||
    die "research CLI wrapper is not pinned to the requested release"
grep -Fq -- '--network=slirp4netns:allow_host_loopback=false' "$USER_BIN_ROOT/marketlab-research" ||
    die "research CLI wrapper lacks constrained outbound networking"
grep -Fq \
    'src=/mnt/media/marketlab/raw,dst=/mnt/media/marketlab/raw,rw=true' \
    "$USER_BIN_ROOT/marketlab-research" ||
    die "research CLI wrapper lacks writable cold raw-data storage"
grep -Fq \
    'src=/mnt/stack/marketlab/active-artifacts,dst=/mnt/stack/marketlab/active-artifacts,rw=true' \
    "$USER_BIN_ROOT/marketlab-research" ||
    die "research CLI wrapper lacks writable hot artifact storage"
grep -Fq "MARKETLAB_SOURCE_REVISION=$(release_value SOURCE_SHA256)" \
    "$USER_BIN_ROOT/marketlab-research" ||
    die "research CLI wrapper does not report the release source revision"
grep -Fq -- '--source-revision|--source-revision=*' "$USER_BIN_ROOT/marketlab-research" ||
    die "research CLI wrapper permits source-revision override"
grep -Fq -- '--allow-unversioned|--allow-unversioned=*' "$USER_BIN_ROOT/marketlab-research" ||
    die "research CLI wrapper permits unversioned production evidence"

socket_policy_files=(
    "$QUADLET_ROOT/marketlab-api.container"
    "$QUADLET_ROOT/marketlab-coordinator.container"
    "$USER_BIN_ROOT/marketlab-research"
)
if [[ "$collector_enabled" == true ]]; then
    socket_policy_files+=("$QUADLET_ROOT/marketlab-collector.container")
fi
if grep -R -E 'podman\.sock|docker\.sock' "${socket_policy_files[@]}"; then
    die "deployment configuration exposes a container-engine socket"
fi

configured_images=(
    "$postgres_image"
    "$service_image"
    "$worker_image"
    "$alpha_model_image"
    "$coordinator_image"
    "$research_image"
    "$runner_image"
)
if [[ "$collector_enabled" == true ]]; then
    configured_images+=("$collector_image")
fi
for configured_image in "${configured_images[@]}"; do
    [[ "$configured_image" =~ @sha256:[0-9a-f]{64}$ ]] ||
        die "active image is not digest-pinned: $configured_image"
done

printf 'Release %s is healthy, loopback-only, socket-isolated, and digest-pinned.\n' "$release_id"
