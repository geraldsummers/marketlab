# Social functional model: blind-extension continuation runbook

This document is the durable handoff for continuing the functional social-attention
forecast study after the model search completed. It records the state that must remain
frozen, the one-time blind-opening procedure, and the decisions permitted afterward.

## Current state

- Git branch: `agent/modularize-marketlab`
- Valid implementation commit and release: `5f9b1b0`
- Release ID: `marketlab-social-functional-5f9b1b0`
- Release source SHA-256:
  `44bc753d773915630e5cda8e5ee7049c0d74aa4af7c8e0edcf4f49567d7e51f2`
- Trainer SHA-256:
  `36db1ce577481631234ba5d699cd5a211360dbc814b48e4264651c1f7eab9653`
- Development feature rows SHA-256:
  `f10279e39bb6a03d1a5cbeb3d1ef90a87080efcdd7dd597a7db6d8f60f0a8372`
- Frozen model-set SHA-256:
  `70d73e2f3c12d05b0d31cc5460b6b97fd113e692f85e61f698a8835b009fb983`
- Frozen model directory on `gerald@192.168.0.11`:
  `/mnt/media/marketlab/raw/social-backfill-v2/functional/models/marketlab-social-functional-5f9b1b0`
- Frozen manifest:
  `frozen-models.json` in that directory; `frozen-models.sha256` contains the same
  externally recorded hash.
- Blind acquisition root has not been created or inspected. Use only:
  `/mnt/media/marketlab/raw/social-functional-blind-extension-2026-07`

The first training release, `marketlab-social-functional-379e017`, failed during a
full-batch GRU fit with CUDA OOM and published no model artifacts. Do not resume or use
that run. Commit `5f9b1b0` bounds neural activation memory with deterministic 4,096-row
micro-batches and gradient accumulation. Its corrected 40-trial run completed
successfully.

## Development result (not confirmation)

The frozen chronological outer-fold audit selected:

| Target | Winner | Positive outer folds | Engineering gate |
| --- | --- | ---: | --- |
| 15-minute return | Elastic net | 2/6 | Fail |
| 1-hour realized variance | Gradient-boosted trees | 6/6 | Pass |
| 1-day return | Elastic net | 2/6 | Fail |
| 1-day realized variance | Gradient-boosted trees | 5/6 | Pass |

This is retrospective evidence for volatility forecasting, not a confirmed forecasting
or trading claim. The model set remains classified `RETROSPECTIVE_EXPLORATION`.

## Non-negotiable blind boundary

Before any acquisition, verify the frozen manifest hash directly:

```sh
model_root=/mnt/media/marketlab/raw/social-backfill-v2/functional/models/marketlab-social-functional-5f9b1b0
sha256sum "$model_root/frozen-models.json"
cat "$model_root/frozen-models.sha256"
```

Both values must equal the frozen hash recorded above. Also verify that the blind root
does not exist. Once acquisition begins, July is permanently opened; deleting files does
not restore blindness. Do not change models, features, locks, search decisions, baseline
definitions, or acceptance thresholds after this point.

Use the lock embedded in the valid digest-pinned release:
`/opt/marketlab/research/social-functional-blind-extension.lock.json`. It registers:

- acquisition interval `[2026-06-01T00:00:00Z, 2026-08-01T00:00:00Z)`;
- June as 30-day feature warm-up only;
- July as the one-time evaluation interval;
- the fixed ten-asset universe, Bluesky queries, match terms, and Binance months;
- no paper-trading or live-trading authorization.

## One-time acquisition

Run on `gerald@192.168.0.11`. Read the digest-pinned image from the release manifest;
never substitute a tag.

```sh
release=/mnt/stack/marketlab/releases/marketlab-social-functional-5f9b1b0
source_release=/mnt/stack/marketlab/source-releases/marketlab-social-functional-5f9b1b0
blind_root=/mnt/media/marketlab/raw/social-functional-blind-extension-2026-07
. "$release/images.env"

test ! -e "$blind_root"
install -d -m 0750 "$blind_root"
```

Acquire Binance market evidence first. Then run the two social shards; they may run in
parallel because they own disjoint assets. All commands are restart-safe at completed
manifest boundaries.

```sh
podman run --rm --replace --name marketlab-functional-blind-market \
  --network=slirp4netns:allow_host_loopback=false \
  --read-only --cap-drop=all --security-opt=no-new-privileges \
  --userns=keep-id:uid=10001,gid=10001 --cpus=4 --memory=8192m \
  --tmpfs=/tmp:rw,exec,nosuid,nodev,size=2g \
  --mount=type=bind,src="$blind_root",dst="$blind_root",rw=true,relabel=shared \
  "$SOCIAL_BACKFILL_IMAGE" \
  --output-root "$blind_root" \
  --models-root /mnt/stack/marketlab/models \
  --model-lock /opt/marketlab/research/sentiment-models.lock.json \
  --program-lock /opt/marketlab/research/social-functional-blind-extension.lock.json \
  --start 2026-06-01T00:00:00Z --end 2026-08-01T00:00:00Z \
  --sources market
```

Run the fixed social shards in parallel:

```sh
run_social_shard() {
  shard_name=$1
  shard_assets=$2
  podman run --rm --replace --name "marketlab-functional-blind-social-$shard_name" \
    --network=slirp4netns:allow_host_loopback=false \
    --read-only --cap-drop=all --security-opt=no-new-privileges \
    --userns=keep-id:uid=10001,gid=10001 --cpus=10 --memory=24576m \
    --tmpfs=/tmp:rw,exec,nosuid,nodev,size=4g \
    --mount=type=bind,src="$blind_root",dst="$blind_root",rw=true,relabel=shared \
    --mount=type=bind,src=/mnt/stack/marketlab/models,dst=/mnt/stack/marketlab/models,ro=true,relabel=shared \
    "$SOCIAL_BACKFILL_IMAGE" \
    --output-root "$blind_root" \
    --models-root /mnt/stack/marketlab/models \
    --model-lock /opt/marketlab/research/sentiment-models.lock.json \
    --program-lock /opt/marketlab/research/social-functional-blind-extension.lock.json \
    --start 2026-06-01T00:00:00Z --end 2026-08-01T00:00:00Z \
    --sources social --assets "$shard_assets"
}

run_social_shard a BTC,HYPE,NEAR,PUMP,SOL &
social_a_pid=$!
run_social_shard b ETH,LIT,WLD,XRP,ZEC &
social_b_pid=$!
wait "$social_a_pid"
wait "$social_b_pid"
```

Do not run analysis during acquisition.

## Completeness and feature materialization

Do not evaluate until all ten market manifests and all 610 social day manifests exist.
The feature materializer independently verifies each manifest's referenced object hash
and fails closed on missing evidence, schema drift, or a changed feature lock.

```sh
release=/mnt/stack/marketlab/releases/marketlab-social-functional-5f9b1b0
blind_root=/mnt/media/marketlab/raw/social-functional-blind-extension-2026-07
. "$release/images.env"

test "$(find "$blind_root/market/manifests" -type f -name '*.json' | wc -l)" -eq 10
test "$(find "$blind_root/social/manifests" -type f -name '*.json' | wc -l)" -eq 610

podman run --rm --name marketlab-functional-blind-features \
  --network=none --read-only --userns=keep-id:uid=10001,gid=10001 \
  --cpus=20 --memory=32g --pids-limit=2048 \
  --tmpfs=/tmp:rw,nosuid,nodev,noexec,size=4g \
  --mount=type=bind,src="$blind_root",dst="$blind_root",rw=true,relabel=shared \
  "$SOCIAL_BACKFILL_IMAGE" functional-features \
  --output-root "$blind_root" \
  --program-lock "$blind_root/program-lock.json" \
  --feature-lock /opt/marketlab/research/social-functional-feature.lock.json \
  --start 2026-06-01T00:00:00Z --end 2026-08-01T00:00:00Z
```

Inspect `functional/features/manifest.json`. It must identify July 1 as
`featureStartInclusive`, August 1 as `endExclusive`, all ten symbols, and an immutable
content-addressed `outputUri`. Recompute the feature-object hash and require it to equal
`outputSha256` before evaluation.

## One-time evaluation

Use the Python environment at `/home/gerald/.venv/marketlab-social-model` and the trainer
from the valid source release. Substitute only the content-addressed feature object path
read from the feature manifest. The output path must not already exist; the evaluator
uses exclusive creation to prevent overwrite.

```sh
source_release=/mnt/stack/marketlab/source-releases/marketlab-social-functional-5f9b1b0
blind_root=/mnt/media/marketlab/raw/social-functional-blind-extension-2026-07
model_root=/mnt/media/marketlab/raw/social-backfill-v2/functional/models/marketlab-social-functional-5f9b1b0
trainer="$source_release/social-model/trainer.py"
search_lock="$source_release/research/social-functional-search.lock.json"
frozen="$model_root/frozen-models.json"
feature_manifest="$blind_root/functional/features/manifest.json"
object_uri=$(jq -r '.outputUri' "$feature_manifest")
case "$object_uri" in objects/*) ;; *) echo "invalid feature object URI" >&2; exit 1 ;; esac
blind_features="$blind_root/$object_uri"
report="$blind_root/functional/blind-evaluation.json"

test "$(sha256sum "$blind_features" | awk '{print $1}')" = \
  "$(jq -r '.outputSha256' "$feature_manifest")"

/home/gerald/.venv/marketlab-social-model/bin/python "$trainer" evaluate \
  --features "$blind_features" \
  --frozen "$frozen" \
  --frozen-sha256 70d73e2f3c12d05b0d31cc5460b6b97fd113e692f85e61f698a8835b009fb983 \
  --search-lock "$search_lock" \
  --output "$report"

sha256sum "$report" > "$report.sha256"
```

The evaluator checks the model manifest, trial ledger, search lock, feature schema, and
every model artifact hash before reading results.

## Decision rule

Evaluate all four targets and preserve every result. A target is blind-functional only
when all three conditions hold:

1. mean loss improvement over the strongest registered market-only baseline is positive;
2. at least 6 of 10 assets have positive loss improvement; and
3. the two-sided HAC p-value remains below 0.05 after Holm adjustment across all four
   target comparisons.

Read the evaluator's `engineeringFunctional` field as the target-level decision. One
volatility target may pass even if the return targets fail. A failed target must not be
refit on July or relabeled as a new success criterion.

If at least one volatility target passes, start the already registered 90-day
`PROSPECTIVE_SHADOW_FORECAST` program with that exact frozen artifact. Forecasts must be
timestamped and stored before outcomes arrive. This still authorizes neither paper nor
live trading. If no target passes, preserve the negative report and design a new model
generation using post-July information only.

## Final handoff record

Record the following in a dated research decision-log entry after evaluation:

- release ID and source digest;
- frozen-model, blind-feature, and blind-report SHA-256 values;
- acquisition start/completion and evaluation timestamps;
- completeness counts and any acquisition retries;
- all four target decisions and their adjusted p-values;
- whether a 90-day prospective shadow program was opened.

Do not commit raw evidence, model artifacts, build outputs, or secrets. Keep those in the
registered host paths and commit only the human-readable decision record.
