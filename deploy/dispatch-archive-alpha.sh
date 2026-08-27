#!/usr/bin/env bash
set -euo pipefail

die() {
    printf 'archive-alpha dispatch: %s\n' "$*" >&2
    exit 1
}

[[ $# -eq 3 ]] || die "usage: $0 RELEASE_ID ALPHA_IMAGE SOURCE_ROOT"
release_id=$1
alpha_image=$2
source_root=$3
[[ "$release_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || die "invalid release id"
[[ "$alpha_image" =~ @sha256:[0-9a-f]{64}$ ]] || die "alpha image must be digest pinned"
[[ -x "$source_root/deploy/archive-alpha-controller.sh" ]] || die "controller is missing from source release"
lock="$source_root/research/alpha/campaigns/archive-directional-gpu-v2.lock.json"
[[ -f "$lock" ]] || die "v2 campaign lock is missing"

config_root=/mnt/stack/marketlab/config
unit_root="$HOME/.config/systemd/user"
environment_file="$config_root/archive-alpha-v2.env"
unit_file="$unit_root/marketlab-archive-alpha-v2.service"
install -d -m 0750 "$config_root" "$unit_root" \
    /mnt/media/marketlab/raw/archive-alpha-v2 \
    /mnt/media/marketlab/artifacts/archive-alpha-v2

if [[ -e "$environment_file" ]]; then
    die "campaign environment already exists; refusing to replace frozen dispatch identity"
fi
temporary_environment=$(mktemp "$config_root/.archive-alpha-v2.env.XXXXXX")
cat >"$temporary_environment" <<EOF
MARKETLAB_ALPHA_RELEASE=$release_id
MARKETLAB_ALPHA_IMAGE=$alpha_image
MARKETLAB_ALPHA_SOURCE_ROOT=$source_root
MARKETLAB_ALPHA_DATA_ROOT=/mnt/media/marketlab/raw/archive-alpha-v2
MARKETLAB_ALPHA_ARTIFACT_ROOT=/mnt/media/marketlab/artifacts/archive-alpha-v2
MARKETLAB_ALPHA_LOCK=$lock
EOF
chmod 0440 "$temporary_environment"
mv "$temporary_environment" "$environment_file"

temporary_unit=$(mktemp "$unit_root/.marketlab-archive-alpha-v2.service.XXXXXX")
cat >"$temporary_unit" <<EOF
[Unit]
Description=Marketlab archive directional GPU v2 campaign
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=3600
StartLimitBurst=3

[Service]
Type=simple
EnvironmentFile=$environment_file
ExecStart=$source_root/deploy/archive-alpha-controller.sh $environment_file
Restart=on-failure
RestartPreventExitStatus=75
RestartSec=30
TimeoutStopSec=90

[Install]
WantedBy=default.target
EOF
chmod 0644 "$temporary_unit"
mv "$temporary_unit" "$unit_file"
systemctl --user daemon-reload
systemctl --user enable --now marketlab-archive-alpha-v2.service
printf '%s\n' "dispatched marketlab-archive-alpha-v2.service"
