# Marketlab coordinator

This application continuously leases `INGESTION` and `EXPERIMENT_RUN` jobs from
PostgreSQL. It heartbeats every active lease and processes the Hyperliquid
mainnet REST kinds implemented by the data module:

- `CANDLES`
- `FUNDING`
- `L2_BOOK`
- `ASSET_CONTEXT` (expanded to mark/oracle and open-interest snapshots)
- `OPEN_INTEREST`
- `ORACLE_MARK`

`TRADES`, `BBO`, and `METADATA` are rejected before any ingestion side effect
because the current REST adapter does not implement them.

The coordinator executes the registered `control-random-walk`,
`control-historical-mean`, and `control-persistence` theories, the fixed BTC
time-series-momentum, four-hour log-HAR variance, and hourly return-reversal
adaptations, plus the fixed ETH hourly volatility-periodicity screen, against
immutable Hyperliquid candle snapshots. Each executor
recomputes the snapshot identity, verifies its hash-named manifest and every raw
object, compiles causal features, reserves its configured holdout, and runs
purged expanding walk-forward folds through the engine. Predictions and reports
are canonical, content-addressed JSON. Their database rows, single terminal
trial, and run transition are committed together under the live job lease
fence. These forecast-only paths always persist `promotionStatus=BLOCKED`.

Raw API responses go through `ContentAddressedDataStore` and default to
`/mnt/media/marketlab/raw`. PostgreSQL records each raw object, its provenance,
the contract snapshot, and every quality finding. A deterministic operation key,
an advisory lock, and the included Flyway uniqueness migration make expired
lease retries resume existing snapshots.

Configuration is environment based. Database settings use the persistence
module's `MARKETLAB_DATABASE_*` variables. Coordinator-specific settings are:

| Variable | Default |
| --- | --- |
| `MARKETLAB_SOURCE_REVISION` | required 64-character source SHA-256 |
| `MARKETLAB_RAW_DATA_ROOT` | `/mnt/media/marketlab/raw` |
| `MARKETLAB_ACTIVE_ARTIFACT_ROOT` | `/mnt/stack/marketlab/active-artifacts` |
| `MARKETLAB_COORDINATOR_LEASE_MS` | `120000` |
| `MARKETLAB_COORDINATOR_HEARTBEAT_MS` | `30000` |
| `MARKETLAB_COORDINATOR_POLL_MS` | `1000` |
| `MARKETLAB_COORDINATOR_REAP_MS` | `30000` |
| `MARKETLAB_COORDINATOR_RETRY_MS` | `15000` |
| `MARKETLAB_DEFAULT_CANDLE_INTERVAL` | `1h` |
| `MARKETLAB_MAX_INGESTION_DAYS` | `3650` |
