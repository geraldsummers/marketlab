# Marketlab

Marketlab is a Kotlin-first research engine for preregistering and testing
academic market-prediction theories on immutable real market data. The engine,
compiled configuration DSL, experiment ledger, and paper trader are separated
so that every result can be reproduced from its source data and run manifest.

The project never generates synthetic market prices. Empirical tests use
production data with provenance, point-in-time availability, and explicit gap
reports.

## Build

```sh
./gradlew check
```

Live Hyperliquid contract tests are opt-in:

```sh
MARKETLAB_MAINNET_TESTS=1 ./gradlew :data:test \
  --tests dev.marketlab.data.hyperliquid.HyperliquidMainnetContractTest
```

## Components

- `contracts`: immutable domain and wire contracts
- `theory-dsl`: compiled, canonical theory configuration DSL
- `data`: real-data collectors and snapshot construction
- `engine`: causal features, validation, forecasting, and paper execution
- `persistence`: PostgreSQL control plane and append-only ledgers
- `analytics-duckdb`: job-local Parquet analytics
- `theories`: reviewed, compiled academic theory catalog
- `service`: asynchronous HTTP API
- `coordinator`: leased Hyperliquid ingestion and engine-backed control runs
- `collector`: continuous BTC trades/BBO/L2 mainnet capture into bounded raw segments
- `social-collector`: exact-byte public social and news capture
- `sentiment-worker`: hash-locked ONNX inference and causal feature materialization
- `worker-kotlin`: isolated batch worker entry point
- `runner`: allowlisted rootless Podman launcher
- `research-cli`: the first production real-data funding screen

## Research guarantees

- Raw HTTP bytes and manifests are content addressed and hash reverified.
- Live WebSocket frames are retained byte-for-byte with their hashes, exchange,
  receive, and availability clocks, server subscription acknowledgements, and
  explicit reconnect/continuity warnings.
- Exchange, local receipt/retrieval, and causal availability clocks remain
  distinct.
- Walk-forward evaluation is chronological, purged, embargoed, and label
  sealed; shuffled market-time splits are unavailable.
- Zero-return, historical-mean, and persistence controls are first-class
  registered theories.
- Execution uses only subsequently observed books, fees, latency, and
  per-level participation caps. Missing fills and prices are never invented.
- Runs, failed trials, quality failures, and promotion decisions are durable.
- Paper sessions require a successful run that passed its registered promotion
  gate, and eligibility is rechecked atomically whenever a session starts.

The registry contains 20 frozen plans spanning null controls, HAR
volatility (including a preregistered BTC four-hour log-HAR feasibility
adaptation and an ETH hourly periodicity screen), perpetual
funding/basis/carry, time-series momentum, order-flow
imbalance, liquidity-conditioned reversal (including a BTC hourly
forecast-only adaptation), cross-venue lead/lag, and five prospective
social/news hypotheses.
Literature selection, contradictory evidence, and the source policy are
documented in [`research/`](research/README.md).

## Prospective social/news program

The social program is a 270-day preregistered study over the point-in-time
Hyperliquid top ten. Membership is recomputed weekly from trailing 30-day daily
candle notional and requires 90 days of listing history. Public information is
stored with publisher, receive, availability, raw-content, adapter, and model
identities; no synthetic social or market observations are permitted.

The five tests cover attention and disagreement as hourly variance predictors,
social polarity as a 15-minute return predictor, news negativity as a daily
variance predictor, and cross-sectional attention as a next-day return
predictor. They share a 60-day holdout and one global Holm correction. Return
theories additionally require observed Hyperliquid BBO/L2 costs and a 90-day
paper phase; live trading remains unauthorized. The frozen schedule and source
and model identities are in
[`research/social-program.lock.json`](research/social-program.lock.json).

## First production screen

The research CLI tests whether causally available BTC funding predicts the
subsequent completed one-hour Hyperliquid return. It compares expanding-window
OLS against zero return and the expanding historical mean, uses purged
walk-forward folds and HAC inference, stores the exact raw responses, and
reports current observed-book capacity separately from historical execution.

See [`research-cli/README.md`](research-cli/README.md) for invocation and
interpretation. A failed predictive test is retained as evidence; it is never
converted into a trading recommendation.

## Service and deployment

The API is bearer-authenticated and idempotent. It registers theories at
startup, queues ingestion and experiment jobs, exposes immutable snapshots and
artifacts, and manages promotion-gated paper sessions. The coordinator executes
the three return controls and the preregistered Hyperliquid BTC time-series
momentum, four-hour log-HAR variance, and hourly return-reversal adaptations;
it also executes the preregistered ETH hourly volatility-periodicity screen.
Unsupported theories terminate explicitly instead of remaining queued.

Gerald deployment is rootless Podman with digest-pinned images, separate
owner/API/coordinator PostgreSQL roles, loopback-only HTTP, no container-engine
socket in application containers, and hot/cold storage split across
`/mnt/stack/marketlab` and `/mnt/media/marketlab`. Build, verification, rollback,
and SSH-tunnel instructions are in [`deploy/README.md`](deploy/README.md).
