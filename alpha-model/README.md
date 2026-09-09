# Local bounded research execution

New local historical work must use the [experiment supervisor](../research/alpha/experiments.md).
Start its clock before acquisition or preparation and run the component commands
below through `experiment.py run`. Search commands need `--trials N` before the
experiment ID. Existing persistent V2 dispatch is disabled; its original instructions
below are retained as historical context, not authorization to restart it.

The local RTX 3060 (12 GB) was verified through CUDA driver context creation and
allocation on 2026-09-08. Create a user-space model environment only when model work
requires it, install `requirements.lock`, and record resolved versions and the
`probe-gpu` artifact. Set `TMPDIR` under `/home/dev/.tmp` during installation.
The system Python does not provide Torch. Use `$HOME/.local/bin` for any persistent
launcher, following the lane tool-installation policy. Recheck shared RAM and VRAM
before choosing the contract memory limit. No new SDK was installed for this update.

# Archive alpha campaign runner

This worker runs assumption-neutral, multi-asset directional research from
immutable historical inputs. It does not place, modify, or cancel orders.

The historical archive contract is
`research/alpha/campaigns/archive-directional-gpu-v2.lock.json`. V1 was
superseded before any data or outcome was opened. V2 discovers symbols from
historical archive prefixes, reconstructs membership from native daily bars,
then acquires native five-minute bars only for assets that enter a point-in-time
basket.

The campaign keeps four boundaries explicit:

1. archive registration and point-in-time universe reconstruction;
2. causal panel and true time-major tensor materialization;
3. purged chronological development search with a complete trial ledger;
4. write-once candidate freezing and confirmation.

BTC and ETH membership is a campaign constraint. BTC leadership, factor choice,
return sign, horizon, basket size, mechanism value, and model superiority are
all empirical development dimensions.

## Environment

Use the pinned GPU worker image through the existing
`python-torch-gpu-v1` runner capability. For local development, create a
user-space environment:

```sh
python3 -m venv /home/dev/.venv/marketlab-alpha
/home/dev/.venv/marketlab-alpha/bin/pip install -r alpha-model/requirements.lock
```

The campaign process and worker probe CUDA and record GPU, VRAM, Torch, CUDA, and determinism state
in every search manifest. It chooses a conservative batch-size ceiling from
measured VRAM; it never changes the frozen architecture in response to an
out-of-memory error.

## Standalone dispatch

After building a digest-pinned release, use
`deploy/dispatch-archive-alpha.sh` on Gerald. The persistent user service
publishes an immutable discovery checkpoint before starting long acquisition,
survives SSH termination, resumes exact request/trial checkpoints, and stops at
`AWAITING_CONFIRMATION_REVIEW`.

Lightweight supervision uses `deploy/archive-alpha-status.sh`. It may restart
the exact frozen unit after a transient interruption. It must escalate checksum,
checkpoint, determinism, mandatory-asset, repeated OOM, and repeated timeout
failures, and it must never run `freeze` or `confirm`.

Production development search uses a disposable process for each exact trial.
`search-step` replays durable ledger metadata, runs at most one missing trial,
publishes its immutable checkpoint, and exits so panel, estimator, and file-cache
memory are reclaimed by the kernel. Ordered rung plans are immutable and include
the hashes of trial records used for automatic promotion. Checkpoint replay checks
recorded paths and sizes without repeatedly hashing every model bundle; the selected
winner is hash-verified before deserialization and all completed bundles receive a
single streamed hash audit before the final search result is published.

Production is intentionally serial with one BLAS/OpenMP thread and one trial in
flight. Each trial starts with 8 CPUs, 36 GiB RAM, and no swap. Exit 137 records an
immutable operational attempt and retries the same missing trial once at 40 GiB.
A second exit 137 marks the campaign `OPERATIONALLY_BLOCKED` and exits with systemd
restart prevention. Stop and resume through the user unit; the interrupted
in-memory fit is recomputed while durable successes and failures are preserved.

## Manual component commands

```sh
python3 alpha-model/campaign.py discover-binance --output-root discovery
python3 alpha-model/campaign.py download-binance --data-type klines --interval 1d --symbols discovery/symbols.txt --start-month 2020-01 --end-month 2026-07 --output-root daily-archives
python3 alpha-model/campaign.py normalize-binance --manifest daily-archives/manifest.json --output daily.parquet
python3 alpha-model/campaign.py universe-observations --bars daily.parquet --output observations.jsonl
python3 alpha-model/campaign.py universe --observations observations.jsonl --first-as-of 2020-04-01T00:00:00Z --last-as-of 2026-07-29T00:00:00Z --output universes.json
python3 alpha-model/campaign.py universe-symbols --universe universes.json --output research-symbols.txt
python3 alpha-model/campaign.py download-binance --data-type klines --interval 5m --symbols research-symbols.txt --start-month 2020-01 --end-month 2026-07 --output-root five-minute-archives
python3 alpha-model/campaign.py normalize-binance --manifest five-minute-archives/manifest.json --output five-minute.parquet
python3 alpha-model/campaign.py materialize --bars five-minute.parquet --universe universes.json --basket-size 6 --period-start 2020-01-01T00:00:00Z --period-end 2025-06-01T00:00:00Z --output development-6.parquet
python3 alpha-model/campaign.py materialize --bars five-minute.parquet --universe universes.json --basket-size 6 --period-start 2025-06-01T00:00:00Z --period-end 2026-08-01T00:00:00Z --output confirmation-6.parquet
python3 alpha-model/campaign.py probe-gpu --output gpu.json
python3 alpha-model/campaign.py search --panel development-6.parquet --lock research/alpha/campaigns/archive-directional-gpu-v2.lock.json --confirmation-ledger-root /mnt/media/marketlab/artifacts/archive-alpha-v2/confirmation-ledger --output search-6
python3 alpha-model/campaign.py merge-searches --search search-4/search-result.json --search search-6/search-result.json --search search-10/search-result.json --lock research/alpha/campaigns/archive-directional-gpu-v2.lock.json --output merged-search.json
python3 alpha-model/campaign.py freeze --search merged-search.json --lock research/alpha/campaigns/archive-directional-gpu-v2.lock.json --candidate CANDIDATE_ID --confirmation-panel confirmation-BASKET_SIZE.parquet --output frozen.json
python3 alpha-model/campaign.py confirm --panel confirmation-BASKET_SIZE.parquet --frozen frozen.json --frozen-sha256 SHA256_PRINTED_BY_FREEZE --output confirmation.json
python3 alpha-model/campaign.py finalize-family --confirmation confirmation.json --confirmation-sha256 SHA256_PRINTED_BY_CONFIRM --family-manifest merged-search.json --output confirmation-family.json
python3 alpha-model/campaign.py report --confirmation confirmation.json --output report.md
```

`acquire` registers local archive bytes and hashes. Network download remains a
separate source adapter so retrieval policy, checksums, and publication clocks
cannot be hidden inside model training.

Incomplete UTC days never enter liquidity ranks. Each active mechanism retains
the exact 400-trial ceiling per basket, spent iteratively: eight balanced cells
for every family, 16 full-development configurations for at most six promoted
families, then 32 configurations across three seeds for at most two survivors.
Every attempt and model bundle is checkpointed before the next trial. Search
results remain `EXPLORATORY`; only the exact merged basket family may later be
frozen for confirmation after explicit review.
## Suspend for a development evidence audit

The multi-basket search can be stopped at the current durable trial boundary without opening confirmation:

```sh
deploy/archive-alpha-suspend.sh /mnt/stack/marketlab/config/archive-alpha-v2.env \
  /mnt/media/marketlab/artifacts/archive-alpha-v2
```

Audit only the immutable JSON ledgers, plans, and completed search reports. Model bundles are deliberately excluded:

```sh
PYTHONPATH=alpha-model python3 -m marketlab_alpha.audit audit-development \
  --lock research/alpha/campaigns/archive-directional-gpu-v2.lock.json \
  --search 4=/mnt/media/marketlab/artifacts/archive-alpha-v2/search-4 \
  --search 6=/mnt/media/marketlab/artifacts/archive-alpha-v2/search-6 \
  --confirmation-ledger-root /mnt/media/marketlab/artifacts/archive-alpha-v2/confirmation-ledger \
  --suspension-record /mnt/media/marketlab/artifacts/archive-alpha-v2/suspensions/evidence-audit-TIMESTAMP.json \
  --output /mnt/media/marketlab/artifacts/archive-alpha-v2/evidence-audit
```

The output is write-once and contains `ledger-manifest.json`, `audit.json`, and `audit.md`. The command fails closed on campaign or basket mismatches, duplicate trials, changed promotion sources, a nonempty confirmation ledger, or an existing output directory.
## Focused historical V3

`marketlab_alpha.focused_v3` freezes one audited 15-minute market-state candidate, performs a single-use historical confirmation only after explicit authorization, and produces a separate non-promotable Hyperliquid transfer diagnostic from an already-ended period. It never waits for future observations.

```sh
PYTHONPATH=alpha-model python3 -m marketlab_alpha.focused_v3 freeze-focused-v3 --help
PYTHONPATH=alpha-model python3 -m marketlab_alpha.focused_v3 confirm-focused-v3 --help
PYTHONPATH=alpha-model python3 -m marketlab_alpha.focused_v3 acquire-hyperliquid-transfer --help
PYTHONPATH=alpha-model python3 -m marketlab_alpha.focused_v3 diagnose-hyperliquid-transfer --help
```

The confirmation command writes its single-use marker before reading any panel row. The transfer result is always `INCONCLUSIVE` and cannot authorize predictive, paper, or live promotion.

## Social-variance defensive exposure replay

marketlab_alpha.variance_exposure freezes and evaluates a historical, de-risk-only portfolio mapping from the frozen social-attention one-hour variance forecast. It verifies model and feature identities, acquires official Binance funding archives with publisher checksums, applies fixed 5 and 10 bps turnover stresses, and writes immutable portfolio rows and a dependence-aware result. The July 2026 period is already open, so this workflow cannot produce blind validation, paper eligibility, or live authority.

Commands:

    PYTHONPATH=alpha-model python3 -m marketlab_alpha.variance_exposure freeze --help
    PYTHONPATH=alpha-model python3 -m marketlab_alpha.variance_exposure acquire-funding --help
    PYTHONPATH=alpha-model python3 -m marketlab_alpha.variance_exposure evaluate --help
