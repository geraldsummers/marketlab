# Corporate-event terms audit — 2026-09-10

**Decision: DATA_BLOCKED for a defensible economic test. The source audit is
complete.** Individual terms are recoverable, including an issuer-hosted filing
bundle, but a representative event archive, historical broker cutoffs and
point-in-time executable quote access are not established. Do not launch a
return backtest from these examples. Proceed to the next ranked source audit.

Experiment: `corporate-event-terms-feasibility-v1`; original two-hour deadline
and 15-minute reporting reserve are in `run-registration.json`. Zero model trials,
no GPU, no paid acquisition, no external communication or trading.

## What we tested / what happened

We attempted 16 distinct official metadata/terms/document endpoints: nine returned
HTTP 200, six SEC endpoints returned 403, and an obsolete broker fee URL returned
410. All response bodies, including failures, were preserved and SHA-256 checked.
A corrected broker URL succeeded. Monster's issuer-hosted filing index, PDF and
HTML succeeded after direct SEC access failed. See `source-manifest.json` for
URLs, retrieval clocks, response status, headers, byte counts and artifact hashes.

One initial command failed before acquisition due to a missing Python import
path. It was fixed and rerun under the original experiment; the failed attempt
remains in the supervisor history. No dependency was installed.

The supervised integrity check verified all 16 hashes and basic expected terms
in six HTML documents. This is not a parser accuracy or sample-coverage benchmark.
Web-rendered SEC documents supported manual inspection but do not substitute for
locally captured original SEC bytes. Search examples were selected for term
variety, not representativeness; no event count or profitability rate is estimated.

## What we know

| Surface | Observed evidence | Readiness and limitation |
| --- | --- | --- |
| Offer terms | Monster May 2024 original offer plus transmittal, guaranteed-delivery and broker exhibits are available from its issuer site. | Individual bundle recoverable; complete amendment chain not audited. |
| Eligibility and proration | Monster distinguishes sub-100-share aggregate holdings and full-position tender from ordinary proration, conditional tenders and auction price eligibility. | Odd-lot priority is an acceptance condition, not a promised profit. |
| Financing and pricing | Monster has a financing condition and a price range, not an unconditional fixed payoff. | Separate committed capital, tender instruction and uncertain final consideration. |
| Amendments | Frontera's September 2024 notice removes odd-lot preference and invalidates prior odd-lot tenders. | Concrete rule-change counterexample; Canadian terms are not a US sample member. |
| Broker deadlines | IBKR's current instructions distinguish region-specific submission cutoffs from issuer expiration; Fidelity separately describes cutoff and expiration. | Current documentation is not historical event/account-specific evidence. |
| Ownership and costs | Fidelity describes odd-lot ownership across accounts. IBKR's current fee page lists a free catch-all category, with separate regional exceptions. | No per-account capacity multiplication; do not project current fees back in time. |
| Executable prices | NYSE describes Daily TAQ trade, quote, NBBO and master files and publishes historical pricing documentation. | Product existence verified; entitlement, desired-period coverage and license rights not established. No quote files acquired. |
| Population enumeration | SEC documents filing-history APIs; direct submissions and 2024 Q2 index probes failed here. | No reproducible full population or independent event count obtained. |

Sources: [Monster filing](https://investors.monsterbevcorp.com/node/16891/html),
[Frontera amendment](https://fronteraenergy.mediaroom.com/2024-09-25-Frontera-to-Amend-Substantial-Issuer-Bid-to-Remove-the-Preferential-Acceptance-of-Odd-Lots),
[IBKR instructions](https://investors.interactivebrokers.com/en/trading/corp-action-instructions.php),
[Fidelity FAQ](https://www.fidelity.com/customer-service/corporate-actions-learn-more-faqs),
[IBKR fees](https://www.interactivebrokers.com/en/pricing/other-fees.php),
[NYSE TAQ](https://www.nyse.com/data-products/catalog/daily-taq),
[NYSE pricing](https://www.nyse.com/publicdocs/nyse/data/NYSE_Historical_Market_Data_Pricing.pdf),
[SEC API documentation](https://www.sec.gov/search-filings/edgar-application-programming-interfaces).
The SEC documents a public API without authentication; the 403 response therefore
establishes a failure of this access attempt, not a requirement to purchase data
or evidence that the archive does not exist. Its root cause was not established.

## Historical universe and point-in-time design

A future source-recovery task should enumerate **all initial US common-equity
issuer tenders announced in 2020–2025**, using filing indexes and issuer identity,
not today's ticker list or a search for profitable odd lots. Link preliminary
SC TO-C communications, initial SC TO-I and every SC TO-I/A by issuer/security and
offer identity. Separate operating-company common equity from fund repurchases,
debt tenders and third-party takeover offers; record every exclusion and reason.
An amendment is an event-state update, not another independent observation.

Retain completed, withdrawn, cancelled, extended, no-preference and amended offers.
Ordinary comparison observations are eligible offer-days without amendments or
special priority, selected before looking at outcomes. Preserve delisted issuers.
Predefine matched liquidity/size controls using only then-known values. Cluster
inference at least by issuer and overlapping offer period; no independent-sample
claim is possible until enumeration and dependence are measured.

Required fields: CIK and historical security identifiers; accession and document
hash; offer identifier; initial announcement, filing acceptance, retrieval and
availability times; version-effective time; eligibility definition; ownership
aggregation; price rule; conditional-proration and withdrawal rules; financing
conditions; issuer expiry/timezone; broker cutoff and settlement eligibility;
fees/taxes; cancellation and payment terms. Unknown clocks remain unknown.
Issuer web retrieval today is not a historical publication/receipt timestamp.
A terminal results amendment may be an outcome source, never an earlier feature.

## What we suspect / transfer assessment

A small-capacity contractual preference may be worth screening only when the
available purchase price, costs and probability-weighted failure loss leave
positive value. The issuer seeks to retire stock; competing arbitrage capital,
shareholder instructions and broker access affect participation. The identity
of a willing seller is not proof that the seller is uninformed.

For sub-100-share eligibility, the acceptance benefit is capped by the true
beneficial owner's qualifying holding (often at most 99 whole shares). A larger
portfolio cannot inherit the same full-acceptance assumption. For a contemplated
quantity q, compare cash consideration with acquisition cost, commission, spread,
funding over actual lockup, taxes where applicable, and failure liquidation loss.
Use executable acquisition quotes and observed payment timing; daily close or
an assumed peg to the offer price cannot supply fills. No numeric profit estimate
is justified by this audit. Cash-funded research is the simplest future baseline;
leverage or borrow introduces separate evidence requirements.

The Rumble January 2025 offer inspected via SEC web rendering illustrates why a
fixed tender price and priority language alone are insufficient: the offer itself
reports a pre-announcement market quote above its tender price and financing
conditionality. We did not download a Rumble price series or calculate returns.
[Original offer](https://www.sec.gov/Archives/edgar/data/1830081/000121390025000757/sctoi_ex99a1arumble.htm).

Buffett/Alluvial narratives motivate document work, not performance validation.
Existing cross-asset divergence remains INCONCLUSIVE with its historical-universe
blocker; prior BTC/social failures remain binding. This audit uses a distinct
corporate-rights information set and provides no reason to reopen their outcomes.

## What remains untested / blocker and next permitted action

No predictive sign, fit, P&L, tradability, execution access, capacity-adjusted
portfolio benefit or independent event sample has been established. No predictive
successor is registered because its source and cost gates cannot yet be met.

The concrete blocker is the combination of (1) reproducible complete historical
filing/event enumeration, (2) retained version/availability and amendment links,
(3) target-native executable quotes with historic security coverage and permitted
use, and (4) historical broker eligibility/cutoff/cost evidence. Recover these in
a separately bounded source task before registering an economic experiment.
A paid license or broker contact would need separate authorization; neither is
necessary to conclude this audit. Existing public or already entitled sources
should be checked first. Do not wait for future outcomes or reset this experiment.
Next queue action: prediction-resolution-terms-feasibility, under its own contract.

## Opened outcomes and bias ledger

The contract authorized terms and coverage, not an experimental outcome dataset.
None was intentionally acquired or evaluated; no sealed Marketlab outcomes were
opened. However, search results unexpectedly disclosed Rumble February 2025
completion/acceptance and Monster June 2024 final tender results. Original offer
pages also include contemporaneous reference prices; the captured Monster bundle
contains historical price-range disclosures. These ancillary disclosures were
not converted into a price/return dataset or used for a performance metric.

Record these as **incidental narrative/embedded-price exposure**, not blindness.
Exclude both named offer episodes from any future untouched confirmation. The
precise full disclosure span was not enumerated, so the outcome inventory uses
UNKNOWN periods rather than inventing complete start/end bounds. Frontera's known
amendment and the earlier inspiration stories are also already exposed discovery
examples. Search snippets are not authoritative confirmation of completion; their
presence nevertheless defeats a future claim that the researcher was unaware.
Other biases: example-selection bias, current broker-rule vintage, incomplete
issuer-mirror coverage and unknown historical receipt clocks. Promotion ceiling
remains exploratory for any later reuse; the present candidate is DATA_BLOCKED.
