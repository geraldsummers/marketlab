#!/usr/bin/env bash
set -euo pipefail

umask 077

REPOSITORY_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
readonly REPOSITORY_ROOT
readonly DEPLOY_ROOT="$REPOSITORY_ROOT/deploy"
readonly RELEASE_ROOT=/mnt/stack/marketlab/releases
readonly BACKUP_ROOT=/mnt/media/marketlab/backups
readonly CONFIG_ROOT="${XDG_CONFIG_HOME:-$HOME/.config}"
readonly STATE_ROOT="${XDG_STATE_HOME:-$HOME/.local/state}/marketlab"
readonly QUADLET_ROOT="$CONFIG_ROOT/containers/systemd"
readonly USER_UNIT_ROOT="$CONFIG_ROOT/systemd/user"
readonly USER_BIN_ROOT="$HOME/.local/bin"
readonly STORAGE_CONFIG="$CONFIG_ROOT/marketlab/storage.conf"

die() {
    printf 'activate-release: %s\n' "$*" >&2
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

assert_digest_reference() {
    local name=$1
    local reference=$2
    [[ "$reference" =~ ^[a-z0-9.-]+/[a-z0-9._/-]+@sha256:[0-9a-f]{64}$ ]] ||
        die "$name is not an immutable sha256 image reference"
}

pull_public_image() {
    local reference=$1
    local anonymous_authfile
    anonymous_authfile=$(mktemp "$CONFIG_ROOT/marketlab/.anonymous-auth.XXXXXX")
    chmod 0600 "$anonymous_authfile"
    printf '{"auths":{}}\n' >"$anonymous_authfile"
    if ! podman pull \
        --quiet \
        --platform=linux/amd64 \
        --authfile "$anonymous_authfile" \
        "$reference" >/dev/null; then
        rm -f -- "$anonymous_authfile"
        die "cannot pull pinned linux/amd64 image $reference"
    fi
    rm -f -- "$anonymous_authfile"
}

render_template() {
    local source=$1
    local destination=$2
    sed \
        -e "s|@SERVICE_IMAGE_DIGEST@|$service_image|g" \
        -e "s|@WORKER_IMAGE_DIGEST@|$worker_image|g" \
        -e "s|@COORDINATOR_IMAGE_DIGEST@|$coordinator_image|g" \
        -e "s|@COLLECTOR_IMAGE_DIGEST@|$collector_image|g" \
        -e "s|@SOCIAL_COLLECTOR_IMAGE_DIGEST@|$social_collector_image|g" \
        -e "s|@SENTIMENT_WORKER_IMAGE_DIGEST@|$sentiment_worker_image|g" \
        -e "s|@RESEARCH_IMAGE_DIGEST@|$research_image|g" \
        -e "s|@POSTGRES_IMAGE_DIGEST@|$postgres_image|g" \
        -e "s|@RUNNER_INSTALL_DIR@|$release_directory/runner|g" \
        -e "s|@SOURCE_REVISION@|$source_sha256|g" \
        "$source" >"$destination"
    if grep -Eq '@[A-Z0-9_]+@' "$destination"; then
        die "unresolved deployment placeholder in $source"
    fi
}

backup_file() {
    local source=$1
    local key=$2
    if [[ -e "$source" || -L "$source" ]]; then
        cp -a -- "$source" "$activation_directory/backup/$key"
    else
        : >"$activation_directory/backup/$key.absent"
    fi
}

restore_file() {
    local destination=$1
    local key=$2
    if [[ -f "$activation_directory/backup/$key.absent" ]]; then
        rm -f -- "$destination"
    else
        install -m "$(stat -c '%a' "$activation_directory/backup/$key")" \
            "$activation_directory/backup/$key" "$destination"
    fi
}

restore_previous_configuration() {
    set +e
    systemctl --user stop \
        marketlab-sentiment-worker.service \
        marketlab-social-market.service \
        marketlab-social-collector.service
    restore_file "$QUADLET_ROOT/marketlab.network" quadlet-network
    restore_file "$QUADLET_ROOT/marketlab-postgres.container" quadlet-postgres
    restore_file "$QUADLET_ROOT/marketlab-api.container" quadlet-api
    restore_file "$QUADLET_ROOT/marketlab-coordinator.container" quadlet-coordinator
    restore_file "$QUADLET_ROOT/marketlab-collector.container" quadlet-collector
    restore_file "$QUADLET_ROOT/marketlab-social-collector.container" quadlet-social-collector
    restore_file "$QUADLET_ROOT/marketlab-social-market.container" quadlet-social-market
    restore_file "$QUADLET_ROOT/marketlab-sentiment-worker.container" quadlet-sentiment-worker
    restore_file "$USER_UNIT_ROOT/marketlab-runner.service" runner-service
    restore_file "$USER_BIN_ROOT/marketlab-research" research-cli
    systemctl --user daemon-reload
    if [[ -f "$activation_directory/previous-release" ]]; then
        systemctl --user restart marketlab-postgres.service
        systemctl --user restart marketlab-runner.service
        systemctl --user restart marketlab-api.service
        systemctl --user restart marketlab-coordinator.service
        if [[ -f "$QUADLET_ROOT/marketlab-collector.container" ]]; then
            systemctl --user restart marketlab-collector.service
        else
            systemctl --user stop marketlab-collector.service
        fi
        for social_unit in \
            marketlab-social-collector \
            marketlab-social-market \
            marketlab-sentiment-worker; do
            if [[ -f "$QUADLET_ROOT/$social_unit.container" ]]; then
                systemctl --user restart "$social_unit.service"
            else
                systemctl --user stop "$social_unit.service"
            fi
        done
    else
        systemctl --user stop \
            marketlab-collector.service \
            marketlab-social-collector.service \
            marketlab-social-market.service \
            marketlab-sentiment-worker.service \
            marketlab-coordinator.service \
            marketlab-api.service \
            marketlab-runner.service \
            marketlab-postgres.service
    fi
    set -e
}

activation_installed=false
handle_exit() {
    local status=$?
    trap - EXIT
    if [[ $status -ne 0 && "$activation_installed" == true ]]; then
        printf 'Activation failed; restoring the previous unit files.\n' >&2
        restore_previous_configuration
    fi
    exit "$status"
}
trap handle_exit EXIT

for command_name in awk cat chmod cp curl date flock grep install jq mktemp podman rm sed sha256sum stat systemctl; do
    require_command "$command_name"
done

[[ $(id -u) -ne 0 ]] || die "run as the rootless Podman user, never as root"
[[ -f "$STORAGE_CONFIG" && ! -L "$STORAGE_CONFIG" ]] ||
    die "run deploy/prepare-host.sh before activating a release"
export CONTAINERS_STORAGE_CONF="$STORAGE_CONFIG"
[[ $(podman info --format '{{.Host.Security.Rootless}}') == true ]] ||
    die "Podman is not running rootless"

release_id=${1:-}
[[ "$release_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] ||
    die "usage: deploy/activate-release.sh <built-release-id>"
release_directory="$RELEASE_ROOT/$release_id"
release_manifest="$release_directory/images.env"
[[ -f "$release_manifest" && ! -L "$release_manifest" ]] ||
    die "release is not built: $release_id"
[[ -x "$release_directory/runner/bin/runner" ]] ||
    die "release has no executable runner distribution"

manifest_release=$(release_value RELEASE_ID)
source_sha256=$(release_value SOURCE_SHA256)
postgres_image=$(release_value POSTGRES_IMAGE)
service_image=$(release_value SERVICE_IMAGE)
worker_image=$(release_value WORKER_IMAGE)
coordinator_image=$(release_value COORDINATOR_IMAGE)
collector_image=$(release_value COLLECTOR_IMAGE 2>/dev/null || true)
social_collector_image=$(release_value SOCIAL_COLLECTOR_IMAGE 2>/dev/null || true)
sentiment_worker_image=$(release_value SENTIMENT_WORKER_IMAGE 2>/dev/null || true)
research_image=$(release_value RESEARCH_IMAGE)
runner_image=$(release_value RUNNER_IMAGE)
[[ "$manifest_release" == "$release_id" ]] || die "release manifest id does not match its directory"
[[ "$source_sha256" =~ ^[0-9a-f]{64}$ ]] || die "release source digest is invalid"
assert_digest_reference POSTGRES_IMAGE "$postgres_image"
assert_digest_reference SERVICE_IMAGE "$service_image"
assert_digest_reference WORKER_IMAGE "$worker_image"
assert_digest_reference COORDINATOR_IMAGE "$coordinator_image"
assert_digest_reference RESEARCH_IMAGE "$research_image"
assert_digest_reference RUNNER_IMAGE "$runner_image"
social_enabled=false
if [[ -n "$social_collector_image" || -n "$sentiment_worker_image" ]]; then
    [[ -n "$social_collector_image" && -n "$sentiment_worker_image" ]] ||
        die "social release images must be present together"
    assert_digest_reference SOCIAL_COLLECTOR_IMAGE "$social_collector_image"
    assert_digest_reference SENTIMENT_WORKER_IMAGE "$sentiment_worker_image"
    social_enabled=true
fi
collector_enabled=false
if [[ -n "$collector_image" ]]; then
    assert_digest_reference COLLECTOR_IMAGE "$collector_image"
    collector_enabled=true
fi

environment_files=(api.env collector.env coordinator.env migrator.env postgres.env runner.env)
if [[ "$social_enabled" == true ]]; then
    environment_files+=(social-collector.env social-market.env sentiment-worker.env)
fi
for environment_file in "${environment_files[@]}"; do
    path="$CONFIG_ROOT/marketlab/$environment_file"
    [[ -f "$path" && ! -L "$path" && -O "$path" ]] ||
        die "missing owned, regular environment file: $path"
    [[ $(stat -c '%a' "$path") == 600 ]] ||
        die "environment file must have mode 0600: $path"
done

owner_password=$(environment_value "$CONFIG_ROOT/marketlab/postgres.env" POSTGRES_PASSWORD)
migrator_password=$(environment_value "$CONFIG_ROOT/marketlab/migrator.env" MARKETLAB_DATABASE_PASSWORD)
api_password=$(environment_value "$CONFIG_ROOT/marketlab/api.env" MARKETLAB_DATABASE_PASSWORD)
coordinator_password=$(environment_value "$CONFIG_ROOT/marketlab/coordinator.env" MARKETLAB_DATABASE_PASSWORD)
for role_password in "$owner_password" "$api_password" "$coordinator_password"; do
    [[ "$role_password" =~ ^[0-9a-f]{96}$ ]] ||
        die "database role passwords must be 48-byte lowercase hexadecimal secrets"
done
[[ "$owner_password" == "$migrator_password" ]] ||
    die "migrator and PostgreSQL owner passwords disagree"
[[ "$owner_password" != "$api_password" && "$owner_password" != "$coordinator_password" ]] ||
    die "owner credentials must not reach an application role"
[[ "$api_password" != "$coordinator_password" ]] ||
    die "API and coordinator database credentials must be distinct"
[[ $(environment_value "$CONFIG_ROOT/marketlab/api.env" MARKETLAB_DATABASE_USER) == marketlab_api ]] ||
    die "API database role is not constrained"
[[ $(environment_value "$CONFIG_ROOT/marketlab/coordinator.env" MARKETLAB_DATABASE_USER) == marketlab_coordinator ]] ||
    die "coordinator database role is not constrained"
[[ $(environment_value "$CONFIG_ROOT/marketlab/migrator.env" MARKETLAB_DATABASE_USER) == marketlab_owner ]] ||
    die "migration credential is not the schema owner"
[[ $(environment_value "$CONFIG_ROOT/marketlab/api.env" MARKETLAB_DATABASE_MIGRATE) == false ]] ||
    die "long-running API must not perform migrations"
[[ $(environment_value "$CONFIG_ROOT/marketlab/coordinator.env" MARKETLAB_DATABASE_MIGRATE) == false ]] ||
    die "long-running coordinator must not perform migrations"
[[ $(environment_value "$CONFIG_ROOT/marketlab/collector.env" MARKETLAB_COLLECTOR_RAW_ROOT) == /mnt/media/marketlab/raw/hyperliquid-stream ]] ||
    die "collector raw-data root is outside its narrow cold-storage mount"
[[ $(environment_value "$CONFIG_ROOT/marketlab/collector.env" MARKETLAB_COLLECTOR_COIN) == BTC ]] ||
    die "exactly one BTC collector is supported"
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
    die "collector environment must not contain database credentials or secrets"
fi
if [[ "$social_enabled" == true ]]; then
for public_environment in social-collector.env social-market.env sentiment-worker.env; do
    if grep -Eq '(^|_)(DATABASE|PASSWORD|TOKEN|SECRET)(_|=)' \
        "$CONFIG_ROOT/marketlab/$public_environment"; then
        die "$public_environment must not contain credentials or secrets"
    fi
done
[[ $(environment_value "$CONFIG_ROOT/marketlab/social-collector.env" MARKETLAB_SOCIAL_RAW_ROOT) == /mnt/media/marketlab/raw/public-information ]] ||
    die "social collector raw root is outside its narrow cold-storage mount"
[[ $(environment_value "$CONFIG_ROOT/marketlab/social-market.env" MARKETLAB_COLLECTOR_UNIVERSE_MODE) == TOP_NOTIONAL_30D ]] ||
    die "social market collector is not using the frozen dynamic universe"
[[ $(environment_value "$CONFIG_ROOT/marketlab/sentiment-worker.env" MARKETLAB_SENTIMENT_MODELS_ROOT) == /mnt/stack/marketlab/models ]] ||
    die "sentiment model root differs from the reviewed deployment"

model_lock=/mnt/stack/marketlab/models/sentiment-models.lock.json
[[ -f "$model_lock" && ! -L "$model_lock" ]] ||
    die "sentiment model lock has not been installed"
while IFS=$'\t' read -r relative expected; do
    [[ "$relative" =~ ^(social|news)/(model[.]onnx|tokenizer[.]json)$ ]] ||
        die "sentiment model lock contains an unsafe artifact path"
    artifact="/mnt/stack/marketlab/models/$relative"
    [[ -f "$artifact" && ! -L "$artifact" ]] ||
        die "locked sentiment artifact is missing: $relative"
    [[ $(sha256sum "$artifact" | awk '{print $1}') == "$expected" ]] ||
        die "locked sentiment artifact hash mismatch: $relative"
done < <(
    jq -r '.models[] | [.modelFile,.modelSha256], [.tokenizerFile,.tokenizerSha256] | @tsv' "$model_lock"
)
fi

(
    cd "$release_directory/runner"
    sha256sum --quiet --check "$release_directory/runner.sha256"
) || die "runner distribution checksum verification failed"

local_images=(
    "$service_image"
    "$worker_image"
    "$coordinator_image"
    "$research_image"
    "$runner_image"
)
if [[ "$social_enabled" == true ]]; then
    local_images+=("$social_collector_image" "$sentiment_worker_image")
fi
if [[ "$collector_enabled" == true ]]; then
    local_images+=("$collector_image")
fi
for local_image in "${local_images[@]}"; do
    podman image exists "$local_image" || die "release image is absent from local storage: $local_image"
    image_source=$(podman image inspect --format '{{index .Labels "dev.marketlab.source-sha256"}}' "$local_image")
    [[ "$image_source" == "$source_sha256" ]] ||
        die "release image label does not match source digest: $local_image"
    image_revision=$(podman image inspect --format '{{index .Labels "org.opencontainers.image.revision"}}' "$local_image")
    [[ "$image_revision" == "$source_sha256" ]] ||
        die "OCI revision label does not match source digest: $local_image"
done
pull_public_image "$postgres_image"

install -d -m 0700 "$STATE_ROOT" "$STATE_ROOT/activations"
exec 9>"$STATE_ROOT/activate.lock"
flock 9

if [[ -f "$STATE_ROOT/active-release" ]] &&
    [[ $(<"$STATE_ROOT/active-release") == "$release_id" ]]; then
    "$DEPLOY_ROOT/verify.sh" "$release_id"
    printf 'Release %s is already active and verified.\n' "$release_id"
    exit 0
fi

activation_id="$(date -u +%Y%m%dT%H%M%SZ)-$$-$release_id"
activation_directory="$STATE_ROOT/activations/$activation_id"
install -d -m 0700 \
    "$activation_directory" \
    "$activation_directory/backup" \
    "$activation_directory/rendered/quadlet" \
    "$activation_directory/rendered/systemd" \
    "$activation_directory/rendered/bin" \
    "$QUADLET_ROOT" \
    "$USER_UNIT_ROOT" \
    "$USER_BIN_ROOT"

if [[ -f "$STATE_ROOT/active-release" ]]; then
    cp -- "$STATE_ROOT/active-release" "$activation_directory/previous-release"
else
    : >"$activation_directory/no-previous-release"
fi

backup_file "$QUADLET_ROOT/marketlab.network" quadlet-network
backup_file "$QUADLET_ROOT/marketlab-postgres.container" quadlet-postgres
backup_file "$QUADLET_ROOT/marketlab-api.container" quadlet-api
backup_file "$QUADLET_ROOT/marketlab-coordinator.container" quadlet-coordinator
backup_file "$QUADLET_ROOT/marketlab-collector.container" quadlet-collector
backup_file "$QUADLET_ROOT/marketlab-social-collector.container" quadlet-social-collector
backup_file "$QUADLET_ROOT/marketlab-social-market.container" quadlet-social-market
backup_file "$QUADLET_ROOT/marketlab-sentiment-worker.container" quadlet-sentiment-worker
backup_file "$USER_UNIT_ROOT/marketlab-runner.service" runner-service
backup_file "$USER_BIN_ROOT/marketlab-research" research-cli

render_template \
    "$DEPLOY_ROOT/quadlet/marketlab.network" \
    "$activation_directory/rendered/quadlet/marketlab.network"
render_template \
    "$DEPLOY_ROOT/quadlet/marketlab-postgres.container.in" \
    "$activation_directory/rendered/quadlet/marketlab-postgres.container"
render_template \
    "$DEPLOY_ROOT/quadlet/marketlab-api.container.in" \
    "$activation_directory/rendered/quadlet/marketlab-api.container"
render_template \
    "$DEPLOY_ROOT/quadlet/marketlab-coordinator.container.in" \
    "$activation_directory/rendered/quadlet/marketlab-coordinator.container"
if [[ "$collector_enabled" == true ]]; then
    render_template \
        "$DEPLOY_ROOT/quadlet/marketlab-collector.container.in" \
        "$activation_directory/rendered/quadlet/marketlab-collector.container"
fi
if [[ "$social_enabled" == true ]]; then
    render_template \
        "$DEPLOY_ROOT/quadlet/marketlab-social-collector.container.in" \
        "$activation_directory/rendered/quadlet/marketlab-social-collector.container"
    render_template \
        "$DEPLOY_ROOT/quadlet/marketlab-social-market.container.in" \
        "$activation_directory/rendered/quadlet/marketlab-social-market.container"
    render_template \
        "$DEPLOY_ROOT/quadlet/marketlab-sentiment-worker.container.in" \
        "$activation_directory/rendered/quadlet/marketlab-sentiment-worker.container"
fi
render_template \
    "$DEPLOY_ROOT/systemd/marketlab-runner.service.in" \
    "$activation_directory/rendered/systemd/marketlab-runner.service"
render_template \
    "$DEPLOY_ROOT/bin/marketlab-research.in" \
    "$activation_directory/rendered/bin/marketlab-research"
chmod 0644 "$activation_directory"/rendered/quadlet/*
chmod 0644 "$activation_directory/rendered/systemd/marketlab-runner.service"
chmod 0755 "$activation_directory/rendered/bin/marketlab-research"

generator=/usr/lib/systemd/system-generators/podman-system-generator
[[ -x "$generator" ]] || die "Podman Quadlet generator is unavailable"
if ! QUADLET_UNIT_DIRS="$activation_directory/rendered/quadlet" \
    "$generator" --user --dryrun \
    >"$activation_directory/quadlet-dryrun.out" \
    2>"$activation_directory/quadlet-dryrun.err"; then
    die "Quadlet validation failed; see $activation_directory/quadlet-dryrun.err"
fi
if grep -Eqi 'unsupported|(^|[^a-z])(error|failed|invalid|cannot)([^a-z]|$)' \
    "$activation_directory/quadlet-dryrun.err"; then
    die "Quadlet validation emitted an error; see $activation_directory/quadlet-dryrun.err"
fi

if podman container exists marketlab-postgres &&
    [[ $(podman inspect --format '{{.State.Running}}' marketlab-postgres) == true ]] &&
    podman exec marketlab-postgres pg_isready --username=marketlab_owner --dbname=marketlab >/dev/null; then
    backup_temporary=$(mktemp "$BACKUP_ROOT/.pre-${release_id}.XXXXXX.dump")
    chmod 0600 "$backup_temporary"
    podman exec marketlab-postgres \
        pg_dump --username=marketlab_owner --dbname=marketlab --format=custom --no-owner --no-privileges \
        >"$backup_temporary"
    backup_final="$BACKUP_ROOT/pre-${activation_id}.dump"
    mv -- "$backup_temporary" "$backup_final"
    sha256sum "$backup_final" >"$backup_final.sha256"
    chmod 0600 "$backup_final" "$backup_final.sha256"
    printf '%s\n' "$backup_final" >"$activation_directory/database-backup"
fi

install -m 0644 \
    "$activation_directory/rendered/quadlet/marketlab.network" \
    "$QUADLET_ROOT/marketlab.network"
install -m 0644 \
    "$activation_directory/rendered/quadlet/marketlab-postgres.container" \
    "$QUADLET_ROOT/marketlab-postgres.container"
install -m 0644 \
    "$activation_directory/rendered/quadlet/marketlab-api.container" \
    "$QUADLET_ROOT/marketlab-api.container"
install -m 0644 \
    "$activation_directory/rendered/quadlet/marketlab-coordinator.container" \
    "$QUADLET_ROOT/marketlab-coordinator.container"
if [[ "$collector_enabled" == true ]]; then
    install -m 0644 \
        "$activation_directory/rendered/quadlet/marketlab-collector.container" \
        "$QUADLET_ROOT/marketlab-collector.container"
fi
if [[ "$social_enabled" == true ]]; then
    install -m 0644 \
        "$activation_directory/rendered/quadlet/marketlab-social-collector.container" \
        "$QUADLET_ROOT/marketlab-social-collector.container"
    install -m 0644 \
        "$activation_directory/rendered/quadlet/marketlab-social-market.container" \
        "$QUADLET_ROOT/marketlab-social-market.container"
    install -m 0644 \
        "$activation_directory/rendered/quadlet/marketlab-sentiment-worker.container" \
        "$QUADLET_ROOT/marketlab-sentiment-worker.container"
else
    rm -f -- \
        "$QUADLET_ROOT/marketlab-social-collector.container" \
        "$QUADLET_ROOT/marketlab-social-market.container" \
        "$QUADLET_ROOT/marketlab-sentiment-worker.container"
fi
install -m 0644 \
    "$activation_directory/rendered/systemd/marketlab-runner.service" \
    "$USER_UNIT_ROOT/marketlab-runner.service"
install -m 0755 \
    "$activation_directory/rendered/bin/marketlab-research" \
    "$USER_BIN_ROOT/marketlab-research"
activation_installed=true

if systemctl --user is-active --quiet marketlab-collector.service ||
    podman container exists marketlab-collector; then
    systemctl --user stop marketlab-collector.service ||
        die "could not stop the existing collector cleanly"
fi
for social_service in marketlab-sentiment-worker.service marketlab-social-market.service marketlab-social-collector.service; do
    if systemctl --user is-active --quiet "$social_service"; then
        systemctl --user stop "$social_service" ||
            die "could not stop $social_service cleanly"
    fi
done
if podman container exists marketlab-collector &&
    [[ $(podman inspect --format '{{.State.Running}}' marketlab-collector) == true ]]; then
    die "existing collector container is still running after stop"
fi
if [[ "$collector_enabled" == false ]]; then
    rm -f -- "$QUADLET_ROOT/marketlab-collector.container"
fi
systemctl --user daemon-reload
systemctl --user start marketlab-network.service
systemctl --user stop marketlab-coordinator.service marketlab-api.service
systemctl --user restart marketlab-postgres.service
systemctl --user restart marketlab-runner.service

podman run \
    --rm \
    --name "marketlab-migrate-$release_id" \
    --network=marketlab \
    --read-only \
    --cap-drop=all \
    --security-opt=no-new-privileges \
    --userns=keep-id:uid=10001,gid=10001 \
    --pids-limit=256 \
    --cpus=2 \
    --memory=2g \
    --memory-swap=2g \
    --tmpfs=/tmp:rw,noexec,nosuid,nodev,size=512m \
    --env-file="$CONFIG_ROOT/marketlab/migrator.env" \
    --pull=never \
    --entrypoint=/opt/marketlab/bin/marketlab-migrate \
    "$coordinator_image"

{
    printf '%s\n' \
        "DO \$marketlab\$" \
        'BEGIN' \
        "    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'marketlab_api') THEN" \
        "        CREATE ROLE marketlab_api LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 32 PASSWORD '$api_password';" \
        '    END IF;' \
        "    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'marketlab_coordinator') THEN" \
        "        CREATE ROLE marketlab_coordinator LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 16 PASSWORD '$coordinator_password';" \
        '    END IF;' \
        'END' \
        "\$marketlab\$;" \
        "ALTER ROLE marketlab_api WITH LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 32 PASSWORD '$api_password';" \
        "ALTER ROLE marketlab_coordinator WITH LOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS CONNECTION LIMIT 16 PASSWORD '$coordinator_password';" \
        "ALTER ROLE marketlab_api SET search_path = pg_catalog, public;" \
        "ALTER ROLE marketlab_coordinator SET search_path = pg_catalog, public;"
    cat "$DEPLOY_ROOT/sql/least-privilege.sql"
} | podman exec --interactive marketlab-postgres \
    psql --no-psqlrc --set=ON_ERROR_STOP=1 --username=marketlab_owner --dbname=marketlab

systemctl --user restart marketlab-api.service
systemctl --user restart marketlab-coordinator.service
if [[ "$collector_enabled" == true ]]; then
    systemctl --user restart marketlab-collector.service
fi
if [[ "$social_enabled" == true ]]; then
    systemctl --user restart marketlab-social-collector.service
    systemctl --user restart marketlab-social-market.service
    systemctl --user restart marketlab-sentiment-worker.service
fi

"$DEPLOY_ROOT/verify.sh" "$release_id"

active_release_temporary=$(mktemp "$STATE_ROOT/.active-release.XXXXXX")
active_activation_temporary=$(mktemp "$STATE_ROOT/.active-activation.XXXXXX")
printf '%s\n' "$release_id" >"$active_release_temporary"
printf '%s\n' "$activation_directory" >"$active_activation_temporary"
mv -- "$active_release_temporary" "$STATE_ROOT/active-release"
mv -- "$active_activation_temporary" "$STATE_ROOT/active-activation"
activation_installed=false
printf 'Activated and verified release %s.\n' "$release_id"
printf 'Activation record: %s\n' "$activation_directory"
