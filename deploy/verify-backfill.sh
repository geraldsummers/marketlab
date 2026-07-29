#!/usr/bin/env bash
set -euo pipefail

readonly RELEASE_ROOT=/mnt/stack/marketlab/releases
readonly CONFIG_ROOT="${XDG_CONFIG_HOME:-$HOME/.config}"
readonly USER_UNIT_ROOT="$CONFIG_ROOT/systemd/user"
readonly STORAGE_CONFIG="$CONFIG_ROOT/marketlab/storage.conf"
readonly OUTPUT_ROOT=/mnt/media/marketlab/raw/social-backfill-v2

die() {
    printf 'verify-backfill: %s\n' "$*" >&2
    exit 1
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

for command_name in awk grep jq podman sha256sum sleep systemctl; do
    command -v "$command_name" >/dev/null 2>&1 ||
        die "required command is unavailable: $command_name"
done

[[ $(id -u) -ne 0 ]] || die "verification must run as the rootless Podman user"
[[ -f "$STORAGE_CONFIG" && ! -L "$STORAGE_CONFIG" ]] ||
    die "Marketlab Podman storage configuration is missing"
export CONTAINERS_STORAGE_CONF="$STORAGE_CONFIG"

release_id=${1:-}
[[ "$release_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || die "invalid release id"
release_manifest="$RELEASE_ROOT/$release_id/images.env"
[[ -f "$release_manifest" ]] || die "release manifest is missing"
backfill_image=$(release_value SOCIAL_BACKFILL_IMAGE)
[[ $(release_value BACKFILL_PROGRAM_SCHEMA) == marketlab.social-backfill-program-lock.v2 ]] ||
    die "release does not declare the v2 study schema"
[[ "$backfill_image" =~ ^[a-z0-9.-]+/[a-z0-9._/-]+@sha256:[0-9a-f]{64}$ ]] ||
    die "backfill image is not digest pinned"

for unit_name in market social-a social-b analysis; do
    unit="marketlab-backfill-$unit_name.service"
    unit_file="$USER_UNIT_ROOT/$unit"
    [[ -f "$unit_file" && ! -L "$unit_file" ]] || die "unit is missing: $unit"
    systemctl --user is-enabled --quiet "$unit" || die "unit is not enabled: $unit"
    grep -Fq -- "$backfill_image" "$unit_file" || die "$unit does not use the release image"
    grep -Fq -- "$OUTPUT_ROOT" "$unit_file" || die "$unit does not use the v2 evidence root"
    grep -Fq -- 'Restart=on-failure' "$unit_file" || die "$unit is not restartable"
done
grep -Fq -- '--network=none' "$USER_UNIT_ROOT/marketlab-backfill-analysis.service" ||
    die "analysis is not network isolated"
if grep -R -Fq -- '/mnt/media/marketlab/raw/social-backfill ' \
    "$USER_UNIT_ROOT"/marketlab-backfill-*.service; then
    die "a managed backfill unit still references the v1 evidence root"
fi

frozen_lock="$OUTPUT_ROOT/program-lock.json"
for _ in {1..30}; do
    [[ -f "$frozen_lock" && ! -L "$frozen_lock" ]] && break
    sleep 1
done
[[ -f "$frozen_lock" && ! -L "$frozen_lock" ]] ||
    die "workers did not freeze the registered program lock"
jq -e \
    '.schemaVersion == "marketlab.social-backfill-program-lock.v2"
     and .sources.social.provider == "bluesky-public-appview-search"
     and (.sources.social | has("farcaster") | not)' \
    "$frozen_lock" >/dev/null ||
    die "frozen program lock is not the reviewed no-Farcaster v2 study"

expected_lock_hash=$(
    podman run --rm --network=none --entrypoint=/usr/bin/sha256sum \
        "$backfill_image" /opt/marketlab/research/social-backfill-program.lock.json |
        awk '{print $1}'
)
actual_lock_hash=$(sha256sum "$frozen_lock" | awk '{print $1}')
[[ "$actual_lock_hash" == "$expected_lock_hash" ]] ||
    die "frozen program lock differs from the release image"

printf 'Verified persistent v2 acquisition and offline-analysis units for release %s.\n' "$release_id"
