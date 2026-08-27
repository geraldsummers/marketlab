# Retrospective social backfill results

This file makes the completed retrospective discovery screen visible in the
repository without copying its immutable report or raw evidence into Git.

The registered envelope is stored on the research host at
`/mnt/media/marketlab/raw/social-backfill-v2/analysis/report.json`. It points to
the content-addressed report
`objects/e3/e30aef8ec930fb86604ef2170ae7fca49b69dca024b674317298664fce43845c.json`
with SHA-256
`e30aef8ec930fb86604ef2170ae7fca49b69dca024b674317298664fce43845c`.
The analysis-lock SHA-256 is
`38183371615c813a6153bc4ec4bd96ebfa31fb9e3f5dca913fab19c7ccaf9cf9`.

The report is classified `RETROSPECTIVE_DISCOVERY_ONLY`. It used bounded
historical Bluesky search capture and Binance perpetual labels, did not evaluate
execution costs, and cannot confirm a Hyperliquid or trading claim.

| Candidate | Holdout improvement over primary control | Raw p | Holm p | Discovery screen |
|---|---:|---:|---:|---|
| Social attention → hourly variance | `0.00007282294322224655` | `0.776812876917456` | `1.0` | Fail |
| Social disagreement → hourly variance | `-0.00012089146418490584` | `0.44783880310857427` | `1.0` | Fail |
| Social polarity → fifteen-minute return | `-1.9584902292702448e-10` | `0.7341559964752804` | `1.0` | Fail |
| Cross-sectional attention → daily return | `-0.0000026532644982374443` | `0.188804678100158` | `0.755218712400632` | Fail |

The later functional attention search is a separate multivariate nonlinear
program. Its boosted-tree one-hour variance candidate passed blind July, while
its return targets and one-day variance target did not. Those outcomes are in
the [decision log](decision-log.md).
