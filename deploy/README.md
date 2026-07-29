# Gerald deployment

This deployment is rootless, content-pinned, and intentionally private. The
HTTP API is published only at `127.0.0.1:8080`; reach it through SSH. Neither
the API, coordinator, continuous collector, research CLI, nor workers receive a
Podman socket.

No deployment is performed merely by building this repository. Activation is a
separate, explicit step.

## Host layout

Hot state is kept on `/mnt/stack`:

- `/mnt/stack/marketlab/postgres`
- `/mnt/stack/marketlab/normalized`
- `/mnt/stack/marketlab/jobs`
- `/mnt/stack/marketlab/cache`
- `/mnt/stack/marketlab/scratch`
- `/mnt/stack/marketlab/active-artifacts`
- `/mnt/stack/marketlab/podman-storage`
- `/mnt/stack/marketlab/releases`
- `/mnt/stack/marketlab/source-releases`
- `/mnt/stack/marketlab/models`
- `/mnt/stack/marketlab/social-features`
- `/mnt/stack/marketlab/social-universe`

Cold or append-heavy state is kept on `/mnt/media`:

- `/mnt/media/marketlab/raw`
- `/mnt/media/marketlab/raw/hyperliquid-stream`
- `/mnt/media/marketlab/raw/public-information`
- `/mnt/media/marketlab/raw/social-market`
- `/mnt/media/marketlab/cold-artifacts`
- `/mnt/media/marketlab/backups`

Marketlab uses its own rootless Podman `storage.conf`, with `graphroot` on the
hot tier. This avoids consuming Gerald's comparatively constrained OS
filesystem and does not disturb unrelated Podman workloads.

Secrets and activation state stay under the deployment user's home:

- `~/.config/marketlab/*.env`, mode `0600`
- `~/.config/marketlab/storage.conf`, mode `0600`
- `~/.local/state/marketlab`
- `~/.config/containers/systemd`
- `~/.config/systemd/user/marketlab-runner.service`

## Images and trust boundary

`images.lock` contains reviewed Linux/amd64 manifest digests for the JDK, JRE,
and PostgreSQL base images. Containerfiles have no mutable default base. A
release build records the exact source-tree SHA-256 and addresses every locally
built image by its manifest digest.

The runner is a host-side Kotlin allowlist service. It needs the rootless
`newuidmap`/`newgidmap` helpers, so its host unit cannot set
`NoNewPrivileges=true`. It is otherwise sandboxed and binds only
`127.0.0.1:8081`. Every worker it launches is read-only, capability-free,
network-free, digest-pinned, and explicitly receives `no-new-privileges`.

Database identities are separated. `marketlab_owner` is available only to the
PostgreSQL container and a short-lived, socket-free migration container.
Long-running processes use distinct `marketlab_api` and
`marketlab_coordinator` logins, migrations are disabled in both, and neither
role owns tables, functions, triggers, or DDL privileges. Grants are applied
after every migration. API access to `paper_events` is append-only; immutable
market-data objects can be inserted only by the coordinator and cannot be
updated or deleted by either application role.

The trusted, digest-pinned research CLI is different: it needs outbound network
access to retrieve real Hyperliquid mainnet observations. Its wrapper uses
rootless slirp networking with host-loopback access disabled, writable cold raw
storage, and writable hot artifact storage. It has no Podman socket.

The continuous collector is one BTC-only, outbound-networked rootless
container. It receives no API token or database credential, publishes no port,
has a read-only root filesystem, and can write only
`/mnt/media/marketlab/raw/hyperliquid-stream`. Trades, BBO, and L2 frames share
a bounded queue and are batched into at most 128 MiB or 15-minute segments.
Each segment contains exact base64-encoded wire bytes and their SHA-256,
exchange/receive/availability clocks, connection records, exact server
subscription acknowledgements, and reconnect quality signals. Completed bytes
are fsynced and atomically hard-linked into content-addressed `objects/`; an
immutable content-addressed manifest and date-partitioned append-only `index/`
entry follow. A crash can leave an ignored `.partial` file or an unreferenced
completed object, but never an index reference to partial bytes. No cleanup
script deletes these files. A replaceable `.collector-ready.json` operational
marker is published only after all three current-process subscription
acknowledgements have been appended and forced to storage; deployment
verification rejects a stale marker or a restart during readiness.

The prospective information program adds three rootless units. The v2 social
collector captures public Bluesky, Nostr, GDELT, RSS, and Mastodon
observations; Farcaster is explicitly disabled. The social-market collector
recomputes the point-in-time Hyperliquid top ten and captures trades/BBO/L2.
The sentiment worker verifies fixed model hashes before scoring and
materializing causal features. Farcaster remains an opt-in adapter for a
separately preregistered future program.

The ONNX files and tokenizers are excluded from source archives and images.
Install them under `/mnt/stack/marketlab/models/{social,news}` and copy
`research/sentiment-models.lock.json` to
`/mnt/stack/marketlab/models/sentiment-models.lock.json`; activation verifies
all four artifact hashes.

## Stage the source

Run from the repository on the workstation after the source is frozen. Use a
new release name; never reuse a name for different content.

```sh
release="marketlab-$(date -u +%Y%m%dT%H%M%SZ)"
ssh gerald@192.168.0.11 \
  "install -d -m 0750 /mnt/stack/marketlab/source-releases/$release"
rsync -a \
  --exclude .git \
  --exclude .gradle \
  --exclude .kotlin \
  --exclude '*/build' \
  --exclude .testdata \
  --exclude .venv \
  --exclude .tmp \
  --exclude runtime-data \
  --exclude artifacts \
  --exclude dist \
  ./ "gerald@192.168.0.11:/mnt/stack/marketlab/source-releases/$release/"
```

The timestamp makes the source directory append-only in normal operation.
`build-release.sh` independently hashes its source and rejects a reused release
id whose content differs.

## Prepare, build, and activate

Preparation is idempotent. It creates missing directories and secrets, never
overwrites an existing environment file, and checks database-password
consistency.

```sh
ssh gerald@192.168.0.11 \
  "cd /mnt/stack/marketlab/source-releases/$release && deploy/prepare-host.sh"
ssh gerald@192.168.0.11 \
  "cd /mnt/stack/marketlab/source-releases/$release && deploy/build-release.sh $release"
```

Inspect the immutable release manifest before activation:

```sh
ssh gerald@192.168.0.11 \
  "sed -n '1,40p' /mnt/stack/marketlab/releases/$release/images.env"
```

Activation takes a PostgreSQL custom-format backup when an existing healthy
database is present, validates Quadlets, runs Flyway in a one-shot owner
container, reapplies least-privilege grants, installs the long-running units,
starts the BTC collector, waits up to 30 seconds for current-process
acknowledgements from all three mainnet streams, and runs the security
verification. It restores the prior unit files automatically if startup or
verification fails.

Collector shutdown has three ordered bounds: the JVM waits at most 50 seconds
for queue draining and segment finalization, Quadlet gives the container 60
seconds before forced termination, and systemd gives the complete stop command
75 seconds. This keeps Podman's stop deadline below the unit deadline as
required by Quadlet.

```sh
ssh gerald@192.168.0.11 \
  "cd /mnt/stack/marketlab/source-releases/$release && deploy/activate-release.sh $release"
```

Do not combine source staging and activation into an unattended command. The
manifest review is the intentional approval boundary.

## Verify and connect

```sh
ssh gerald@192.168.0.11 \
  "cd /mnt/stack/marketlab/source-releases/$release && deploy/verify.sh $release"
ssh -N -L 8080:127.0.0.1:8080 gerald@192.168.0.11
```

Then use `http://127.0.0.1:8080`. The bearer token is in
`~/.config/marketlab/api.env` on Gerald and is never printed by the deployment
scripts.

The verification checks service and container health, image identities,
digest-only references, loopback-only publication, hot/cold mounts, environment
file modes, source revision propagation, and absence of container-engine socket
mounts. Database checks also prove that application roles have no DDL, object
ownership, trigger-function execution, evidence UPDATE/DELETE, shared owner
credential, or permission to run Flyway. Collector checks additionally enforce
the single BTC scope, fixed memory/file bounds, digest-pinned image, automatic
rootless restart, read-only root, no ports or credentials, and its single
narrow writable raw-data mount.

## Run the real-data research CLI

After activation:

```sh
ssh gerald@192.168.0.11 \
  '~/.local/bin/marketlab-research --start 2026-01-01T00:00:00Z --end 2026-07-01T00:00:00Z --coins BTC,ETH'
```

The wrapper injects the release's 64-character source SHA-256. Do not pass
`--allow-unversioned` for production evidence.

## Run the retrospective social backfill

The separately registered backfill is discovery-only. Run its digest-pinned
image with no credentials, a read-only model mount, and a single writable
backfill root. The command is resumable at completed source-day and market-asset
boundaries. `--assets` can safely shard disjoint symbols across containers.
Bluesky capture uses bounded UTC search windows because the public endpoint
rejects cursor pagination; it is historical search capture, not a claim of
complete archive coverage.

```sh
backfill_image=$(sed -n 's/^SOCIAL_BACKFILL_IMAGE=//p' \
  /mnt/stack/marketlab/releases/$release/images.env)
podman run --rm \
  --network=slirp4netns:allow_host_loopback=false \
  --read-only --cap-drop=all --security-opt=no-new-privileges \
  --userns=keep-id:uid=10001,gid=10001 \
  --cpus=20 --memory=49152m --memory-swap=49152m \
  --tmpfs=/tmp:rw,exec,nosuid,nodev,size=8g \
  --mount=type=bind,src=/mnt/media/marketlab/raw/social-backfill,dst=/mnt/media/marketlab/raw/social-backfill,rw=true,relabel=shared \
  --mount=type=bind,src=/mnt/stack/marketlab/models,dst=/mnt/stack/marketlab/models,ro=true,relabel=shared \
  "$backfill_image" \
  --output-root /mnt/media/marketlab/raw/social-backfill \
  --models-root /mnt/stack/marketlab/models \
  --model-lock /opt/marketlab/research/sentiment-models.lock.json \
  --program-lock /opt/marketlab/research/social-backfill-program.lock.json \
  --start 2025-10-04T00:00:00Z \
  --end 2026-07-01T00:00:00Z \
  --sources market,social
```

After all ten market manifests and all 2,700 social source-day manifests exist,
run the frozen retrospective analysis without network access:

```sh
podman run --rm \
  --network=none \
  --read-only --cap-drop=all --security-opt=no-new-privileges \
  --userns=keep-id:uid=10001,gid=10001 \
  --tmpfs=/tmp:rw,exec,nosuid,nodev,size=2g \
  --mount=type=bind,src=/mnt/media/marketlab/raw/social-backfill,dst=/mnt/media/marketlab/raw/social-backfill,rw=true,relabel=shared \
  --mount=type=bind,src=/mnt/stack/marketlab/models,dst=/mnt/stack/marketlab/models,ro=true,relabel=shared \
  "$backfill_image" \
  --output-root /mnt/media/marketlab/raw/social-backfill \
  --models-root /mnt/stack/marketlab/models \
  --model-lock /opt/marketlab/research/sentiment-models.lock.json \
  --program-lock /opt/marketlab/research/social-backfill-program.lock.json \
  --analysis-lock /opt/marketlab/research/social-backfill-analysis.lock.json \
  --start 2025-10-04T00:00:00Z \
  --end 2026-07-01T00:00:00Z \
  --sources analysis
```

## Roll back

Roll back to the immediate predecessor:

```sh
ssh gerald@192.168.0.11 \
  "cd /mnt/stack/marketlab/source-releases/$release && deploy/rollback.sh"
```

Or select a retained release explicitly:

```sh
ssh gerald@192.168.0.11 \
  "cd /mnt/stack/marketlab/source-releases/$release && deploy/rollback.sh OLD_RELEASE_ID"
```

Rollback switches the application images and runner distribution and retains a
fresh pre-switch database backup. It never restores PostgreSQL automatically:
database restoration is destructive and must be a separate, explicit operator
decision after checking migration compatibility. Rolling back to a release
that predates the collector stops and removes only its unit; already captured
raw segments are retained.

No script prunes images, releases, raw observations, artifacts, or backups.
