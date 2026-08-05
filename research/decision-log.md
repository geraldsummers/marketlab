# Research decision log

Adaptive research choices are recorded here before their candidate result is
observed. Immutable run manifests and content-addressed reports hold the
corresponding empirical results.

## 2026-07-28 — Hyperliquid BTC four-hour log-HAR variance adaptation

**Status before execution:** preregistered; the candidate forecasts, losses,
coefficients, and holdout result have not been observed. This entry authorizes
no ingestion or experiment run by itself. The frozen registration is
`hyperliquid-btc-four-hour-log-har-variance@1.0.0`, plan hash
`2fbf43d99477adadebe1e896c0f20374da6cb99c9adbe3a8e32c7a68b8a018ae`.

**Why this adaptation was selected:** the frozen
`har-realized-volatility@1.0.0` plan requires 730 days of five-minute candles
and a point-in-time cross-sectional universe. Hyperliquid's official API
retains only the [most recent 5,000 candles](https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint),
which is about 17 days at five-minute resolution and cannot satisfy that plan.
Four-hour sampling is the finest native cadence that both fits under this
limit and supports the original long validation design. Fixing one BTC
perpetual also removes the unavailable historical universe reconstruction. The
result is explicitly a feasibility adaptation, not a reproduction of Corsi's
high-frequency measurement design.

**Evidence reviewed:** the heterogeneous daily/weekly/monthly volatility
cascade in [Corsi (2009)](https://doi.org/10.1093/jjfinec/nbp001), robust
variance-forecast ranking with an imperfect proxy in
[Patton (2011)](https://doi.org/10.1016/j.jeconom.2010.03.034), and a
crypto-specific HAR benchmark using intraday returns in
[Brauneis and Sahiner (2026)](https://doi.org/10.1007/s10690-024-09510-6).
The crypto paper uses five-minute Coinbase observations; it supports testing a
crypto HAR benchmark but does not validate four-hour Hyperliquid variance as an
equivalent realized-variance proxy.

**Frozen source request and causal clock:** the only admissible request is a
production Hyperliquid `candleSnapshot` for the BTC perpetual, native interval
`4h`, half-open range
`[2024-04-29T20:00:00Z, 2026-03-31T00:00:00Z)`. The first candle is an anchor
for its successor's close-to-close return. The request is expected to contain
4,201 contiguous completed candles: one anchor plus six returns for each of
700 complete UTC days from 2024-04-30 through 2026-03-30. Exact raw response
bytes and request provenance must be content addressed. Every candle must have
positive prices and trade count, no duplicates or cadence gaps, and causal
availability exactly one millisecond after its exchange close. A daily
decision occurs at 00:00 UTC only after the preceding 20:00–24:00 candle is
available.

**Frozen aggregation and model:** for UTC day \(d\), realized variance is the
sum of the six squared four-hour close-to-close log returns in that day. The
daily feature is that variance; weekly and monthly components are respectively
the sum of the trailing 42 and 180 squared returns divided by 7 and 30. The
three OLS inputs are the natural logarithms of those positive components. The
model has an intercept and no parameter grid. Its label is log next-day
realized variance; its forecast is exponentiated before variance-scale MAE,
RMSE, and QLIKE are calculated. No epsilon, clipping, or post-hoc positivity
rule is permitted: any non-positive component or target fails the run.

**Frozen validation and controls:** the 700 daily variance outcomes yield 670
eligible one-day forecast origins after the 30-day feature history. The last
90 outcomes, ending immediately before the momentum holdout begins on
2026-03-31, form this candidate's one-time holdout. Development and holdout
must satisfy both a one-day purge and a one-day embargo. Development uses five
expanding 30-day test folds with at least 365 effective training days and no
hyperparameter tuning. The mandatory controls are expanding historical-mean
variance and last-realized-variance persistence, fitted on exactly the same
rows. QLIKE is primary. Loss-differential inference uses HAC lag 7 and
Benjamini–Hochberg adjustment across exactly the two candidate-versus-control
comparisons.

**Pre-execution implementation clarification:** this clarification was recorded
while the candidate still had zero runs, trials, and artifacts. Purging makes
seven complete development folds feasible; the fixed evaluation family is the
final five chronological folds, preserving their original fold identifiers.
The exponentiated log-OLS forecast uses no residual-smearing or other
retransformation correction. This is the geometric-scale forecast frozen by
the registered `LogRealizedVariance` target, not a general test of every HAR
retransformation.

**Failure and promotion decision:** the screen fails if data provenance or
quality fails, either holdout QLIKE is not lower, or either dependence-aware
comparison fails after the two-test adjustment. This single BTC and coarse
four-hour proxy cannot establish cross-asset or sampling stability. The period
is retrospectively available and related price history was used by earlier
research, so the test is not researcher-blind. Global SPA family inference and
cross-run holdout reuse prevention remain unavailable. Consequently this plan
is permanently paper-ineligible and promotion remains blocked regardless of a
favorable screen; no trading claim may be derived from it.

## 2026-07-28 — Hyperliquid BTC time-series-momentum adaptation

**Status before execution:** preregistered; candidate result not observed.

**Why this test was selected:** the production audit found that the API could
execute real-data controls but no registered non-control theory. The original
multi-venue time-series-momentum reproduction requires five years of
point-in-time data and cannot be satisfied honestly by current Hyperliquid
history. A separate adaptation was therefore registered instead of weakening
that frozen reproduction.

**Evidence reviewed:** the 12-month own-return continuation result in
[Moskowitz, Ooi, and Pedersen (2012)](https://doi.org/10.1016/j.jfineco.2011.11.003),
crypto-specific momentum evidence in
[Liu and Tsyvinski (2021)](https://doi.org/10.1093/rfs/hhaa113), and the
asset-level/historical-mean critique in
[Huang, Li, Wang, and Zhou (2020)](https://doi.org/10.1016/j.jfineco.2019.08.004).

**Real-data feasibility check:** Hyperliquid returns BTC daily price bars before
the perpetual had observed trades. Those zero-trade pseudo-bars fail the
framework's quality gate and were not admitted. The fixed production request is
the three-year completed-candle interval
`2023-07-28T00:00:00Z`–`2026-07-28T00:00:00Z`, which contains 1,096 contiguous
BTC perpetual daily bars with positive observed trade counts.

**Frozen design:** `hyperliquid-btc-time-series-momentum@1.0.0`, plan hash
`c865419fc12b3324491130bae15fe0de1a507f25e03b268617ea352130415c2a`.
The candidate is intercept OLS using the trailing 365-day log return and the
standard deviation of 60 completed daily returns to forecast the next 30-day
log return. It has no tunable parameters. Development uses five expanding
60-day folds, 180 effective training days, 30-day purge and embargo, HAC lag
29, and mandatory expanding-historical-mean and zero-return controls.

**Confirmation decision:** the final 90 labeled origins are boundary-purged
from development and evaluated once in one unconditional run. Candidate and
both controls are fit before the holdout labels are read; Benjamini–Hochberg is
applied to the two HAC loss comparisons. This is not researcher-blind because
prior control work examined overlapping Hyperliquid history, and the current
control plane does not prevent another run from reusing the same holdout.
Coefficient-sign inference and the registered global SPA family test are also
not implemented. These limitations are embedded in the report, and promotion
remains blocked regardless of the result.

## 2026-07-28 — Hyperliquid BTC hourly-return reversal adaptation

**Status before execution:** preregistered; candidate returns and result not
evaluated. The database contains no theory version, run, trial, or artifact for
this adaptation at the time of this entry. The frozen
`hyperliquid-btc-hourly-return-reversal@1.0.0` plan hash is
`b9be8214bb3834cb940e83b4dee8bbf05929290fbec10fde9810afe26e66c57c`.

**Why this adaptation was selected:** the immutable production snapshot
`160b1dd2-1869-31f6-b773-28c05826b4e5` contains exactly 4,800 contiguous native
one-hour BTC perpetual candles over 200 days. The parent
`liquidity-conditioned-reversal@1.0.0` plan requires 180 days of historical
BBO and L2 depth plus observed execution, which the live collector has not yet
accumulated. This separate adaptation tests only the parent theory's
unconditional short-horizon reversal implication. It cannot confirm or reject
the parent's liquidity interaction or trading economics.

**Evidence reviewed:** [Nagel (2012)](https://doi.org/10.1093/rfs/hhs066)
interprets short-term reversal as compensation for liquidity provision, while
[Wen, Bouri, Xu, and Zhao
(2022)](https://doi.org/10.1016/j.najef.2022.101733) report both intraday
momentum and reversal in cryptocurrency markets. Because either sign is
plausible, this plan requires the fitted lagged-return coefficient to remain
negative rather than relabeling a positive result as momentum.

**Frozen source and causal clock:** the only admissible snapshot has manifest
hash `3307c6b61fd76bd44175b2a51b994c5c9b6c9f5cf40a546e95afa3cf72c135da`
and one production raw object with SHA-256
`f00395c84d764f1a31968563fdec25ee7dc7913362141ee467f18f9cb7446b79`.
Its exact request is the BTC `candleSnapshot`, native `1h`, half-open range
`[2026-01-09T03:00:00Z, 2026-07-28T03:00:00Z)`. Every candle must be completed,
positive-priced, positive-trade-count, hourly contiguous, and causally
available at its close. At decision time the feature is
`ln(C_t/C_t-1)` and the sealed label is `ln(C_t+1/C_t)`.

**Frozen validation and controls:** 4,800 candles produce 4,798 labeled hourly
origins. The final 720 origins, with decisions from
`2026-06-28T03:00:00Z` through `2026-07-28T02:00:00Z` and final label
availability at `2026-07-28T03:00:00Z`, form one unconditional holdout. The
one-hour purge and embargo remove exactly one boundary row, leaving 4,077
development rows. All eleven feasible expanding seven-day folds are used,
each with at least 2,160 effective training rows, for 1,848 development
forecasts. The candidate is intercept OLS with only `return_1h`; controls are
the expanding historical mean, zero return, and positive-return persistence.
Squared-error loss comparisons use HAC lag 24 and Benjamini–Hochberg adjustment
across exactly these three controls.

**Failure and promotion decision:** evidence requires a negative lagged-return
coefficient in every development fit and the final holdout fit, lower holdout
squared error than all three controls, and adjusted two-sided p-values below
0.05 for all comparisons. Model selection may not use holdout results. This is
a retrospective, BTC-only, candle-close forecast without spread, depth, fee,
latency, capacity, or global SPA evaluation. It is permanently forecast-only
and paper-ineligible regardless of its result.

**Post-execution result:** the single coordinated run
`2ae45bdf-aec1-4ef2-b6d9-f9fe2d8c3370` completed under source revision
`22079ceb41e0114459c51847893ba95499b01282a3c7ff3fc815689ad0e5c2ad`.
All eleven development slopes were unexpectedly positive (from
`0.004046222073845827` to `0.017242062646562258`), while the final fit was
negative (`-0.009419098990486714`), so the required sign stability failed.
On the 720-row sealed holdout, candidate RMSE (`0.003578414910041611`) was
worse than both the historical mean (`0.003578035383733444`) and zero return
(`0.00357465881932542`). The durable evidence status is
`SEALED_HOLDOUT_HOURLY_REVERSAL_NOT_SUPPORTED`; promotion is blocked. The
report and predictions hashes are respectively
`b5b7943b40c5f73cac2b8f1ede8e9927cafea6cb470d4bd8f62fd61794e5755b`
and `39218d666010504a2721410fb769f2b4c9605b4253e3b8462694296de07f945c`.

## 2026-07-28 — Hyperliquid ETH hourly volatility periodicity

**Status before data retrieval:** preregistered; no ETH source response, price,
return, fitted value, or holdout statistic from the requested period has been
read. The frozen `hyperliquid-eth-hourly-volatility-periodicity@1.0.0` plan hash
is `be4ed3d9a555e431310bc58bbc7657b0b158f06c9db18b9cb3d12afbbb9e6106`.

**Why this is a distinct remaining family:** [Hansen, Kim, and Kimbrough
(2024)](https://doi.org/10.1093/jjfinec/nbac034) document recurrent
hour-of-day volatility in BTC and ETH and improved out-of-sample volatility
forecasts when periodicity is included. This tests deterministic calendar
structure incremental to dynamic variance, rather than another return
lookback, reversal horizon, HAR window, or asset replication.

**Frozen source request:** retrieve exactly the native Hyperliquid mainnet ETH
perpetual `1h` candle request over the 4,800-hour half-open interval
`[2025-06-23T03:00:00Z, 2026-01-09T03:00:00Z)`. Its canonical request body must
be
`{"req":{"coin":"ETH","endTime":1767927599999,"interval":"1h","startTime":1750647600000},"type":"candleSnapshot"}`.
The response must contain exactly 4,800 unique, positive-priced,
positive-trade-count, completed and contiguous candles. Only hashes, row
counts, clocks, and quality findings may be inspected before the executor is
frozen; raw price or volume values may not.

**Frozen features and target:** each decision starts when completed candle
`C_t` is available. The positive target is
`max(ln(C_t+1/C_t)^2, 1e-12)`, fitted on the log scale. Clock-free dynamic
features are the log of the latest squared return and the log of trailing
24-return mean variance, both floored at `1e-12`. The candidate adds exactly
23 UTC-hour indicators for the forecast interval start, with hour 00 as the
reference. There is no hyperparameter search.

**Frozen validation and controls:** the first usable decision follows 24
completed returns, producing exactly 4,775 labeled origins. The final 720
origins are the sole holdout, with decisions from `2025-12-10T03:00:00Z`
through `2026-01-09T02:00:00Z` and final label availability at
`2026-01-09T03:00:00Z`. A one-hour purge and embargo remove exactly one
boundary origin, leaving 4,054 development rows. Use all eleven expanding
seven-day folds with at least 2,160 effective training rows, yielding 1,848
development forecasts. Controls are (1) the identical dynamic log-variance
model without clock indicators and (2) expanding arithmetic mean variance.
Loss is variance-scale QLIKE; HAC lag is 24; Benjamini–Hochberg adjustment is
across exactly two comparisons.

**Frozen decision:** support requires lower sealed-holdout mean QLIKE than
both controls and positive candidate-minus-control QLIKE-loss improvement with
adjusted two-sided p-values below 0.05 for both. Development results cannot
alter this rule. The adaptation is ETH-only, retrospective, hourly, based on
one noisy squared return per target, and does not reproduce the paper's
multi-venue high-frequency GARCH design. It is permanently forecast-only and
paper-ineligible.

**Pre-retrieval source amendment:** the first immutable ingestion
`c188a02a-f83f-4cc1-af5e-dc4a2ad07999` failed closed before snapshot creation.
Hyperliquid returned only `709200000` ms of coverage, below the frozen
`17280000000` ms requirement, consistent with the REST endpoint's rolling
candle retention. No response prices, returns, or outcome statistics were
read. The model, target, controls, sample size, folds, holdout size, and
decision thresholds remain unchanged. The sole admissible replacement is the
previously unread ETH `1h` request over
`[2026-01-09T03:00:00Z, 2026-07-28T03:00:00Z)`, canonical body
`{"req":{"coin":"ETH","endTime":1785207599999,"interval":"1h","startTime":1767927600000},"type":"candleSnapshot"}`.
The holdout decisions consequently become `2026-06-28T03:00:00Z` through
`2026-07-28T02:00:00Z`, with the final label available at
`2026-07-28T03:00:00Z`. This calendar period overlaps earlier BTC screens, but
no ETH values from it have been observed; the overlap and lack of a global
cross-run holdout lock remain explicit limitations.

**Post-execution result:** coordinated run
`9f3c33a6-4b5f-419a-9fba-b2af984b3b38` completed from the amended production
snapshot `af958a14-31b2-3841-9a10-9a6762a1208b`. The snapshot contained exactly
4,800 contiguous completed candles with zero quality findings; its manifest
hash is
`7c0d111ac236c58ceeaed3f2dbb9a4d8c99fe54cbdc10b7d1042b57d341a1fa8`
and raw-response hash is
`550d601c0ae2aa2f12e3d823fd9f1cdd75972646654cf705890383a037e82707`.
After the frozen 24-return warm-up and one boundary purge, the executor used
4,054 development rows, produced 1,848 forecasts across all eleven folds, and
opened the single 720-row holdout once.

Holdout candidate QLIKE was `-6.317342054924922`, versus
`-6.248873373828504` for the clock-free dynamic model. The improvement
`0.06846868109642025` was not significant after the frozen two-comparison
Benjamini-Hochberg adjustment (`p = 0.7705479108447613`). Against expanding
mean variance, QLIKE was `-9.48500674940982`: the candidate was materially
worse by `-3.167664694484888`, with adjusted `p =
0.000003606144608259143`. The durable evidence status is
`SEALED_HOLDOUT_HOURLY_VOLATILITY_PERIODICITY_NOT_SUPPORTED`; promotion is
blocked. The immutable report and prediction hashes are respectively
`ce2fd80019dd2a3a37e6aa726dadc10759c91376d5805440c0b7202c3e8bea43`
and
`9a87809cffb9fa0e2c33d384aa4249721c3633c689e86dd58f57d9f33cf608e6`.

## 2026-08-05 — Social-attention functional model blind July validation

**Frozen boundary before acquisition:** the corrected 40-trial chronological
search completed under release `marketlab-social-functional-5f9b1b0`, source
digest
`44bc753d773915630e5cda8e5ee7049c0d74aa4af7c8e0edcf4f49567d7e51f2`.
Its externally pinned model-set manifest hash was
`70d73e2f3c12d05b0d31cc5460b6b97fd113e692f85e61f698a8835b009fb983`.
That hash was verified while the blind evidence root was absent. No model,
feature, baseline, search, or acceptance rule changed after the boundary.

**Blind acquisition and materialization:** the registered root was opened at
`2026-08-04T06:47:16Z`. The digest-pinned backfill image acquired all ten
Binance market manifests with 5,856 fifteen-minute bars per asset, zero gaps,
and zero missing minutes. Two disjoint Bluesky shards acquired all 610 daily
social manifests, 61 for each of ten assets, with zero service restarts. The
last acquisition manifest completed at `2026-08-04T14:25:42Z`. The frozen
program-lock hash is
`ec30222c94a2bf84081ff3783518293dfd7f6a8599c9e382a29c642d82bc2fe6`.

June was used only for the registered 30-day feature warm-up. Materialization
completed at `2026-08-04T14:26:23Z` and emitted exactly 29,760 July rows,
2,976 for every asset. The feature object hash is
`a376614dc095e6c0a053f59e07e88fe67cd670e9774fe9729408266a598564e7`;
the feature-manifest hash is
`b5fd387d6fbb6f765afe82eb5db77017f88d9121a4e56bb381428343a7520ffd`.

**One-time result:** evaluation completed at `2026-08-04T14:26:27Z`. The
one-hour realized-variance model passed every frozen target-level gate. Its
mean QLIKE-loss improvement over the strongest market-only elastic-net
baseline was `0.027668041870337765`; all ten assets improved; its HAC
two-sided p-value was `0.0000029576948074969817`, and its Holm-adjusted value
across all four target comparisons was `0.000011830779229987927`.

The other targets did not pass. Fifteen-minute return improved by only
`3.901545267283385e-9` with eight positive assets and Holm-adjusted `p = 1.0`.
One-day return was worse by `-0.000010108674048288167`, with three positive
assets and adjusted `p = 1.0`. One-day variance improved by
`0.021780056266059188` across nine assets, but was not statistically
confirmed (`p = 0.08113559893110485`, Holm-adjusted `p =
0.24340679679331456`). Failure does not authorize refitting or relabeling any
target.

**Decision and limits:** the durable blind report hash is
`07537da00a1088b5a445be8adc931e274539b82cecf8a3ca1f0adc28fd15c4c0`.
The exact frozen one-hour variance artifact is admitted only to the registered
90-day prospective shadow-forecast stage. This is evidence of incremental
one-hour variance forecasting function, not price-direction predictability or
a trading strategy. Paper and live trading remain unauthorized. The
prospective shadow program was not opened at the time of this entry because
durable live feature materialization, pre-outcome forecast publication, and
outcome-sealing orchestration are not yet implemented.
