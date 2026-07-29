#!/usr/bin/env bash
set -euo pipefail

umask 027

REPOSITORY_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)
readonly REPOSITORY_ROOT
readonly DEPLOY_ROOT="$REPOSITORY_ROOT/deploy"
readonly RELEASE_ROOT=/mnt/stack/marketlab/releases
readonly STATE_ROOT="${XDG_STATE_HOME:-$HOME/.local/state}/marketlab"
readonly STORAGE_CONFIG="${XDG_CONFIG_HOME:-$HOME/.config}/marketlab/storage.conf"

die() {
    printf 'build-release: %s\n' "$*" >&2
    exit 1
}

require_command() {
    command -v "$1" >/dev/null 2>&1 || die "required command is unavailable: $1"
}

locked_value() {
    local key=$1
    awk -v wanted="$key" '
        index($0, wanted "=") == 1 {
            print substr($0, length(wanted) + 2)
            found = 1
        }
        END { if (!found) exit 1 }
    ' "$DEPLOY_ROOT/images.lock"
}

source_digest() {
    tar \
        --sort=name \
        --mtime=@0 \
        --owner=0 \
        --group=0 \
        --numeric-owner \
        --mode='a=rX,u+w,a-s' \
        --exclude='./.git' \
        --exclude='./.gradle' \
        --exclude='./.kotlin' \
        --exclude='./.idea' \
        --exclude='./.testdata' \
        --exclude='./.venv' \
        --exclude='./.tmp' \
        --exclude='./runtime-data' \
        --exclude='./artifacts' \
        --exclude='./dist' \
        --exclude='./build' \
        --exclude='./*/build' \
        -C "$REPOSITORY_ROOT" \
        -cf - . |
        sha256sum |
        awk '{print $1}'
}

assert_digest_reference() {
    local name=$1
    local reference=$2
    [[ "$reference" =~ ^[a-z0-9.-]+/[a-z0-9._/-]+@sha256:[0-9a-f]{64}$ ]] ||
        die "$name is not an immutable sha256 image reference"
}

for command_name in awk chmod find flock install mktemp mv podman sha256sum sort tar xargs; do
    require_command "$command_name"
done

[[ $(id -u) -ne 0 ]] || die "run as the rootless Podman user, never as root"
[[ -f "$STORAGE_CONFIG" && ! -L "$STORAGE_CONFIG" ]] ||
    die "run deploy/prepare-host.sh before building a release"
export CONTAINERS_STORAGE_CONF="$STORAGE_CONFIG"
[[ $(podman info --format '{{.Host.Security.Rootless}}') == true ]] ||
    die "Podman is not running rootless"
[[ -f "$DEPLOY_ROOT/images.lock" ]] || die "deploy/images.lock is missing"
[[ -d "$RELEASE_ROOT" && -w "$RELEASE_ROOT" ]] ||
    die "run deploy/prepare-host.sh before building a release"

builder_image=$(locked_value BUILDER_IMAGE)
runtime_image=$(locked_value RUNTIME_IMAGE)
postgres_image=$(locked_value POSTGRES_IMAGE)
assert_digest_reference BUILDER_IMAGE "$builder_image"
assert_digest_reference RUNTIME_IMAGE "$runtime_image"
assert_digest_reference POSTGRES_IMAGE "$postgres_image"

initial_source_digest=$(source_digest)
release_id=${1:-"src-${initial_source_digest:0:16}"}
[[ "$release_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] ||
    die "release id must use 1-64 portable filename characters"

install -d -m 0700 "$STATE_ROOT"
exec 9>"$STATE_ROOT/build.lock"
flock 9

release_directory="$RELEASE_ROOT/$release_id"
if [[ -e "$release_directory" ]]; then
    [[ -f "$release_directory/images.env" ]] ||
        die "release directory already exists but is incomplete: $release_directory"
    existing_digest=$(awk -F= '
        $1 == "SOURCE_SHA256" { print $2; found = 1 }
        END { if (!found) exit 1 }
    ' "$release_directory/images.env") ||
        die "existing release has no source digest"
    [[ "$existing_digest" == "$initial_source_digest" ]] ||
        die "release id already belongs to different source content"
    for required in runner/bin/runner images.env runner.sha256; do
        [[ -e "$release_directory/$required" ]] ||
            die "existing release is missing $required"
    done
    printf 'Release %s is already built from source %s.\n' "$release_id" "$initial_source_digest"
    exit 0
fi

anonymous_authfile=$(mktemp "$STATE_ROOT/anonymous-auth.XXXXXX")
chmod 0600 "$anonymous_authfile"
printf '{"auths":{}}\n' >"$anonymous_authfile"
runner_container=

cleanup_resources() {
    if [[ -n "$runner_container" ]]; then
        podman rm --force "$runner_container" >/dev/null 2>&1 || true
    fi
    rm -f -- "$anonymous_authfile"
}
trap cleanup_resources EXIT

for immutable_base in "$builder_image" "$runtime_image" "$postgres_image"; do
    podman pull \
        --quiet \
        --platform=linux/amd64 \
        --authfile "$anonymous_authfile" \
        "$immutable_base" >/dev/null ||
        die "cannot pull pinned linux/amd64 base image $immutable_base"
    podman image exists "$immutable_base" ||
        die "pinned base image is absent after pull: $immutable_base"
done

build_directory=$(mktemp -d "$RELEASE_ROOT/.build-${release_id}.XXXXXX")
chmod 0750 "$build_directory"

build_image() {
    local component=$1
    local containerfile=$2
    local tag="localhost/marketlab-${component}:${release_id}"
    local digest
    local pinned

    printf 'Building %s from digest-pinned base images...\n' "$component" >&2
    podman build \
        --pull=always \
        --platform=linux/amd64 \
        --authfile "$anonymous_authfile" \
        --build-arg "BUILDER_IMAGE=$builder_image" \
        --build-arg "RUNTIME_IMAGE=$runtime_image" \
        --build-arg "BUILD_REVISION=$initial_source_digest" \
        --label "dev.marketlab.release=$release_id" \
        --label "dev.marketlab.source-sha256=$initial_source_digest" \
        --ignorefile "$DEPLOY_ROOT/container.ignore" \
        --file "$DEPLOY_ROOT/$containerfile" \
        --tag "$tag" \
        "$REPOSITORY_ROOT" >&2
    digest=$(podman image inspect --format '{{.Digest}}' "$tag")
    [[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]] ||
        die "Podman did not report an immutable manifest digest for $tag"
    pinned="${tag%:*}@$digest"
    podman image exists "$pinned" ||
        die "locally built image is not addressable by digest: $pinned"
    printf '%s\n' "$pinned"
}

service_image=$(build_image service Containerfile.service)
worker_image=$(build_image worker Containerfile.worker-kotlin)
coordinator_image=$(build_image coordinator Containerfile.coordinator)
collector_image=$(build_image collector Containerfile.collector)
social_collector_image=$(build_image social-collector Containerfile.social-collector)
sentiment_worker_image=$(build_image sentiment-worker Containerfile.sentiment-worker)
social_backfill_image=$(build_image social-backfill Containerfile.social-backfill)
research_image=$(build_image research Containerfile.research-cli)
runner_image=$(build_image runner Containerfile.runner)

runner_container="marketlab-runner-extract-${release_id}"
podman create --name "$runner_container" "$runner_image" >/dev/null
install -d -m 0750 "$build_directory/runner"
podman cp "$runner_container:/opt/marketlab/." "$build_directory/runner/"
podman rm "$runner_container" >/dev/null
runner_container=
[[ -x "$build_directory/runner/bin/runner" ]] ||
    die "runner distribution did not contain an executable launcher"

(
    cd "$build_directory/runner"
    find . -type f -print0 |
        sort -z |
        xargs -0 sha256sum >"$build_directory/runner.sha256"
)

cat >"$build_directory/images.env" <<EOF
RELEASE_ID=$release_id
SOURCE_SHA256=$initial_source_digest
BUILDER_IMAGE=$builder_image
RUNTIME_IMAGE=$runtime_image
POSTGRES_IMAGE=$postgres_image
SERVICE_IMAGE=$service_image
WORKER_IMAGE=$worker_image
COORDINATOR_IMAGE=$coordinator_image
COLLECTOR_IMAGE=$collector_image
SOCIAL_COLLECTOR_IMAGE=$social_collector_image
SENTIMENT_WORKER_IMAGE=$sentiment_worker_image
SOCIAL_BACKFILL_IMAGE=$social_backfill_image
BACKFILL_PROGRAM_SCHEMA=marketlab.social-backfill-program-lock.v2
RESEARCH_IMAGE=$research_image
RUNNER_IMAGE=$runner_image
EOF
chmod 0440 "$build_directory/images.env" "$build_directory/runner.sha256"

final_source_digest=$(source_digest)
[[ "$final_source_digest" == "$initial_source_digest" ]] ||
    die "source changed during the build; refusing to publish a mixed release"

mv -- "$build_directory" "$release_directory"
printf 'Built immutable release %s from source %s.\n' "$release_id" "$initial_source_digest"
printf 'Release manifest: %s/images.env\n' "$release_directory"
