# Literature and hypothesis review protocol

## Inclusion standard

A candidate must make a falsifiable claim about a mechanism, an information set
available before a declared decision time, a target, and a horizon. Evidence
may come from peer-reviewed work or a strong institutional working paper, but
publication status and independent replication maturity are recorded
separately.

The review includes favorable, null, and contradictory evidence. A forecasting
algorithm is an estimator rather than a theory unless it encodes an explicit
economic mechanism. Generic indicators, chart shapes, stock-to-flow, “max
pain,” and unconstrained parameter sweeps do not enter the initial registry.

## Evidence extraction

Each research card records:

1. Citation, publication status, original market/sample, and data provenance.
2. Mechanism, predicted sign, target, horizon, eligible universe, and failure
   condition.
3. Exact information availability and the paper's transformations/model.
4. Supportive and critical replications, including post-publication decay.
5. Required data fields, resolution, minimum history, costs, and licensing.
6. Original methodology reproduction and separately preregistered crypto
   adaptation.
7. Fixed benchmark, loss, inference, execution, robustness, and promotion
   rules.
8. Every tried parameter/model variant, including failures.

## Prioritization

Candidates are ranked before confirmation results using:

- mechanism clarity;
- peer-review and independent replication strength;
- Hyperliquid relevance;
- real point-in-time data feasibility;
- horizon relative to latency and costs;
- survivorship, timestamp, and data-mining risk;
- incremental information versus theories already tested.

The final holdout never influences this ranking. If results alter the next
research choice, that adaptive choice is appended to the decision log and its
trials remain in the global multiple-testing family.

## Statistical standard

- Chronological expanding/rolling folds; no shuffled market time series.
- Purging and embargo where forward labels overlap.
- All scalers, selectors, and hyperparameters fit inside training folds.
- A sealed chronological confirmation set evaluated once per frozen hypothesis
  version.
- HAC or dependence-aware block inference for serially dependent losses.
- Hansen SPA/FDR family correction, with deflated Sharpe and probability of
  backtest overfitting as secondary diagnostics.
- Forecast loss reported before strategy returns.
- Robustness across time regime, venue, asset, liquidity, latency, capacity,
  and doubled costs.

This standard is motivated by the high non-replication rate of published
anomalies ([Hou, Xue, and Zhang, 2020](https://academic.oup.com/rfs/article/33/5/2019/5236964)),
post-publication decay ([McLean and Pontiff, 2016](https://onlinelibrary.wiley.com/doi/abs/10.1111/jofi.12365)),
and the multiple-testing threshold problem
([Harvey, Liu, and Zhu, 2016](https://academic.oup.com/rfs/article-abstract/29/1/5/1843824)).

