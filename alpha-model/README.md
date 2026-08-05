# Archive alpha campaign runner

This worker runs assumption-neutral, multi-asset directional research from
immutable historical inputs. It does not place, modify, or cancel orders.

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

## Commands

```sh
python3 alpha-model/campaign.py acquire --input ARCHIVE --output archives.json
python3 alpha-model/campaign.py download-binance --data-type klines --interval 1m --symbols symbols.txt --start-month 2020-01 --end-month 2026-07 --output-root /mnt/media/marketlab/raw/archive-alpha/klines
python3 alpha-model/campaign.py normalize-binance --manifest /mnt/media/marketlab/raw/archive-alpha/klines/manifest.json --output /mnt/media/marketlab/raw/archive-alpha/klines.jsonl
python3 alpha-model/campaign.py universe-observations --bars /mnt/media/marketlab/raw/archive-alpha/klines.jsonl --output observations.jsonl
python3 alpha-model/campaign.py universe --observations observations.jsonl --first-as-of 2020-04-01T00:00:00Z --last-as-of 2026-08-01T00:00:00Z --output universes.json
python3 alpha-model/campaign.py materialize --bars /mnt/media/marketlab/raw/archive-alpha/klines.jsonl --universe universes.json --basket-size 6 --period-start 2020-01-01T00:00:00Z --period-end 2025-06-01T00:00:00Z --output development-6.jsonl
python3 alpha-model/campaign.py materialize --bars /mnt/media/marketlab/raw/archive-alpha/klines.jsonl --universe universes.json --basket-size 6 --period-start 2025-06-01T00:00:00Z --period-end 2026-08-01T00:00:00Z --output confirmation-6.jsonl
python3 alpha-model/campaign.py probe-gpu --output gpu.json
python3 alpha-model/campaign.py search --panel development-6.jsonl --lock research/alpha/campaigns/archive-directional-gpu-v1.lock.json --confirmation-ledger-root /mnt/media/marketlab/artifacts/archive-alpha/confirmation-ledger --output search-6
python3 alpha-model/campaign.py merge-searches --search search-4/search-result.json --search search-6/search-result.json --search search-10/search-result.json --lock research/alpha/campaigns/archive-directional-gpu-v1.lock.json --output merged-search.json
python3 alpha-model/campaign.py freeze --search merged-search.json --lock research/alpha/campaigns/archive-directional-gpu-v1.lock.json --candidate CANDIDATE_ID --confirmation-panel confirmation-BASKET_SIZE.jsonl --output frozen.json
python3 alpha-model/campaign.py confirm --panel confirmation-BASKET_SIZE.jsonl --frozen frozen.json --frozen-sha256 SHA256_PRINTED_BY_FREEZE --output confirmation.json
python3 alpha-model/campaign.py finalize-family --confirmation confirmation.json --confirmation-sha256 SHA256_PRINTED_BY_CONFIRM --family-manifest merged-search.json --output confirmation-family.json
python3 alpha-model/campaign.py report --confirmation confirmation.json --output report.md
```

`acquire` registers local archive bytes and hashes. Network download remains a
separate source adapter so retrieval policy, checksums, and publication clocks
cannot be hidden inside model training.

Incomplete UTC days never enter liquidity ranks. Each active mechanism uses an
exact 400-trial production ceiling per basket: 16 Stage-A configurations for
each of 13 families, then 32 configurations for two survivors across three
fixed seeds. A trial is one configuration/seed; every fold fit is reported by
its `fitCount`. Candidates must improve both naive controls and a frozen
market-only ridge. Search results remain `EXPLORATORY`; only the exact merged
basket family may be frozen for confirmation.
