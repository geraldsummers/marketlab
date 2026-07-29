# Prospective social and news program v2

Status: active preregistration; D0 starts only after all required source,
market-universe, and model readiness checks pass. V1 was superseded before D0
because its required local Farcaster node was unavailable. Its lock and status
remain preserved in `social-program-v1.lock.json` and `social-program-v1.md`.

## Data and causal policy

The program uses only observations actually received from credential-free
public sources. Bluesky Jetstream, Nostr public relays, GDELT GKG, and reviewed
crypto RSS feeds are mandatory. Mastodon hashtag timelines are a robustness
sample. Farcaster is deliberately excluded from v2; X, Reddit, StockTwits, and
CryptoPanic remain excluded because the approved program does not have a free,
accountless, stable interface for them.

Raw response/frame bytes, publisher time, local receipt time, model-scoring
time, signatures or signed identifiers, source revision, and model hashes are
retained. Features are ordered by the latest causal availability time, never
the publisher timestamp. Nostr events undergo canonical-id and BIP-340
verification.

Publisher language tags are honored when present. Untagged text is accepted
only when the version-locked offline Lingua detector identifies English.
Social text uses the locked CardiffNLP Twitter RoBERTa model; news text uses
the locked ProsusAI FinBERT model. File hashes and upstream revisions are in
`sentiment-models.lock.json`.

## Frozen feature policy

- identical text is counted once per source in a rolling 24-hour window;
- one author contributes at most three messages per asset per hour;
- polarity and negativity are averaged within source before sources are
  equally weighted;
- attention normalization uses only prior complete hourly bins from 30 days;
- a composite normalized score requires at least two sources;
- cross-sectional ranks use the contemporaneous point-in-time universe.

The universe is selected on deployment and then each Monday at 00:00 UTC from
the ten eligible Hyperliquid perpetuals with the greatest trailing 30-day
notional reconstructed from completed daily candles. An asset needs at least
90 days of daily history. Selection snapshots are immutable, hash-addressed,
and effective only after selection completes.

## Schedule and tests

The common schedule is D0–4 stabilization, D4–34 warmup, D34–154 training,
D154–210 four expanding 14-day development tests, and D210–270 one sealed
holdout. The five registered plans test:

1. social attention → next-hour realized variance;
2. social disagreement → next-hour realized variance;
3. social polarity → next-15-minute return;
4. news negativity → next-day realized variance;
5. cross-sectional attention → next-day return.

All five confirmation p-values belong to one Holm family. No model, source,
window, universe, sign, control, or execution rule may change after D4.
Return tests pay observed BBO/L2 execution costs with 500 ms latency. A
confirmed return theory still requires 90 days of paper trading. Nothing in
this program authorizes live trading.

`social-program.lock.json` is the machine-readable v2 preregistration. The
program clock stores its SHA-256 and refuses to continue if the lock changes.
Each program has an isolated state directory so the v1 waiting state and v2
clock are independently auditable.
