# Repository map

Marketlab separates research claims, point-in-time evidence, evaluation, and
operations so a favorable result cannot directly become a live action.

```text
public/exchange sources
        │
        ▼
collectors and data adapters ──► immutable evidence and snapshots
        │                                  │
        ▼                                  ▼
causal features and frozen theories ──► chronological engine evaluation
                                                   │
                                                   ▼
                                  artifacts, decisions, shadow, paper gates
                                                   │
                                                   ▼
                                      explicit human live authorization
```

## Architectural layers

| Layer | Components | Responsibility |
|---|---|---|
| Governance | `AGENTS.md`, `research/`, `theories`, `theory-dsl`, `historical-data` | Policies, candidate identity, theory contracts, clocks, locks, and decisions |
| Acquisition | `data`, `collector`, `social-collector`, `social-backfill` | Exact source bytes, event streams, historical archives, manifests, and integrity |
| Features and models | `sentiment-core`, `sentiment-worker`, `social-model`, `alpha-model` | Frozen preprocessing, causal materialization, bounded CPU/GPU model search, and scoring |
| Evaluation | `engine`, `analytics-duckdb`, `research-cli`, coordinator handlers | Walk-forward forecasting, inference, observed execution, and diagnostics |
| Control plane | `contracts`, `evidence-core`, `persistence`, `coordinator`, `service` | Immutable identities, storage, jobs, runs, artifacts, and eligibility |
| Isolation and operations | `worker-kotlin`, `runner`, `deploy` | Allowlisted batch execution, digest-pinned releases, verification, and rollback |

The machine-readable component inventory is
`research/inventory/components.json`. Its Gradle-module coverage is validated
against `settings.gradle.kts`.

## Where new work belongs

| Work | Primary location |
|---|---|
| New alpha mechanism or candidate | `research/alpha/spaces/<space>/candidates/<id>/` |
| New compiled theory plan | `theories/` plus `research/inventory/theories/<id>.json` |
| Frozen acquisition/model/evaluation contract | Existing root of `research/`; do not move contractual locks |
| New result | Immutable artifact store, candidate evidence, evidence inventory, and decision log |
| Market source adapter or snapshot | `data/` |
| Continuous Hyperliquid event capture | `collector/` |
| Social/news source capture | `social-collector/` |
| Historical social acquisition | `social-backfill/` |
| Archive-backed directional model campaigns | `alpha-model/` plus a lock under `research/alpha/campaigns/` |
| Prospective sentiment/features | `sentiment-core/` and `sentiment-worker/` |
| General evaluation or execution semantics | `engine/` |
| Experiment routing | `coordinator/` |
| Deployment or recovery | `deploy/` and the operations inventory |
| Cross-cutting explanation | `docs/` |

## Paths intentionally left in place

Files such as `research/*.lock.json`, source-policy documents, runbooks,
container paths under `/opt/marketlab/research`, and content-addressed artifact
locations are contractual. Repository organization uses indexes and links
instead of moving them and invalidating deployments, hashes, or audit history.

## Parallel editing boundaries

Candidate directories and per-theory/evidence inventory records are independent
work units. Shared foundation modules, source schemas, the engine, deployment,
and centralized component/operations maps require an explicit agent claim
before editing. See `AGENTS.md` and `research/alpha/workflow.md`.
