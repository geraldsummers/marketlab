# Theory exhaustion audit

**Audit date:** 2026-07-28  
**Scope:** distinct, falsifiable market-prediction mechanisms that can be
identified with production data available to this project, plus documented
blockers for the registered mechanisms that cannot yet be identified.

## What “exhausted” means here

The space of arbitrary formulas and parameter combinations is infinite.
Exhaustion therefore does not mean that every possible indicator has been
curve-fit. It means:

1. every theory already registered in the compiled catalog is either a
   completed, preregistered real-data experiment, a control, or has a concrete
   point-in-time data/identification blocker;
2. the literature screening found no additional materially distinct mechanism
   that can be tested faithfully with the production data currently available;
3. variants that change only estimator, lookback, threshold, or asset are not
   counted as new theories unless they encode a new economic mechanism and a
   separately defensible multiple-testing budget; and
4. failed theories remain failed. No post-result retuning or alternate holdout
   is used to rescue them.

This is an operational stopping rule, not a claim that financial research is
finished forever. The reopening rules at the end make the boundary explicit.

## Completed coordinated experiments

All source observations were retrieved from Hyperliquid mainnet. No synthetic,
simulated, or hand-authored market observations entered an experiment.

| Frozen theory plan | Run | Real sample | Sealed result |
|---|---|---|---|
| `hyperliquid-btc-time-series-momentum@1.0.0` | `5126acad-29e8-4653-b632-ebbe1f4bc228` | BTC daily perpetual candles | `FORECAST_LOSS_SCREEN_NOT_SUPPORTED` |
| `hyperliquid-btc-four-hour-log-har-variance@1.0.0` | `fbda33cb-bc46-49a4-90a2-34ad642321e4` | BTC four-hour perpetual candles | `SEALED_HOLDOUT_QLIKE_SCREEN_NOT_SUPPORTED` |
| `hyperliquid-btc-hourly-return-reversal@1.0.0` | `2ae45bdf-aec1-4ef2-b6d9-f9fe2d8c3370` | 4,800 BTC hourly perpetual candles | `SEALED_HOLDOUT_HOURLY_REVERSAL_NOT_SUPPORTED` |
| `hyperliquid-eth-hourly-volatility-periodicity@1.0.0` | `9f3c33a6-4b5f-419a-9fba-b2af984b3b38` | 4,800 ETH hourly perpetual candles | `SEALED_HOLDOUT_HOURLY_VOLATILITY_PERIODICITY_NOT_SUPPORTED` |

The momentum candidate passed neither the development forecast-loss screen nor
a tradeable execution test. The log-HAR candidate failed the required
historical-mean variance comparison in the sealed holdout. The hourly reversal
candidate had positive slopes in all eleven development folds and was worse
than both mean and zero-return controls in holdout RMSE. The volatility-clock
candidate did not significantly beat its clock-free dynamic control and was
materially worse than the expanding mean-variance control under QLIKE.

A separate funding-to-next-hour-return diagnostic used 2,160 real Hyperliquid
funding observations and produced 1,344 out-of-sample forecasts. It
underperformed both mean and zero-return controls and is recorded as
inconclusive rather than as a completed funding-basis-carry test: it has no
sealed final holdout and cannot identify the basis or executable carry leg.

## Registered catalog disposition

The compiled catalog contains 15 plans: five controls, four completed feasible
adaptations, and six parent or data-intensive theories.

| Plan or family | Disposition on 2026-07-28 | Reopening condition |
|---|---|---|
| Random walk, historical mean, persistence | Exercised as forecast controls | None; controls are not predictive theories |
| Flat position, buy-and-hold | Execution controls, not standalone prediction claims | A candidate reaches execution replay |
| BTC time-series momentum adaptation | Completed; not supported | New preregistered confirmation period, not retuning the opened holdout |
| BTC four-hour log-HAR adaptation | Completed; not supported | New preregistered confirmation period |
| BTC hourly return reversal adaptation | Completed; not supported | New preregistered confirmation period |
| ETH hourly volatility periodicity | Completed; not supported | New preregistered confirmation period |
| Full HAR realized volatility | Blocked: 730 days of five-minute candles and a point-in-time universe are unavailable from the rolling REST window | External archival five-minute history with provenance and universe membership |
| Parent multi-asset time-series momentum | Blocked: five years of continuous spot/perpetual history and point-in-time universe membership are unavailable | Vetted multi-venue archive with delistings and historical eligibility |
| Funding-basis carry | Blocked: 730 days of synchronized funding, mark/oracle basis, and 180 days of historical BBO/L2 execution state are unavailable | Complete basis history plus sufficient live execution capture |
| Order-flow/queue imbalance | Blocked: requires 90 continuous days of causal event-time L2/BBO and fee-aware replay | Collector reaches the continuity threshold and passes gap audit |
| Liquidity-conditioned reversal | Blocked: requires 180 continuous days of BBO/L2 depth and execution replay | Collector reaches the continuity threshold and passes gap audit |
| Cross-venue lead-lag | Blocked: requires 90 days of synchronized Binance and Hyperliquid event streams with measured clock skew | Cross-venue capture is implemented, accumulated, and audited |

The Hyperliquid microstructure collector's earliest event in the current
archive is `2026-07-28T05:58:12Z`. Assuming no subsequent gap, the first
possible 90-day eligibility boundary is `2026-10-26T05:58:12Z`, and the first
possible 180-day boundary is `2027-01-24T05:58:12Z`. Those dates are lower
bounds: releases, reconnects, sequence gaps, clock quality, and required field
coverage must still pass a full continuity audit. Cross-venue capture has not
started, so it has no eligibility date.

## Screening of additional literature families

### Volume and volatility feedback

Published crypto volume-predictability results use cross-asset daily data and
nonlinear/quantile causality. A single-venue candle-volume OLS proxy would not
reproduce that claim, would discard its tail hypothesis, and could not
distinguish information flow from venue migration or artificial volume.
Faithful testing requires a point-in-time multi-asset volume panel and the
preregistered nonlinear inference design. It is therefore a documented data
and identification blocker, not an omitted easy experiment.

### Cross-sectional factors and calendar return effects

Size, momentum, reversal, liquidity, beta, and characteristic-factor claims
require a broad point-in-time universe including inactive and delisted assets.
The current fixed BTC/ETH history cannot identify a cross-sectional premium.
Return calendar effects were not added after screening recent evidence because
they do not supply a robust new return mechanism; the economically distinct
volatility-periodicity claim was the feasible clock-based theory and was
tested.

### Attention, network, macro, and on-chain state

Search interest, social/news sentiment, wallet/network activity, protocol
flows, stablecoin issuance, and macro-announcement theories need archived
features carrying publication/availability timestamps. The project has no
such point-in-time production source. Joining today's revised series to past
prices would create look-ahead bias. The current 200-day candle window also
contains too few major scheduled-event repetitions for a credible independent
event-study holdout.

### Derivatives state

Liquidations, open interest, options-implied volatility/skew, term structure,
and cross-exchange basis are not present as complete point-in-time historical
streams. Current-state API responses cannot reconstruct what was knowable at
each historical decision. Funding by itself is not a basis trade, which is why
the narrow funding diagnostic was not promoted to a carry result.

### Technical indicators and generic machine learning

Moving-average crossovers, RSI, MACD, alternate lags, trees, neural networks,
and ensembles are transformations or estimators, not automatically distinct
economic theories. Searching them after seeing these holdouts would expand the
researcher degrees of freedom without a fresh global snooping correction and
untouched confirmation data. They are excluded by the stopping rule unless
introduced later with a mechanism, a frozen model-family budget, and a new
sealed sample.

## Reopening rules

Research resumes when at least one of the following becomes true:

- a registered live-data requirement reaches its minimum duration and passes
  field, sequence, receive-time, gap, and provenance audits;
- a trustworthy external archive supplies a currently missing point-in-time
  variable and its historical availability timestamps;
- a materially distinct peer-reviewed mechanism yields a falsifiable claim
  supported by the available production data; or
- a fresh confirmation period is accumulated for a previously failed
  adaptation, with the plan and decision threshold frozen before any outcome
  is inspected.

Until then, adding further candle-indicator combinations would be specification
search, not autonomous theory testing.
