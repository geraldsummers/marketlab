#!/usr/bin/env bash
set -euo pipefail

SCRIPT_ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)
readonly SCRIPT_ROOT
readonly STATE_ROOT="${XDG_STATE_HOME:-$HOME/.local/state}/marketlab"
readonly RELEASE_ROOT=/mnt/stack/marketlab/releases

die() {
    printf 'rollback: %s\n' "$*" >&2
    exit 1
}

[[ -f "$STATE_ROOT/active-release" ]] || die "there is no active release"
current_release=$(<"$STATE_ROOT/active-release")
target_release=${1:-}

if [[ -z "$target_release" ]]; then
    [[ -f "$STATE_ROOT/active-activation" ]] ||
        die "the active activation record is missing; pass a release id explicitly"
    active_activation=$(<"$STATE_ROOT/active-activation")
    [[ "$active_activation" == "$STATE_ROOT/activations/"* ]] ||
        die "the active activation record points outside the state directory"
    [[ -d "$active_activation" && ! -L "$active_activation" ]] ||
        die "the active activation record is invalid"
    [[ -f "$active_activation/previous-release" ]] ||
        die "the initial deployment has no previous release"
    target_release=$(<"$active_activation/previous-release")
fi

[[ "$target_release" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] ||
    die "invalid target release id"
[[ "$target_release" != "$current_release" ]] ||
    die "target release is already active"
target_manifest="$RELEASE_ROOT/$target_release/images.env"
[[ -f "$target_manifest" ]] ||
    die "target release is not available: $target_release"

printf 'Rolling back application units from %s to %s.\n' "$current_release" "$target_release"
if grep -q '^COLLECTOR_IMAGE=' "$target_manifest"; then
    printf 'The target release includes the digest-pinned BTC stream collector.\n'
else
    printf 'The target predates stream capture; activation will stop and remove the collector unit without deleting raw data.\n'
fi
printf 'A fresh pre-activation PostgreSQL backup will be retained; database files are never auto-restored.\n'
"$SCRIPT_ROOT/activate-release.sh" "$target_release"
