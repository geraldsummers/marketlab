# Social-variance defensive exposure result

## Identity

- Candidate ID: social-variance-defensive-exposure-v1
- Frozen plan SHA-256: 640c45592f26489d31fd33cd1abca465a2448cd6598f670376a539a0cc689f01
- Source revision: uncommitted workspace; implementation SHA-256 eb7f7898380103fd048582a28d15f5753cb9604870ef9c6231028d0859da7f44 is authoritative
- Frozen artifact SHA-256: 5d161ad3aba41a35c98422c17660ef5fcbab8aa66b0317a6e2d9205e3aed784f
- Result artifact SHA-256: 7acfb54e99609e94da385285a78215e7aa8496542f7cddfbe9a85a025338fccf
- Evaluation timestamp: 2026-08-26

## Outcome

- Prior stage: EXPLORATORY
- New stage: REJECTED
- Primary decision: EXPLORATORY_GATE_FAILED
- Primary metric and baseline: annualized certainty equivalent at gamma 4 versus the frozen market-only variance scaler
- Dependence-aware inference: hourly utility differences used Newey-West HAC lag 24 and a deterministic 2,000-sample moving-block bootstrap; the 95% intervals included zero at both costs

## What is known

The social-aware scaler underperformed market-only sizing by -0.1224140993 annualized certainty-equivalent units at 5 bps and -0.1260305022 at 10 bps. It also had worse 5% hourly expected shortfall and maximum log drawdown at both costs. The prespecified gate failed at both costs, so phase two is not authorized.

The replay used 744 hourly decisions across ten assets, actual official Binance funding archives, and 5 and 10 bps turnover stresses. This result is not blind validation because July outcomes had already been opened before the strategy was designed.

## What is suspected

The social forecast slightly reduced volatility but changed exposure more often and de-risked at times when subsequent returns were favorable. This timing explanation is descriptive and was not separately tested.

## What remains untested

Return direction, observed spread and slippage, latency, fillability, capacity, prospective shadow behavior, paper execution, and live operation remain untested. No tradability, portfolio-alpha, paper, or live claim was earned or authorized.

## Next permitted action

Preserve this rejection and do not execute the conditional signed-social phase that depended on this gate. A new strategy requires a materially distinct mechanism, a bounded preregistration, and an evidence path that does not relabel the consumed July period as blind.
