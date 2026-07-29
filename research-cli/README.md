# MarketLab research CLI

This application runs the first auditable Hyperliquid mainnet research screen. It
downloads raw responses through `HyperliquidDataIngestor`, stores them with
`ContentAddressedDataStore`, and writes a canonical JSON report plus a SHA-256
sidecar under `<artifact-root>/research-reports/`.

Production runs require `MARKETLAB_SOURCE_REVISION` (or
`--source-revision`) to be the exact lowercase 40-character Git tree/commit hash
or 64-character source-archive SHA-256 supplied by deployment:

The requested interval describes funding-decision buckets. For each funding
observation in `[start, end)`, the suite uses the subsequent fully completed
one-hour candle as its forecast label. Consequently, `end + 1 hour` must already
be in the past. Both timestamps must be UTC-hour aligned.

```sh
export MARKETLAB_SOURCE_REVISION='<40-or-64-character-lowercase-source-hash>'
./gradlew :research-cli:run --args='
  --data-root /mnt/media/marketlab/raw
  --artifact-root /mnt/stack/marketlab/active-artifacts
  --coins BTC,ETH
  --start 2026-04-28T00:00:00Z
  --end 2026-07-28T00:00:00Z
  --seed 21745394912678988
'
```

Equivalent environment variables are `MARKETLAB_DATA_ROOT`,
`MARKETLAB_ARTIFACT_ROOT`, `MARKETLAB_COINS`, `MARKETLAB_START`,
`MARKETLAB_END`, `MARKETLAB_SEED`, and `MARKETLAB_SOURCE_REVISION`. For a local
or test-only invocation with no revision, pass `--allow-unversioned` (or set
`MARKETLAB_ALLOW_UNVERSIONED=1`); the report is then explicitly marked
non-production. Reports also record the JVM, Kotlin runtime, OS, and architecture.

The candidate is an expanding-window OLS forecast from causally available
funding rate to the subsequent hourly open-to-close log return. Zero-return and
expanding historical-mean forecasts are mandatory controls. A sample with fewer
than 2,160 complete aligned rows, or fewer than five emitted walk-forward folds,
is explicitly inconclusive.

The capacity section walks one currently observed L2 snapshot in each direction
at $10,000, $100,000, and $1,000,000 using at most 10% of every displayed level.
It is a point-in-time depth diagnostic, not a historical replay or a fill. This
suite creates no orders or fills and cannot promote a theory to paper trading
without separate historical observed-book execution evidence.
