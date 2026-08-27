#!/usr/bin/env bash
set -euo pipefail

umask 077

readonly STACK_ROOT=/mnt/stack/marketlab
readonly MEDIA_ROOT=/mnt/media/marketlab
readonly CONFIG_ROOT="${XDG_CONFIG_HOME:-$HOME/.config}/marketlab"
readonly STATE_ROOT="${XDG_STATE_HOME:-$HOME/.local/state}/marketlab"
readonly STORAGE_CONFIG="$CONFIG_ROOT/storage.conf"

die() {
    printf 'prepare-host: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "required command is unavailable: $1"
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

assert_secret_file() {
    local file=$1
    [[ -f "$file" && ! -L "$file" ]] || die "$file must be a regular, non-symlink file"
    [[ -O "$file" ]] || die "$file must be owned by the deployment user"
    chmod 0600 "$file"
}

write_secret_file() {
    local destination=$1
    local temporary
    [[ ! -e "$destination" ]] || return 0
    temporary=$(mktemp "$CONFIG_ROOT/.marketlab-env.XXXXXX")
    chmod 0600 "$temporary"
    cat >"$temporary"
    if ! ln -- "$temporary" "$destination"; then
        rm -f -- "$temporary"
        die "could not install $destination without overwriting it"
    fi
    rm -f -- "$temporary"
}

ensure_environment_line() {
    local destination=$1
    local key=$2
    local value=$3
    if ! grep -q "^${key}=" "$destination"; then
        printf '%s=%s\n' "$key" "$value" >>"$destination"
    fi
}

for command_name in awk cat chmod findmnt flock grep install ln mktemp openssl podman rm; do
    require_command "$command_name"
done

[[ $(id -u) -ne 0 ]] || die "run as the rootless Podman user, never as root"
[[ $(podman info --format '{{.Host.Security.Rootless}}') == true ]] ||
    die "Podman is not running rootless"

stack_mount=$(findmnt -n -o TARGET -T /mnt/stack) ||
    die "/mnt/stack is not backed by a mounted filesystem"
media_mount=$(findmnt -n -o TARGET -T /mnt/media) ||
    die "/mnt/media is not backed by a mounted filesystem"
[[ "$stack_mount" == /mnt/stack ]] || die "/mnt/stack resolves through unexpected mount $stack_mount"
[[ "$media_mount" == /mnt/media ]] || die "/mnt/media resolves through unexpected mount $media_mount"
stack_source=$(findmnt -n -o SOURCE -T /mnt/stack)
media_source=$(findmnt -n -o SOURCE -T /mnt/media)
[[ "$stack_source" != "$media_source" ]] ||
    die "hot and cold roots must reside on distinct mounted storage"

install -d -m 0700 "$CONFIG_ROOT" "$STATE_ROOT"
exec 9>"$STATE_ROOT/prepare.lock"
flock 9

install -d -m 0750 \
    "$STACK_ROOT" \
    "$STACK_ROOT/postgres" \
    "$STACK_ROOT/normalized" \
    "$STACK_ROOT/jobs" \
    "$STACK_ROOT/cache" \
    "$STACK_ROOT/scratch" \
    "$STACK_ROOT/active-artifacts" \
    "$STACK_ROOT/podman-storage" \
    "$STACK_ROOT/releases" \
    "$STACK_ROOT/source-releases" \
    "$STACK_ROOT/models" \
    "$STACK_ROOT/social-features" \
    "$STACK_ROOT/social-universe" \
    "$MEDIA_ROOT" \
    "$MEDIA_ROOT/raw" \
    "$MEDIA_ROOT/raw/hyperliquid-stream" \
    "$MEDIA_ROOT/raw/public-information" \
    "$MEDIA_ROOT/raw/social-market" \
    "$MEDIA_ROOT/raw/social-backfill-v2" \
    "$MEDIA_ROOT/cold-artifacts" \
    "$MEDIA_ROOT/backups"

write_secret_file "$STORAGE_CONFIG" <<EOF
[storage]
driver = "overlay"
runroot = "/run/user/$(id -u)/marketlab-containers"
graphroot = "$STACK_ROOT/podman-storage"
EOF
assert_secret_file "$STORAGE_CONFIG"
grep -Fq "graphroot = \"$STACK_ROOT/podman-storage\"" "$STORAGE_CONFIG" ||
    die "storage.conf must keep Marketlab container storage on the hot tier"
export CONTAINERS_STORAGE_CONF="$STORAGE_CONFIG"
[[ $(podman info --format '{{.Host.Security.Rootless}}') == true ]] ||
    die "Marketlab's isolated Podman storage configuration is not rootless"

owner_password=
if [[ -f "$CONFIG_ROOT/postgres.env" ]]; then
    assert_secret_file "$CONFIG_ROOT/postgres.env"
    owner_password=$(environment_value "$CONFIG_ROOT/postgres.env" POSTGRES_PASSWORD) ||
        die "POSTGRES_PASSWORD is missing from postgres.env"
elif [[ -f "$CONFIG_ROOT/migrator.env" ]]; then
    assert_secret_file "$CONFIG_ROOT/migrator.env"
    owner_password=$(environment_value "$CONFIG_ROOT/migrator.env" MARKETLAB_DATABASE_PASSWORD) ||
        die "MARKETLAB_DATABASE_PASSWORD is missing from migrator.env"
else
    owner_password=$(openssl rand -hex 48)
fi

if [[ -f "$CONFIG_ROOT/api.env" ]]; then
    assert_secret_file "$CONFIG_ROOT/api.env"
    api_password=$(environment_value "$CONFIG_ROOT/api.env" MARKETLAB_DATABASE_PASSWORD) ||
        die "MARKETLAB_DATABASE_PASSWORD is missing from api.env"
    api_token=$(environment_value "$CONFIG_ROOT/api.env" MARKETLAB_API_TOKEN) ||
        die "MARKETLAB_API_TOKEN is missing from api.env"
else
    api_password=$(openssl rand -hex 48)
    api_token=$(openssl rand -hex 48)
fi

if [[ -f "$CONFIG_ROOT/coordinator.env" ]]; then
    assert_secret_file "$CONFIG_ROOT/coordinator.env"
    coordinator_password=$(environment_value "$CONFIG_ROOT/coordinator.env" MARKETLAB_DATABASE_PASSWORD) ||
        die "MARKETLAB_DATABASE_PASSWORD is missing from coordinator.env"
else
    coordinator_password=$(openssl rand -hex 48)
fi

if [[ -f "$CONFIG_ROOT/runner.env" ]]; then
    assert_secret_file "$CONFIG_ROOT/runner.env"
    runner_token=$(environment_value "$CONFIG_ROOT/runner.env" MARKETLAB_RUNNER_TOKEN) ||
        die "MARKETLAB_RUNNER_TOKEN is missing from runner.env"
else
    runner_token=$(openssl rand -hex 48)
fi

for role_password in "$owner_password" "$api_password" "$coordinator_password"; do
    [[ "$role_password" =~ ^[0-9a-f]{96}$ ]] ||
        die "database role passwords must be 48-byte lowercase hexadecimal secrets"
done
[[ "$owner_password" != "$api_password" ]] ||
    die "owner and API database passwords must be distinct"
[[ "$owner_password" != "$coordinator_password" ]] ||
    die "owner and coordinator database passwords must be distinct"
[[ "$api_password" != "$coordinator_password" ]] ||
    die "API and coordinator database passwords must be distinct"

write_secret_file "$CONFIG_ROOT/postgres.env" <<EOF
POSTGRES_DB=marketlab
POSTGRES_USER=marketlab_owner
POSTGRES_PASSWORD=$owner_password
POSTGRES_INITDB_ARGS=--data-checksums
EOF

write_secret_file "$CONFIG_ROOT/migrator.env" <<EOF
MARKETLAB_DATABASE_URL=jdbc:postgresql://marketlab-postgres:5432/marketlab
MARKETLAB_DATABASE_USER=marketlab_owner
MARKETLAB_DATABASE_PASSWORD=$owner_password
EOF

write_secret_file "$CONFIG_ROOT/api.env" <<EOF
MARKETLAB_API_HOST=0.0.0.0
MARKETLAB_API_PORT=8080
MARKETLAB_ALLOW_CONTAINER_WILDCARD_BIND=true
MARKETLAB_API_TOKEN=$api_token
MARKETLAB_DATABASE_URL=jdbc:postgresql://marketlab-postgres:5432/marketlab
MARKETLAB_DATABASE_USER=marketlab_api
MARKETLAB_DATABASE_PASSWORD=$api_password
MARKETLAB_DATABASE_POOL_SIZE=10
MARKETLAB_DATABASE_MIN_IDLE=1
MARKETLAB_DATABASE_MIGRATE=false
MARKETLAB_EXPOSE_ERROR_DETAILS=false
MARKETLAB_SOCIAL_RAW_ROOT=$MEDIA_ROOT/raw/public-information
MARKETLAB_SENTIMENT_FEATURE_ROOT=$STACK_ROOT/social-features
MARKETLAB_SOCIAL_UNIVERSE_ROOT=$STACK_ROOT/social-universe
MARKETLAB_SENTIMENT_MODEL_LOCK=$STACK_ROOT/models/sentiment-models.lock.json
EOF

ensure_environment_line "$CONFIG_ROOT/api.env" MARKETLAB_SOCIAL_RAW_ROOT "$MEDIA_ROOT/raw/public-information"
ensure_environment_line "$CONFIG_ROOT/api.env" MARKETLAB_SENTIMENT_FEATURE_ROOT "$STACK_ROOT/social-features"
ensure_environment_line "$CONFIG_ROOT/api.env" MARKETLAB_SOCIAL_UNIVERSE_ROOT "$STACK_ROOT/social-universe"
ensure_environment_line "$CONFIG_ROOT/api.env" MARKETLAB_SENTIMENT_MODEL_LOCK "$STACK_ROOT/models/sentiment-models.lock.json"

write_secret_file "$CONFIG_ROOT/runner.env" <<EOF
MARKETLAB_RUNNER_HOST=127.0.0.1
MARKETLAB_RUNNER_PORT=8081
MARKETLAB_RUNNER_TOKEN=$runner_token
MARKETLAB_JOBS_ROOT=$STACK_ROOT/jobs
MARKETLAB_PODMAN=/usr/bin/podman
CONTAINERS_STORAGE_CONF=$STORAGE_CONFIG
EOF

write_secret_file "$CONFIG_ROOT/coordinator.env" <<EOF
MARKETLAB_COORDINATOR_ID=gerald-ingestion-1
MARKETLAB_RAW_DATA_ROOT=$MEDIA_ROOT/raw
MARKETLAB_ACTIVE_ARTIFACT_ROOT=$STACK_ROOT/active-artifacts
MARKETLAB_COORDINATOR_LEASE_MS=120000
MARKETLAB_COORDINATOR_HEARTBEAT_MS=30000
MARKETLAB_COORDINATOR_POLL_MS=1000
MARKETLAB_COORDINATOR_REAP_MS=30000
MARKETLAB_COORDINATOR_RETRY_MS=15000
MARKETLAB_DEFAULT_CANDLE_INTERVAL=1h
MARKETLAB_MAX_INGESTION_DAYS=3650
MARKETLAB_DATABASE_URL=jdbc:postgresql://marketlab-postgres:5432/marketlab
MARKETLAB_DATABASE_USER=marketlab_coordinator
MARKETLAB_DATABASE_PASSWORD=$coordinator_password
MARKETLAB_DATABASE_POOL_SIZE=4
MARKETLAB_DATABASE_MIN_IDLE=1
MARKETLAB_DATABASE_MIGRATE=false
EOF

write_secret_file "$CONFIG_ROOT/collector.env" <<EOF
MARKETLAB_COLLECTOR_RAW_ROOT=$MEDIA_ROOT/raw/hyperliquid-stream
MARKETLAB_COLLECTOR_COIN=BTC
MARKETLAB_COLLECTOR_MAX_SEGMENT_BYTES=134217728
MARKETLAB_COLLECTOR_MAX_SEGMENT_MILLIS=900000
MARKETLAB_COLLECTOR_QUEUE_CAPACITY=64
MARKETLAB_COLLECTOR_MAX_FRAME_BYTES=2097152
EOF

write_secret_file "$CONFIG_ROOT/social-collector.env" <<EOF
MARKETLAB_SOCIAL_RAW_ROOT=$MEDIA_ROOT/raw/public-information
MARKETLAB_SOCIAL_FARCASTER_ENABLED=false
MARKETLAB_SOCIAL_FARCASTER_EVENTS_URI=http://marketlab-snapchain:3381/v1/events
MARKETLAB_SOCIAL_MAX_SEGMENT_BYTES=67108864
MARKETLAB_SOCIAL_MAX_SEGMENT_MILLIS=900000
MARKETLAB_SOCIAL_QUEUE_CAPACITY=1024
MARKETLAB_SOCIAL_MAX_FRAME_BYTES=4194304
MARKETLAB_SOCIAL_POLL_MILLIS=300000
MARKETLAB_SOCIAL_UNIVERSE_REFRESH_MILLIS=3600000
EOF
ensure_environment_line "$CONFIG_ROOT/social-collector.env" MARKETLAB_SOCIAL_FARCASTER_ENABLED false

write_secret_file "$CONFIG_ROOT/social-market.env" <<EOF
MARKETLAB_COLLECTOR_RAW_ROOT=$MEDIA_ROOT/raw/social-market
MARKETLAB_COLLECTOR_COIN=BTC
MARKETLAB_COLLECTOR_UNIVERSE_MODE=TOP_NOTIONAL_30D
MARKETLAB_SOCIAL_UNIVERSE_ROOT=$STACK_ROOT/social-universe
MARKETLAB_COLLECTOR_MAX_SEGMENT_BYTES=134217728
MARKETLAB_COLLECTOR_MAX_SEGMENT_MILLIS=900000
MARKETLAB_COLLECTOR_QUEUE_CAPACITY=256
MARKETLAB_COLLECTOR_MAX_FRAME_BYTES=2097152
EOF

write_secret_file "$CONFIG_ROOT/sentiment-worker.env" <<EOF
MARKETLAB_SOCIAL_RAW_ROOT=$MEDIA_ROOT/raw/public-information
MARKETLAB_SENTIMENT_FEATURE_ROOT=$STACK_ROOT/social-features
MARKETLAB_SOCIAL_UNIVERSE_ROOT=$STACK_ROOT/social-universe
MARKETLAB_SENTIMENT_MODELS_ROOT=$STACK_ROOT/models
MARKETLAB_SENTIMENT_MODEL_LOCK=/opt/marketlab/research/sentiment-models.lock.json
MARKETLAB_SOCIAL_PROGRAM_LOCK=/opt/marketlab/research/social-program.lock.json
MARKETLAB_SENTIMENT_POLL_MILLIS=30000
EOF

for secret_file in \
    "$CONFIG_ROOT/api.env" \
    "$CONFIG_ROOT/collector.env" \
    "$CONFIG_ROOT/coordinator.env" \
    "$CONFIG_ROOT/migrator.env" \
    "$CONFIG_ROOT/postgres.env" \
    "$CONFIG_ROOT/runner.env"; do
    assert_secret_file "$secret_file"
done
for public_environment_file in \
    "$CONFIG_ROOT/social-collector.env" \
    "$CONFIG_ROOT/social-market.env" \
    "$CONFIG_ROOT/sentiment-worker.env"; do
    assert_secret_file "$public_environment_file"
    if grep -Eq '(^|_)(DATABASE|PASSWORD|TOKEN|SECRET)(_|=)' "$public_environment_file"; then
        die "$public_environment_file must not contain credentials or secrets"
    fi
done

configured_owner_password=$(environment_value "$CONFIG_ROOT/postgres.env" POSTGRES_PASSWORD)
migrator_password=$(environment_value "$CONFIG_ROOT/migrator.env" MARKETLAB_DATABASE_PASSWORD)
configured_api_password=$(environment_value "$CONFIG_ROOT/api.env" MARKETLAB_DATABASE_PASSWORD)
configured_coordinator_password=$(environment_value "$CONFIG_ROOT/coordinator.env" MARKETLAB_DATABASE_PASSWORD)
[[ "$configured_owner_password" == "$migrator_password" ]] ||
    die "owner password disagrees between postgres.env and migrator.env"
[[ "$configured_owner_password" != "$configured_api_password" ]] ||
    die "owner and API roles share a password"
[[ "$configured_owner_password" != "$configured_coordinator_password" ]] ||
    die "owner and coordinator roles share a password"
[[ "$configured_api_password" != "$configured_coordinator_password" ]] ||
    die "API and coordinator roles share a password"

[[ $(environment_value "$CONFIG_ROOT/postgres.env" POSTGRES_USER) == marketlab_owner ]] ||
    die "POSTGRES_USER must be marketlab_owner"
[[ $(environment_value "$CONFIG_ROOT/migrator.env" MARKETLAB_DATABASE_USER) == marketlab_owner ]] ||
    die "migrator must use only marketlab_owner"
[[ $(environment_value "$CONFIG_ROOT/api.env" MARKETLAB_DATABASE_USER) == marketlab_api ]] ||
    die "API must use the constrained marketlab_api role"
[[ $(environment_value "$CONFIG_ROOT/coordinator.env" MARKETLAB_DATABASE_USER) == marketlab_coordinator ]] ||
    die "coordinator must use the constrained marketlab_coordinator role"
[[ $(environment_value "$CONFIG_ROOT/api.env" MARKETLAB_DATABASE_MIGRATE) == false ]] ||
    die "API migrations must be disabled"
[[ $(environment_value "$CONFIG_ROOT/coordinator.env" MARKETLAB_DATABASE_MIGRATE) == false ]] ||
    die "coordinator migrations must be disabled"
[[ $(environment_value "$CONFIG_ROOT/runner.env" CONTAINERS_STORAGE_CONF) == "$STORAGE_CONFIG" ]] ||
    die "runner must use Marketlab's isolated Podman storage"
[[ $(environment_value "$CONFIG_ROOT/coordinator.env" MARKETLAB_RAW_DATA_ROOT) == "$MEDIA_ROOT/raw" ]] ||
    die "coordinator raw-data root must use cold storage"
[[ $(environment_value "$CONFIG_ROOT/coordinator.env" MARKETLAB_ACTIVE_ARTIFACT_ROOT) == "$STACK_ROOT/active-artifacts" ]] ||
    die "coordinator artifact root must use hot storage"
[[ $(environment_value "$CONFIG_ROOT/collector.env" MARKETLAB_COLLECTOR_RAW_ROOT) == "$MEDIA_ROOT/raw/hyperliquid-stream" ]] ||
    die "collector raw-data root must use its narrow cold-storage directory"
[[ $(environment_value "$CONFIG_ROOT/collector.env" MARKETLAB_COLLECTOR_COIN) == BTC ]] ||
    die "exactly one BTC collector is supported"
[[ $(environment_value "$CONFIG_ROOT/collector.env" MARKETLAB_COLLECTOR_MAX_SEGMENT_BYTES) == 134217728 ]] ||
    die "collector segment-size boundary differs from the reviewed deployment"
[[ $(environment_value "$CONFIG_ROOT/collector.env" MARKETLAB_COLLECTOR_MAX_SEGMENT_MILLIS) == 900000 ]] ||
    die "collector segment-time boundary differs from the reviewed deployment"
[[ $(environment_value "$CONFIG_ROOT/collector.env" MARKETLAB_COLLECTOR_QUEUE_CAPACITY) == 64 ]] ||
    die "collector queue boundary differs from the reviewed deployment"
[[ $(environment_value "$CONFIG_ROOT/collector.env" MARKETLAB_COLLECTOR_MAX_FRAME_BYTES) == 2097152 ]] ||
    die "collector frame-size boundary differs from the reviewed deployment"
if grep -Eq '(^|_)(DATABASE|PASSWORD|TOKEN|SECRET)(_|=)' "$CONFIG_ROOT/collector.env"; then
    die "collector environment must not contain database credentials or secrets"
fi

configured_api_token=$(environment_value "$CONFIG_ROOT/api.env" MARKETLAB_API_TOKEN)
configured_runner_token=$(environment_value "$CONFIG_ROOT/runner.env" MARKETLAB_RUNNER_TOKEN)
[[ ${#configured_api_token} -ge 32 ]] || die "MARKETLAB_API_TOKEN is too short"
[[ ${#configured_runner_token} -ge 32 ]] || die "MARKETLAB_RUNNER_TOKEN is too short"
[[ $(environment_value "$CONFIG_ROOT/api.env" MARKETLAB_API_HOST) == 0.0.0.0 ]] ||
    die "container API host must be 0.0.0.0; host exposure remains loopback-only in Quadlet"
[[ $(environment_value "$CONFIG_ROOT/api.env" MARKETLAB_ALLOW_CONTAINER_WILDCARD_BIND) == true ]] ||
    die "MARKETLAB_ALLOW_CONTAINER_WILDCARD_BIND must be true for the container"

printf 'Prepared rootless Marketlab directories and 0600 environment files.\n'
printf 'Hot storage:  %s (%s)\n' "$STACK_ROOT" "$stack_source"
printf 'Cold storage: %s (%s)\n' "$MEDIA_ROOT" "$media_source"
