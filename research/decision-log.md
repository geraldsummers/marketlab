# Research decision log

Adaptive research choices are recorded here before their candidate result is
observed. Immutable run manifests and content-addressed reports hold the
corresponding empirical results.

## 2026-08-15 — Archive v2 scheduler-only throughput correction

**State before change:** `EXPLORATORY`. Basket 4 had reached
`AWAITING_CONFIRMATION_REVIEW`; basket 6 had 207 checkpointed development
trials and was inside `ROBUSTNESS`. Operators had inspected operational counts,
rungs, timing, memory, and failures, but had not opened confirmation outcomes.
Confirmation remained sealed.

The blanket post-CUDA `fork` experiment caused native worker-pool failures and
was disabled. The replacement changes scheduling only: registered rows,
features, targets, folds, models, configurations, seeds, trial order,
promotion gates, multiplicity, costs, and confirmation boundaries remain
unchanged. A persistent CPU-only pool is created before CUDA initialization;
eligible linear, shallow-tree, forest, and histogram-boosting trials may use it.
Live cgroup working-set headroom (excluding reclaimable `inactive_file` cache)
can reduce the configured ceiling, and all GPU and Torch work remains serial.
New trials record the scheduler, selected worker count, and pinned worker-image
digest while prior immutable checkpoints remain authoritative.

Resume is permitted only after serial-versus-adaptive representative outputs
match and the new digest-pinned deployment passes the archive search tests.
Any checkpoint hash mismatch, nondeterminism, native worker failure, or memory
pressure returns execution to one worker. This operational correction does not
authorize freezing, confirmation, paper trading, or live trading.

## 2026-08-06 — Archive directional campaign v2 supersedes v1 before data

No archive acquisition, development score, candidate selection, confirmation
panel inspection, or confirmation opening occurred under
`archive-directional-gpu-v1`. Before dispatch, v1 was found not to freeze a
historical symbol-discovery source, scalable preparation, restart-safe trial
checkpoints, or the requested automatic compute-expansion and lightweight
supervision boundary.

`archive-directional-gpu-v2` therefore supersedes v1 with disposition
`SUPERSEDED_BEFORE_DATA`. V2 fixes USD-M USDT archive-prefix discovery, daily
point-in-time universe construction, native five-minute research bars,
time-stratified compute rungs, exact per-trial checkpoints, and a mandatory
stop at `AWAITING_CONFIRMATION_REVIEW`. This change uses no observed predictive
outcome. V1 remains in the repository and must not later be described as run.

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

## 2026-08-20 — Archive directional development scheduler recovery

**Decision:** keep the archive directional campaign at `EXPLORATORY` with
confirmation sealed. Preserve the immutable failed ElasticNet full-development
trial whose 86,400-second wall-clock alarm expired after the worker container
had been paused for several days. The failure is operational evidence; it is
not deleted, retried, or reclassified.

**Operational correction:** replace family-wide result materialization with a
bounded streaming scheduler. At most two eligible CPU trials run concurrently,
cgroup headroom is reevaluated before replacement dispatch, child completions
are checkpointed and reported immediately, and selection still receives trials
in the registered order. GPU, Torch, and memory-constrained work remains serial.
Run the worker with 8 CPUs, 40 GiB memory, no swap, one BLAS thread per process,
and deterministic stop/remove semantics. The unfinished trial at rollout may be
aborted and recomputed from its last durable boundary; frozen rows, folds,
models, configurations, seeds, metrics, gates, and confirmation policy do not
change.

## 2026-08-24 — Archive directional disposable trial workers

**Decision:** keep the archive directional campaign at `EXPLORATORY` and keep
confirmation sealed. Replace the long-lived adaptive pool with one disposable
process per exact registered trial. At rollout, preserve the finalized basket-4
ledger with 1,184 durable records (987 completed and 197 failed) and the active
basket-6 ledger with 609 durable records (608 completed and one failed timeout).
Preserve every frozen row, fold, target, seed, configuration, promotion gate,
and multiplicity rule.

**Operational evidence and correction:** repeated kernel cgroup OOM records
showed the monolithic parent retaining the panel and canonical robustness model
bundles while persistent fork workers retained additional panel copies. The
40 GiB cgroup therefore died during replay and restarted the entire search.
The replacement writes immutable ordered rung plans, replays checkpoint
metadata without deserializing model bundles, runs one missing trial, commits
its ledger and bundle atomically, then exits to reclaim memory. Only the final
selected bundle is loaded, and all completed bundle hashes are streamed once
before finalization. A trial receives 36 GiB with one retry at 40 GiB; two OOM
exits durably mark it `OPERATIONALLY_BLOCKED` and prevent a systemd restart.

**Rollout evidence:** release
`marketlab-alpha-trial-worker-v13-20260824` is running from image digest
`sha256:5b80739dec7056ab59dce3b86c9c5d8cba3d946f7b0279fa80c9664621b156b3`.
Rootless Podman uses the default rootless namespace rather than creating a
multi-gigabyte `keep-id` layer copy; capability drop, no-new-privileges,
offline search networking, and cgroup isolation remain enforced. The first
V13 replay preserved 609 basket-6 records, emitted 81 immutable plans, counted
zero replayed records as new trials, and entered the named ExtraTrees
finalization operation with about 7.1 GiB peak memory under the 36 GiB,
zero-swap profile. Confirmation remained unopened.

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

## 2026-08-25 - Suspend archive directional V2 for immutable evidence audit

### What we tested

The preregistered `archive-directional-gpu-v2` development family on point-in-time Binance archive baskets 4 and 6. The audit covered every durable trial record, failure, immutable basket-6 plan, available completed search report, registered controls, seeds, folds, and time-shift placebo. Basket 10 was not started.

### Current epistemic stage

`INCONCLUSIVE`. This is repeatedly opened, nonblind development evidence. It is not blind validation, prospective evidence, a tradability claim, portfolio alpha, an operational strategy, or live authorization.

### What happened

The campaign was drained after `finalize-market-state-direction-v1-6-extra-trees-robust-19-group` reached its boundary and was suspended before another durable trial. Basket 4 contains 1,184 attempts: 987 completed and 197 failed. Basket 6 contains 743 attempts: 742 completed and 1 failed, but no completed search result. Basket 10 contains zero attempts. The final audit recommendation is `INSUFFICIENT_EVIDENCE`.

### What we know

The basket-4 selected market-state and cross-asset specifications passed the registered time-shift integrity rule. Market-state showed positive aggregate forecast-loss differences for the same 15-minute outright-return ExtraTrees specification in baskets 4 and 6. These fold aggregates do not establish statistical significance. The complete cross-basket focused-V3 gate did not pass because basket 6 was unfinished and had no finalized placebo/search report; basket 10 was untested. Trade-flow records preserve 104 failed basket-4 attempts and no completed predictive evidence.

### What we suspect

The market-state result may justify writing a much smaller fresh V3 contract, but selection exposure, incomplete family accounting, source transfer, and absent dependence-aware inference can explain the apparent leader. This is a hypothesis for registration, not evidence that the mechanism works.

### What remains untested

Basket 10; complete basket-6 family accounting and placebo; dependence-aware and family-wide inference; historical-universe sufficiency for relative value; target-venue transfer; fees, spread, slippage, latency, funding, borrow, capacity, portfolio behavior, shadow execution, and operational recovery.

### Opened outcomes and periods that are no longer blind

The development period from `2020-01-01T00:00:00Z` through `2025-06-01T00:00:00Z` was repeatedly opened and cannot be reused as untouched confirmation. The V2 confirmation ledger remained empty and sealed.

### What data or authority would change the answer

A fresh preregistered period, complete point-in-time target-venue and historical-membership data, and reviewed inference/economic gates could change the research answer. No trading or capital authority was requested or granted.

### Next permitted action

Keep V2 suspended. If pursued, write and review one focused V3 lock around an exact mechanism and specification with fresh outcomes; do not launch it or open confirmation automatically.

### Immutable artifacts

- Final correction record: `/mnt/media/marketlab/artifacts/archive-alpha-v2/suspensions/evidence-audit-correction-20260825T225600Z.json`, SHA-256 `f5964b48c81388d8a24cfa8b2fef6c37687cdebcd90bc773384b3b6107e710eb`.
- Final ledger manifest: `/mnt/media/marketlab/artifacts/archive-alpha-v2/evidence-audit-v3/ledger-manifest.json`, SHA-256 `6a04f1e6ff99a820a90708558055f5e2918f29fd23828007839e549ff84a0d44`.
- Final audit JSON: `/mnt/media/marketlab/artifacts/archive-alpha-v2/evidence-audit-v3/audit.json`, SHA-256 `a07fbec0156327a68b2f9c457420be949fd6287d4fbfd1dc10bc11ceacd45117`.
- Final handoff report: `/mnt/media/marketlab/artifacts/archive-alpha-v2/evidence-audit-v3/audit.md`, SHA-256 `48ce34160ad92ba96f334c9648d01578584abacd5b082afb6441be1cf1544f35`.
- Superseded audit artifacts remain immutable and are linked by the correction chain; they must not be treated as final.
- Worker release: `/mnt/stack/marketlab/source-releases/marketlab-alpha-trial-worker-v13-20260824`.
- Worker image digest: `sha256:5b80739dec7056ab59dce3b86c9c5d8cba3d946f7b0279fa80c9664621b156b3`.

## 2026-08-25 - Freeze focused historical V3 and audit Hyperliquid source transfer

### What we tested

No confirmation outcome was opened. We froze the exact V2 basket-four market-state leader for a single-use Binance archive confirmation and separately applied that unchanged Binance model to an already-ended Hyperliquid candle period as a retrospective source-transfer diagnostic.

### Current epistemic stage

The Binance candidate is `FROZEN_CANDIDATE` and awaits explicit authorization to open its single-use confirmation. The Hyperliquid transfer candidate is `INCONCLUSIVE`. Neither candidate has a tradability, portfolio, paper, operational, or live claim.

### What happened

The final freeze binds the nine-field feature schema, 15-minute outright target, dynamic basket four, ExtraTrees configuration, seed, model artifact, baselines, confirmation panel, inference, acceptance thresholds, and implementation hash. The confirmation ledger remains empty. The Hyperliquid REST diagnostic recovered 16.53 observed calendar days within the fixed August request because rolling retention omitted the beginning of the requested period. It scored 18,871 rows without target-venue tuning.

### What we know

Hyperliquid transfer was descriptively negative: mean relative MSE improvement was `-0.0007132213` against the strongest market-only baseline and descriptive HAC `p=0.7203`. BTC was positive, while ETH, HYPE, and SOL were negative. This evidence does not establish direct source transfer and must not be used for local tuning. It does not reveal or determine the still-sealed Binance confirmation result.

### What we suspect

Venue-specific volume scale, participant composition, contract behavior, and the reconstructed quote-volume proxy may explain the transfer failure. Those explanations were not separately tested and do not justify revising this frozen candidate.

### What remains untested

The single-use Binance confirmation; a materially distinct venue-normalized mechanism; execution fees, spread, slippage, latency, capacity, portfolio behavior, shadow operation, and live operation.

### Opened outcomes and periods that are no longer blind

The Hyperliquid transfer observations from epoch `1786187700000` through `1787616000000` are open retrospective diagnostic evidence. The Binance period `2025-06-01T00:00:00Z/2026-08-01T00:00:00Z` remains sealed.

### What data or authority would change the answer

Explicit user authorization naming the final focused freeze SHA-256 permits the one-time Binance confirmation. No future-data wait is in scope. No capital or trading authority was requested or granted.

### Next permitted action

Review final freeze SHA-256 `2c33653130199327393c98b84f3962feb04825c374bd39c6ea6bbfe89a27277f` and request explicit authorization before creating the confirmation marker. Do not alter the frozen candidate or use the Hyperliquid result to retune it.

### Immutable artifacts

- Focused lock SHA-256: `fabd6dc9ac65846ba83e4467038e90d1fc55e3bff61c4705e4043bc0f70f1f3b`.
- Final focused freeze: `/mnt/media/marketlab/artifacts/archive-alpha-v2/focused-v3/frozen-v3.json`, SHA-256 `2c33653130199327393c98b84f3962feb04825c374bd39c6ea6bbfe89a27277f`.
- Focused implementation SHA-256: `3967a4c38d427f3409ad034affae5d8ca10647d31c8b2d24e2e92344c029b572`.
- Hyperliquid acquisition manifest SHA-256: `aaa24b31d292ade376470c377dd04b91d5a04721b4ac9233d4fdc04aa9f2ae51`.
- Hyperliquid transfer result SHA-256: `808d45f4e6124ab942f9a9f28fbdd1f4cdf6d1aa65c060d8dc8479aa57e592a5`.
- Earlier focused freeze engineering artifacts are superseded, remain immutable, and must not be used for confirmation.

## 2026-08-26 - Reject focused historical V3 after single-use confirmation

### What we tested

With explicit user authorization naming frozen SHA-256
`2c33653130199327393c98b84f3962feb04825c374bd39c6ea6bbfe89a27277f`,
we opened the single-use Binance confirmation for the exact focused V3
basket-four, 15-minute outright-return ExtraTrees specification. No feature,
model, baseline, inference rule, threshold, or subgroup changed after freezing.

### Current epistemic stage

`REJECTED`. The preregistered predictive-signal gate failed. No tradability,
portfolio, operational, paper, or live claim was tested or authorized.

### What happened

The confirmation scored 460,504 rows from the untouched period. Candidate MSE
was `0.00001724753237980223` versus `0.00001743711146581192` for the strongest
market-only baseline, a relative improvement of `0.01087216115933958`. The
candidate passed the primary-loss, minimum-relative-improvement, positive-month,
mandatory-BTC-and-ETH, and clean-development-placebo gates. It failed the
frozen dependence-aware Newey-West HAC gate: raw and adjusted `p` were
`0.10519928843783445`, above the registered `0.05` maximum. The frozen decision
is therefore `PREDICTIVE_SIGNAL_GATE_FAILED`.

### What we know

The aggregate effect size was positive, BTC and ETH were positive, and 13 of
14 calendar months were positive. Those observations do not override the
failed family-wide inference gate. This candidate version did not earn a
predictive-signal claim.

### What we suspect

There may be a weak or unstable conditional effect, but selection exposure,
serial dependence, venue specificity, or concentration in a small number of
periods could explain the observed aggregate improvement. These are new
hypotheses, not confirmation findings.

### What remains untested

A materially distinct signed mechanism; independent source transfer; fees,
spread, slippage, latency, funding, capacity, portfolio construction, shadow
operation, and recovery behavior. The failed candidate may not be rescued by
local parameter or subgroup search on this opened period.

### Opened outcomes and periods that are no longer blind

Binance outcomes from `2025-06-01T00:00:00Z` through
`2026-08-01T00:00:00Z` are permanently opened and cannot be reused as untouched
confirmation for a revised candidate. The earlier V2 development period was
already nonblind.

### What data or authority would change the answer

A materially distinct mechanism or information set and a fresh confirmation
period preregistered before outcome access could support a new candidate. No
capital or trading authority was requested or granted.

### Next permitted action

Preserve the rejection and use it only to design a separate generation-two
directional hypothesis. Do not retune focused V3 or reinterpret secondary
subgroups as confirmation.

### Immutable artifacts

- Confirmation result: `/mnt/media/marketlab/artifacts/archive-alpha-v2/focused-v3/confirmation-result.json`, SHA-256 `dae6cdc0025db03304a5a6e16228fc5b5998f0ea1a4681bcaee29b2985625068`.
- Frozen specification SHA-256: `2c33653130199327393c98b84f3962feb04825c374bd39c6ea6bbfe89a27277f`.
- Confirmation marker: `/mnt/media/marketlab/artifacts/archive-alpha-v2/confirmation-ledger/focused-v3/binance-market-state-direction-v3-confirmation-1.json`.

## 2026-08-26 - Reject social-variance defensive exposure replay

### What we tested

We preregistered a historical portfolio replay after July outcomes were already open. The replay compared fixed equal-weight exposure, de-risk-only sizing from the frozen market-only one-hour variance forecast, and the same sizing rule from the BLIND_VALIDATED social-attention variance forecast. It used ten Binance USDT perpetual assets, hourly decisions, actual official July funding archives, 5 and 10 bps turnover stresses, and annualized certainty equivalent at gamma 4.

### Current epistemic stage

REJECTED. This was an EXPLORATORY opened-outcome portfolio replay, not blind validation. No return-direction, tradability, portfolio-alpha, shadow, paper, operational, or live claim was earned or authorized.

### What happened

Across 744 hourly decisions and 7,440 asset-decision rows, social-aware sizing underperformed market-only sizing by -0.12241409927081981 annualized certainty-equivalent units at 5 bps and -0.12603050215198253 at 10 bps. Its hourly 5% expected shortfall and maximum log drawdown were also worse at both costs. The deterministic moving-block 95% intervals for the certainty-equivalent differences included zero. The exact prespecified phase-two gate failed at both costs, so the volatility-conditioned signed-social family was not run.

### What we know

The validated social-attention forecast contains one-hour variance information, but this specific de-risk-only mapping did not improve portfolio utility over the market-only variance scaler in the consumed July period. Lower forecast loss does not imply better exposure timing. More model search is not justified by this result.

### What we suspect

The social scaler reduced volatility slightly but incurred more turnover and may have reduced exposure before favorable returns. This is a descriptive explanation, not a tested directional mechanism, and it cannot be rescued by retuning on July.

### What remains untested

A materially distinct portfolio mapping; observed spread, slippage, latency, capacity, and fills; return direction; prospective shadow behavior; paper execution; and operational recovery remain untested.

### Opened outcomes and periods that are no longer blind

Binance outcomes from 2026-07-01T00:00:00Z through 2026-08-01T00:00:00Z were already open and were additionally consumed for this portfolio replay. They cannot support a blind claim for a revised strategy.

### What data or authority would change the answer

A materially distinct economic mechanism, separately registered before evaluation, could justify another bounded historical test. Fresh authority would still be required for shadow, paper, or live actions. Waiting for future days is outside this historical research scope.

### Next permitted action

Preserve this rejection. Do not run the dependent signed-social phase under this program. Return to strategy generation using the durable variance finding without assuming it translates into direction or portfolio utility.

### Immutable artifacts

- Frozen replay: /mnt/media/marketlab/artifacts/social-variance-defensive-exposure-v1-run8/frozen.json, SHA-256 5d161ad3aba41a35c98422c17660ef5fcbab8aa66b0317a6e2d9205e3aed784f.
- Official funding manifest: /mnt/media/marketlab/artifacts/social-variance-defensive-exposure-v1-run8/funding/manifest.json, SHA-256 e14ff8427f5f77fbc5715f1913ba2c5e5de7f76db263d0ddf7d274d627c0f01e.
- Portfolio result: /mnt/media/marketlab/artifacts/social-variance-defensive-exposure-v1-run8/result.json, SHA-256 7acfb54e99609e94da385285a78215e7aa8496542f7cddfbe9a85a025338fccf.
- Portfolio rows: /mnt/media/marketlab/artifacts/social-variance-defensive-exposure-v1-run8/result.rows.jsonl, SHA-256 cfefba1a3e376a0271b509855887b5f2d92e6c36c20cb2a9769bd87a0ae779b1.
- Evaluator implementation SHA-256: eb7f7898380103fd048582a28d15f5753cb9604870ef9c6231028d0859da7f44.

## 2026-08-26 - Register successor-safe next-generation alpha program

### What we tested

No market outcome was tested or opened. The alpha workspace was reconfigured
around two parallel P0 data-feasibility audits: event-conditioned signed social
direction and delta-neutral funding/basis carry.

### Current epistemic stage

The new event-conditioned social candidate is DATA_FEASIBILITY. The existing
funding-basis carry candidate remains DATA_BLOCKED until a point-in-time source
audit changes that evidence. No predictive, tradability, portfolio, paper, or
live claim was created.

### What happened

Successor tasks now bind priority, candidates, deliverables, acceptance
criteria, claim surfaces, resource limits, and outcome access. Default ready
routing exposes only the highest runnable priority. Two data audits may proceed
in parallel, while model execution remains limited to one process, two BLAS
threads initially, sixteen variants by default, and no unjustified GPU.

### What we know

Unconditional social direction and simple variance-aware exposure mappings
failed, while one-hour social-attention variance remains BLIND_VALIDATED. Funding
alone did not test carry. The next permitted work must therefore add a genuine
signed event mechanism or reconstruct both sides of delta-neutral carry.

### What we suspect

Polarity surprise or disagreement change may supply sign only during attention
shocks. Funding and contemporaneous basis may identify compensation without
price direction. Neither mechanism has passed its data-feasibility gate.

### What remains untested

Untouched historical social overlap, the registered eight-variant development
family, synchronized basis and both execution legs, delta-neutral replay,
single-use confirmation, observed execution, paper operation, and live
operation remain untested.

### Opened outcomes and periods that are no longer blind

No outcome was opened by this reconfiguration. All previously registered
opened periods remain consumed and cannot be reused as confirmation.

### What data or authority would change the answer

Existing, already-ended, provenance-preserving historical periods may advance
the program. Future-data waiting is out of scope. Historical confirmation may
open autonomously only after the machine-valid frozen and ledger gates pass.
Capital and trading authority remain absent.

### Next permitted action

Run the two P0 manifest and source audits in parallel. Do not begin model
development, carry replay, or confirmation until their durable task blockers
are completed with linked evidence.

## 2026-08-27 - Complete next-program social and carry data audits

### What we tested

No market target or confirmation outcome was opened. The social audit inspected
only immutable feature manifests and the opened-outcome registry. The carry
audit inspected official Binance monthly prefix listings and six
checksum-verified January 2025 BTC archives covering funding, perpetual, mark,
index, premium, and spot one-minute data.

### Current epistemic stage

Event-conditioned social direction is DATA_BLOCKED. Funding-basis carry remains
DATA_BLOCKED. Neither candidate earned a predictive, tradability, portfolio,
paper, operational, or live claim.

### What happened

The only materialized social feature manifests end at 2026-07-01 and
2026-08-01. Their periods overlap exact opened outcomes, while older
unknown-boundary evidence prevents proving unregistered slices untouched. No
target-bearing feature row was read.

Official futures prefixes contain fundingRate, klines, markPriceKlines,
indexPriceKlines, premiumIndexKlines, trades, aggregate trades, and bookTicker.
Official spot prefixes contain klines, trades, and aggregate trades, but no
spot bookTicker or book snapshots. Sampled funding and five price archives
passed official checksums; every one-minute price sample contained 44,640 rows
with a maximum 60-second gap. Historical exchange-rule versions, fee schedules,
and borrow availability are also absent from the official monthly prefixes.

### What we know

There is no untouched materialized historical period for the registered social
candidate. Funding and basis are reconstructable, but executable net carry is
not: both observed execution legs and historical rule, fee, and borrow state
are incomplete.

### What we suspect

A separately sourced historical social archive or spot quote archive could
change feasibility. Conservative synthetic costs would permit an exploratory
illustration, but would not satisfy the registered executable carry mechanism.

### What remains untested

The eight-variant social family, any social confirmation, delta-neutral carry
replay, observed two-leg fills, capacity, paper operation, and live operation
remain untested.

### Opened outcomes and periods that are no longer blind

No outcome was opened by either audit. Previously registered periods remain
consumed.

### What data or authority would change the answer

A trustworthy already-ended social feature archive absent from the
opened-outcome inventory could reopen social development. Historical spot quote
or book data plus rule, fee, and borrow vintages could reopen carry. Waiting for
future days remains out of scope.

### Next permitted action

Preserve both blockers. Do not run the social model family or carry replay. The
workspace should route to the next independent highest-priority task.

### Immutable artifacts

- Combined data-feasibility audit:
  /mnt/media/marketlab/artifacts/next-program-audits/data-feasibility-20260827/audit.json,
  SHA-256 6764b2afc00345bf2d09161e8f01b07ecd326fccd0f1a721f411c059e4d2410a.
- The artifact directory contains fourteen exact source objects totaling
  approximately 7.2 MB, including official listing XML, archive bytes, and
  checksum sidecars.

## 2026-08-27 - Register shared historical cost-model contract

### What we tested

No market outcome was opened. We audited the cost and execution assumptions in
the archive directional evaluators, focused V3, social-variance exposure replay,
funding diagnostic, observed-book executor, capacity analyzer, and paper
promotion gate.

### Current epistemic stage

No candidate stage changed. The audit is governance evidence:
COST_CONTRACT_REGISTERED. It creates no predictive, tradability, portfolio,
paper, operational, or live claim.

### What happened

Archive development and focused V3 are forecast-only. The social-variance
replay used actual Binance funding and fixed 5 and 10 basis-point turnover
stresses, but no observed spread, slippage, latency, borrow, or capacity. The
funding diagnostic uses current observed L2 only as a non-historical capacity
diagnostic and explicitly fabricates no fills. The engine contains reusable
post-latency observed-book sweeping, fee, partial-fill, and displayed-depth
controls, but those components were not connected to the historical studies.

### What we know

Forecast-only, stressed historical replay, and observed-execution replay are
different evidence gates. Fixed costs are stresses, not fills. There is no
defensible universal basis-point default across candidates or venues.

### What we suspect

Connecting historical point-in-time books and rule vintages to the existing
observed-book executor could support future tradability evaluation. Current
data coverage does not support that connection for the blocked carry candidate.

### What remains untested

Historical observed-book execution for every prior candidate, actual
post-latency fills, historical fees and borrow, multi-leg capacity, paper
operation, and live operation remain untested.

### Opened outcomes and periods that are no longer blind

No outcome was opened by this audit.

### What data or authority would change the answer

Historical causally available books and point-in-time fee, funding, borrow, and
rule state can advance a candidate from stressed replay to observed execution.
Paper and live authority remain separate.

### Next permitted action

Every future candidate must select and freeze one level from
research/alpha/cost-model-policy.json. Tradability and paper gates require
observed-execution replay and doubled-cost survival.

### Immutable artifacts

- Cost-model audit:
  /mnt/media/marketlab/artifacts/next-program-audits/cost-model-audit-20260827/audit.json,
  SHA-256 62c435fd77129e92db8f1a2307cdc9a31e9395266e4a7bca6330b16f73b1aea9.

## 2026-08-27 - Stop event-conditioned social V1 at signed-event coverage gate

### What we tested
We reused the explicitly contaminated November 2025 through June 2026 Bluesky/Binance period to rematerialize attention shock, polarity surprise, disagreement change, and one-hour/four-hour targets. Coverage ran before any model fit.

### Current epistemic stage
DATA_BLOCKED. Availability-proxy timestamps, the survivor universe, and opened outcomes cap this evidence at EXPLORATORY. No predictive or trading claim was earned.

### What happened
The panel contains 57,515 rows. Polarity surprise exceeded 100 acted rows in every fold. Disagreement change produced 87 acted rows in fold one versus the registered minimum of 100. Zero variants were fit and prospective activation remains false.

### What we know
The joint family cannot be evaluated under its frozen coverage rule. Lowering the rule or retaining only polarity now would be post-outcome adaptation.

### What we suspect
A polarity-shock-only mechanism may be feasible, but it needs a new candidate and contract before target rows are read again.

### What remains untested
All eight variants, predictive loss, inference, costs, prospective confirmation, and execution.

### Opened outcomes and periods that are no longer blind
Binance one-hour and four-hour returns from 2025-11-03T00:00:00Z through 2026-07-01T00:00:00Z were reused and cannot be confirmation outcomes.

### What data or authority would change the answer
A larger existing signed archive could restore V1 coverage. A separately preregistered polarity-only candidate can proceed as bounded discovery. Future waiting remains prohibited until development, freeze, and outcome sealing pass.

### Next permitted action
Run polarity-shock-direction-v2-contract. Do not lower V1 coverage, drop disagreement from V1, or start a prospective clock.

### Immutable artifacts
Coverage report: /mnt/media/marketlab/artifacts/event-conditioned-social-direction-v1-20260827-run1/report.json, SHA-256 0f140ad4120b4bc6f1331c01009df89a6fa1bdc62e57708c48b718662899bf5b.
Materialized panel: /mnt/media/marketlab/artifacts/event-conditioned-social-direction-v1-20260827-run1/panel.jsonl, SHA-256 d4509e2541e46559f0685a416f141eaa595b9360cd15f4018f1837c2785d3bbb.

## 2026-08-27 - Register polarity-shock directional V2

### What we tested
No target-bearing row or market outcome was read. We preregistered a materially distinct polarity-surprise-only candidate after disagreement coverage blocked V1.

### Current epistemic stage
DATA_FEASIBILITY. The planned historical development is explicitly contaminated and can earn at most EXPLORATORY evidence.

### What happened
The new family contains exactly four variants: one-hour and four-hour horizons crossed with a fixed regularized linear interaction model and one shallow histogram-boosted tree. Attention is gated at the training-fold 90th percentile. The contract freezes coverage, stability, inference, baseline, and 5/10 basis-point stress gates.

### What we know
Polarity surprise had adequate coverage in the prior V1 audit. This does not establish predictiveness and does not permit interpreting the V1 subgroup as a result.

### What we suspect
Polarity relative to an asset's trailing social baseline may supply sign during abnormal information arrival. This mechanism is distinct from unconditional fifteen-minute polarity and from disagreement change.

### What remains untested
All four models, forecast loss, asset and fold stability, dependence-aware inference, economic stress, Hyperliquid transfer, prospective shadowing, and execution.

### Opened outcomes and periods that are no longer blind
No outcome was opened by registration. The November 2025 through June 2026 panel remains consumed and may be used only for explicitly contaminated development.

### What data or authority would change the answer
A passing bounded development result may authorize freezing one exact candidate. Only a frozen candidate with operational outcome sealing may activate the 30-day/1,000-event prospective gate.

### Next permitted action
Run polarity-shock-direction-v2-development once with one process, two BLAS threads, four variants, and no GPU.

### Immutable artifacts
No outcome-bearing artifact was created by this metadata-only registration.

## 2026-08-27 - Reject polarity-shock directional V2

### What we tested
The exact four-variant polarity-surprise family crossed 1h/4h horizons with ridge and shallow histogram boosting on five purged chronological folds.

### Current epistemic stage
REJECTED. This was availability-biased retrospective discovery capped at EXPLORATORY.

### What happened
All four variants had negative aggregate forecast-loss improvement and zero positive folds. The least-negative result was 1h ridge at -0.000001764 mean loss improvement, two positive assets, and negative net mean return at 10 bps. No variant passed all gates.

### What we know
Attention-gated polarity surprise did not improve directional return forecasts over the strongest registered baselines in this development panel.

### What we suspect
Social polarity may be incorporated too quickly, too noisy under historical search capture, or unrelated to sign even when attention predicts variance. These are hypotheses, not reasons to retune.

### What remains untested
A materially different signed information source, Hyperliquid-native direction, prospective confirmation, and execution.

### Opened outcomes and periods that are no longer blind
The 2025-11-03 through 2026-07-01 Binance 1h/4h outcomes are consumed for V2 development.

### What data or authority would change the answer
Only a materially different mechanism or information set with a new candidate can reopen directional social research.

### Next permitted action
Preserve rejection and return workspace routing to independent mechanisms. Do not freeze or start prospective waiting.


## 2026-09-08 — Bounded cross-market exploration infrastructure

Current expanded research stage: `DATA_FEASIBILITY`. User-directed scope now
includes balanced conventional-market, onchain, prediction-market and dollar
stablecoin feasibility work. Long tail refers to underexamined mechanisms.
The proposed US stablecoin-policy expansion is a thesis for dated primary-source
verification, not an established predictive result.

The new local experiment supervisor fixes the maximum total budget at 11h45m,
including a reporting reserve, and preserves deadline and family trial accounting
across interruptions. New dispatch of the incompatible persistent archive V2
campaign is disabled; its frozen locks, prior outcomes and failures are unchanged.
This is an operational change, not a reclassification of any prior candidate.

What was tested: local driver access and software contracts for deadlines,
resource admission, interruption, evidence finalization and workspace routing.
What is known: the RTX 3060 permits local CUDA driver allocation; the existing
social-model environment contains CPU-only Torch. No GPU model performance claim
has been tested. No new market or sealed confirmation outcomes were opened.
Next permitted action: claim and start one of the four equally prioritized
bounded historical source-feasibility tasks, after the implementation checks pass.

Implementation verification: workspace validation, 35 workspace/supervisor tests,
91 alpha-model tests, 8 social-model tests, shell checks, and `./gradlew check`
passed. Parent-process line coverage measured 73% for the new supervisor and
63% for the workspace tool; subprocess paths are tested but not included in that
coverage measurement. The registration evidence inventory links the immutable
software-validation artifact. The four source audits remain READY and unexecuted.

## 2026-09-10 — Practitioner-informed roadmap registration

Decision: prioritize corporate-event terms (rank 10), prediction resolution
(rank 20), and stablecoin redemption constraints (rank 30) within P1. Broad
source audits remain rank 100. Mechanisms are IDEA; three new, unexecuted
candidates are DATA_FEASIBILITY. See research/inspiration/README.md and the
practitioner-roadmap-registration-2026-09-10 evidence record.

What we tested: literature and operator-document orientation only. We know the
sources describe contractual, financing and operational constraints. We suspect
these are useful search directions; data coverage and net economics remain
untested. Historical stories are already exposed discovery examples, not blind
confirmation. No experimental outcome dataset or sealed period was opened.
Next permitted action: a two-hour source audit, with a 15-minute reporting reserve,
then a separately registered successor or blocker. Long holding/data horizons
do not extend the original sub-12-hour experiment deadline. No research was
launched by this registration.

Implementation verification: 18 workspace, 22 supervisor and 29 automation tests
passed, along with workspace validation and `./gradlew check`. The registration
evidence links the immutable software-validation artifact. These checks establish
software behavior, not source coverage or strategy performance.
