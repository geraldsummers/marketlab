# Archive alpha campaign runner

This worker runs assumption-neutral, multi-asset directional research from
immutable historical inputs. It does not place, modify, or cancel orders.

The active contract is
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
