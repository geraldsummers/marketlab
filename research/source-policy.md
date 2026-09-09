# Real-data source policy

## Decision rule

Every experiment declares required fields/resolution, discovery venues, target
venue, point-in-time universe, period, publication delay, completeness grade,
license, expected acquisition cost, and transfer rationale.

1. Declare the target market, venue, instrument universe and decision clock.
   Prefer target-native authoritative data that answers that hypothesis.
2. Otherwise use the cheapest authoritative production source with the required
   history and microstructure.
3. External venues may reproduce or discover an effect, but final target labels
   and execution validation must come from the declared target venue. Preserve
   Hyperliquid requirements in existing frozen contracts unchanged.
4. Paid/requester-pays acquisition needs explicit approval for that case.
5. Validate paid or third-party coverage against overlapping official data.

## Source roles

| Theory need | Preferred sources |
|---|---|
| Hyperliquid target, funding, OI, mark/oracle, current books | Hyperliquid REST/WS and official requester-pays archives |
| Long bars, trades, futures funding and metrics | Official Binance archives; Kraken as an independent USD spot robustness venue |
| Historical high-resolution L2 | Official OKX downloads for discovery; Hyperliquid S3/live capture for target adaptation |
| Second-perpetual funding/OI venue | Official Bybit archive/API |
| Option surfaces | Deribit; CME DataMine only with approved licensing/cost |
| On-chain or macro | Native chain data and vintage official statistical releases |

Hyperliquid REST candles expose only the latest 5,000 observations, and the
official monthly S3 archives may be late or incomplete. The collector therefore
starts prospective production capture immediately and never treats missing
archive objects as zero activity. See the
[Info API](https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/info-endpoint),
[WebSocket documentation](https://hyperliquid.gitbook.io/hyperliquid-docs/for-developers/api/websocket),
and [historical-data warning](https://hyperliquid.gitbook.io/hyperliquid-docs/historical-data).

## Non-negotiable integrity rules

- Preserve exact raw bytes and source/checksum/revision metadata outside Git.
- Keep event, receive/retrieval, and causal availability clocks distinct.
- Preserve native units, contract/collateral terms, tick/lot rules, and actual
  funding timestamps.
- Reject or quarantine sequence gaps, impossible books/OHLC, unfinished
  candles, timestamp-unit changes, and zero-trade pseudo-bars.
- Snapshot listings/delistings and asset metadata as known at each timestamp.
- Do not forward-fill missing prices or reconstruct unobserved order books.
- Unit tests use ordinary scalar invariants; market-data integration fixtures
  are checksum-locked production excerpts with provenance.


## Cross-market source contracts

- Conventional markets: record exchange calendars and timezones, auctions,
  halts, historical listings/delistings, point-in-time corporate actions,
  adjusted versus raw prices, futures rolls, and statistical release vintages.
  Today's adjustment factors or constituent lists are not historical knowledge.
- Onchain: preserve chain ID, block/hash, transaction/log identity, finality and
  reorg handling, retrieval/receipt clocks, archive-node and indexer revisions.
  A block timestamp is not proof of causal receipt. Execution needs gas, MEV,
  bridge/settlement delays and observed liquidity, as relevant to the claim.
- Prediction markets: preserve venue and contract identity, question and rule
  versions, outcome universe, resolution source and time, disputes, cancellations
  and settlement. Quote probability is distinct from an executable price;
  account for fees, spread, locked collateral and event dependence.
- Dollar stablecoins: distinguish issuance/redemption flows, circulating supply,
  backing composition, reserve attestations, publication/revision clocks,
  redemption access, depegs and issuer/chain identity. Tokenized Treasuries and
  privately issued dollar tokens are distinct instruments.
- Policy transmission: verify the proposed US support expansion with dated
  primary releases and distinguish proposal, enactment, implementation and
  market availability. Use information available at the decision clock, not
  retrospective summaries or inferred government guarantees.

The four new domain inventories are feasibility placeholders, not verified
sources or licenses. Use existing historical data, preserve source bytes and
bias ledgers, and record a blocker when required coverage is absent. Paid data
still requires case-specific authorization. A source audit may inspect coverage
metadata without opening sealed confirmation outcomes.
