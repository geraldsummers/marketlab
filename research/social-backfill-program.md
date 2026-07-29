# Retrospective social backfill discovery program

This program accelerates falsification, not confirmation. It freezes 270 days
from 2025-10-04 through 2026-06-30 and retrieves only real Bluesky posts and
real Binance USDT-M perpetual minute archives. Exact response and archive bytes,
request parameters, hashes, historical publisher/index clocks, and the later
local retrieval clock are retained.

Bluesky `indexedAt` is used as a historical availability proxy. It is not a
locally observed historical receipt time. Binance is a cross-venue label source,
and the fixed 2026-W31 Hyperliquid top-ten set has survivorship bias. This phase
also has only one historical social venue and no historical observed execution
book. It can reject weak theories cheaply, but favorable results cannot confirm
the v2 plans, authorize paper trading, or shorten their sealed holdout.

The period is divided into 30 feature-warmup days, 120 training days, 56
development days, a common 60-day retrospective holdout, and four unused tail
days. The machine-readable registration is
`social-backfill-program.lock.json`.

Before any development or holdout result was inspected, the exact retrospective
feature, model, split, control, inference, and multiplicity rules were frozen in
`social-backfill-analysis.lock.json`. That analysis lock does not alter the
already frozen acquisition program or its output root.

Exchange archive gaps are never interpolated. They are counted in each asset
manifest, and any incomplete fifteen-minute bar touching a gap is excluded.

Bluesky cursors are not used because the public AppView rejects historical
cursor requests and emits a cursor even for a one-post interval. The capture
therefore queries fixed 30-minute UTC windows for BTC, ETH, SOL, and XRP and
fixed one-hour windows for the remaining symbols. A response with at least 90
posts is recursively subdivided, with saturation at one-second resolution
failing closed. This bounds truncation risk but cannot prove archive
completeness; the resulting data is explicitly a historical search capture.
