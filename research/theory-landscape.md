# Academic theory landscape

This is the initial decision record, not a claim that every listed effect is
real or tradable. “Priority” measures information value and target relevance
before Marketlab sees confirmation results.

## Tier 0: mandatory controls

| Family | Claim and horizon | Evidence and caveats | Required real data |
|---|---|---|---|
| No-predictability / random walk | Current price, zero excess return, or a historical mean is hard to beat at the next declared horizon. | Rejection of a strict random walk does not itself imply a profitable forecast; use drift and buy-and-hold controls ([Lo and MacKinlay, 1988](https://academic.oup.com/rfs/article-abstract/1/1/41/1601244)). | Exactly the same point-in-time sample, target, and costs as the candidate. |
| Volatility persistence / HAR-RV | Daily, weekly, and monthly realized-variance components forecast the next hour, funding interval, or day. | A durable risk forecast rather than return-direction alpha; compare using QLIKE and persistence ([Corsi, 2009](https://ideas.repec.org/a/oup/jfinec/v7y2009i2p174-196.html), [Patton, 2011](https://ideas.repec.org/a/eee/econom/v160y2011i1p246-256.html)). | Clean intraday trade/mid returns, explicit gaps, and 24/7 annualization. |

## Tier 1: highest-priority tests

### Perpetual funding, basis, and constrained arbitrage

Split this into three hypotheses: next-funding prediction, executable
perp/index or perp/spot basis convergence, and hedged carry. Funding is partly
mechanical; profitable carry is compensation for margin, liquidity, crash,
stablecoin, and venue risk rather than free yield. Horizons range from the
funding interval to months. Required inputs include the historical rule version,
premium observations, mark/oracle/index, spot and perp quotes, actual payment
timestamps, fees, borrow, OI, and margin terms. See
[He, Manela, Ross, and von Wachter](https://arxiv.org/abs/2212.06888) and
[Schmeling, Schrimpf, and Todorov](https://pubsonline.informs.org/doi/abs/10.1287/mnsc.2024.05069).

Primary hazards are finalized-funding leakage, applying today's clamp
historically, mark-versus-fill confusion, omitted borrow/funding legs, and
liquidation survivorship. This is the flagship Hyperliquid-native family.

### Time-series momentum

Past own return predicts continuation over intermediate horizons followed by
longer reversal, potentially through underreaction and trend-following flows.
The original futures result uses roughly one-to-twelve-month lookbacks and
holding periods ([Moskowitz, Ooi, and Pedersen, 2012](https://www.sciencedirect.com/science/article/pii/S0304405X11002613));
crypto evidence reports daily/weekly momentum and attention effects
([Liu and Tsyvinski](https://www.nber.org/papers/w24877)).

The effect is contested: asset-by-asset and out-of-sample evidence can be weak,
and pooled results may resemble historical-mean exposure or volatility scaling
([Huang et al., 2020](https://www.sciencedirect.com/science/article/abs/pii/S0304405X19301953)).
The reproduction therefore reports raw forecasts, long and short legs, drift,
and volatility scaling separately before testing any shorter crypto adaptation.

The executable `hyperliquid-btc-time-series-momentum@1.0.0` plan is one such
separately identified adaptation. It retains the published 365-day lookback and
30-day target, fixes the universe to the Hyperliquid BTC perpetual, and uses a
three-year quality-valid history with historical-mean and zero-return controls.
It is not presented as the five-year, multi-venue reproduction.

### Order-flow and queue imbalance

Causally prior additions, cancellations, trades, and bid/ask queue imbalance
may predict the next mid move over event time or milliseconds-to-seconds.
Impact is conditioned on depth and tick size
([Cont, Kukanov, and Stoikov](https://academic.oup.com/jfec/article-abstract/12/1/47/816163),
[Cont and de Larrard](https://epubs.siam.org/doi/abs/10.1137/110856605)).

Faithful tests require complete ordered L2 events, sequence continuity,
aggressor side, exchange and receipt clocks, and multiple depth levels.
Hyperliquid's public feed is snapshot-based, so snapshot queue imbalance is a
separate adaptation and is never mislabeled as event-level OFI. Contemporaneous
target overlap, batching, spoofed liquidity, and imaginary queue fills are
fatal errors.

### Liquidity-conditioned reversal and liquidity forecasts

Temporary price concessions caused by one-sided flow can reverse as liquidity
recovers; expected compensation rises in stressed liquidity states
([Nagel, 2012](https://www.nber.org/papers/w17653)). Spread, depth, impact, and
resilience are also forecast targets in their own right.

Use seconds-to-minutes after a preregistered return/flow/depth shock, with
observed bid/ask and books. Generic “oversold” or RSI reversal is excluded.
Bid-ask bounce, stale marks, threshold mining, permanent informed flow, and
unrealistic maker fills are the key falsification risks.

### Cross-venue price discovery and lead-lag

Fragmented venues may incorporate common information at different speeds due
to liquidity, fees, participant mix, and capital constraints
([Makarov and Schoar, 2020](https://www.fmg.ac.uk/publications/academic-journals/trading-and-arbitrage-cryptocurrency-markets)).
The target is a subsequent executable Hyperliquid mid/trade return, never its
oracle/mark, because Hyperliquid's oracle already incorporates major venues.

Tradability requires simultaneous production WebSocket capture with exchange
and local receive clocks. Historical archives can support discovery only.
Clock skew, network jitter, stablecoin/contract mismatches, and latency arms
races receive explicit sensitivity tests.

## Tier 2: follow after core timing and L2 validation

| Family | Mechanism / target | Main hazards |
|---|---|---|
| Volume-conditioned continuation/reversal | Signed unexpected volume distinguishes persistent informed flow from temporary inventory pressure ([Llorente et al., 2002](https://web.mit.edu/wangj/www/pap/LlorenteMichaelySaarWang02.pdf)). Minutes to days. | Contemporaneous volume leakage, wash volume, arbitrary surprise windows. |
| Leverage and liquidation cascades | OI, funding, thin depth, and an adverse move can precede forced-flow tail events through margin/liquidity spirals ([Brunnermeier and Pedersen, 2009](https://pages.stern.nyu.edu/~lpederse/papers/Mkt_Fun_Liquidity.pdf)). | Liquidations are often effects; delayed/incomplete feeds and unknown cross-margin state. |
| Cross-sectional momentum / crypto factors | Relative winners and size/momentum factors forecast weekly-to-monthly relative returns ([Liu, Tsyvinski, and Wu, 2022](https://www.nber.org/papers/w25882)). | Present-universe backfill, delistings, revised supply, microcap/illiquidity dominance. |
| Same-asset relative value and pairs | Economically linked contracts or close substitutes can reconverge after temporary divergence ([Gatev, Goetzmann, and Rouwenhorst, 2006](https://academic.oup.com/rfs/article-abstract/19/3/797/1646694)). | O(N²) search, structural breaks, in-test hedge ratios, two-leg funding/execution. |
| Volatility-managed exposure | Lower exposure during forecast high volatility may improve risk-adjusted returns. | Critical replications find unstable benefits; never credit sizing as return alpha ([Cederburg et al., 2020](https://ideas.repec.org/a/eee/jfinec/v138y2020i1p95-117.html)). |

## Tier 3: structured external information

- **On-chain adoption/security:** days-to-months network use, fees, supply, and
  security may inform valuation. Address/entity ambiguity, Sybil activity,
  revisions, and price-denominated circularity make this lower priority.
- **Option-implied information:** Deribit/CME surfaces may forecast
  Hyperliquid BTC/ETH volatility and tail state. Stale wide quotes, maturity
  interpolation, and physical-versus-risk-neutral variance must be separated.
- **Macro and scheduled events:** use vintage releases and exact availability
  timestamps; treat these initially as regime controls because crypto evidence
  is unstable across eras.
- **Hawkes intensity:** self-exciting trades, cancels, jumps, and liquidations
  may forecast event intensity after complete event data and simpler OFI/HAR
  baselines are proven.

Text, news, and social signals are not in the first program because immutable
publication timestamps, revisions, licensing, duplicates, and bot identity are
not yet controlled.

## Frozen initial order

1. Data audits, total-return accounting, nulls, persistence, and HAR-RV.
2. Funding forecast, basis convergence, hedged carry, and paper-faithful
   time-series momentum including its critical replication.
3. Event/snapshot L2 quality, OFI/queue imbalance, liquidity-conditioned
   reversal, and spread/depth/resilience forecasts.
4. Synchronized cross-venue lead-lag.
5. Tier 2, then Tier 3, chosen by preregistered evidence and data readiness.
