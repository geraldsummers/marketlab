# Parallel alpha workflow

## Durable units

An **alpha space** owns a coherent information or market-mechanism surface. A
**candidate** owns one falsifiable claim. An **experiment lock** owns one
single-use evaluation design. A **result** owns the outcome, including null and
negative outcomes.

Do not create workstreams named after algorithms. A candidate such as
"order-flow imbalance predicts the next mid-price move when depth is thin" is
a research claim; "try XGBoost" is not.

## Parallel work

Agents should select disjoint candidates or data surfaces. Before editing,
each agent records:

- its canonical agent ID;
- exact candidate directory;
- shared source files, schemas, collectors, or deployment surfaces it expects
  to touch;
- current status and UTC timestamp.

Claims live in `$HOME/.local/share/worklane/agent-work/`. Agents read every
claim before editing and coordinate before touching an overlapping shared
surface. A claim is a soft lock, not research evidence.

Ordinary candidate work must not edit another candidate's directory. Shared
space manifests should change only when the space-level ready queue or data
readiness changes. The workspace has no central mutable candidate ledger, so
parallel candidate creation does not require a shared-file edit.

## Candidate lifecycle

Candidate manifests use one of these stages:

```text
IDEA
DATA_FEASIBILITY
EXPLORATORY
FROZEN_CANDIDATE
BLIND_VALIDATED
PROSPECTIVE_SHADOW
PAPER_ELIGIBLE
LIVE_ELIGIBLE
REJECTED
INCONCLUSIVE
DATA_BLOCKED
OPERATIONALLY_BLOCKED
```

Stage changes append evidence; they do not delete the earlier record. A failed
candidate keeps its ID and terminal state. A materially revised mechanism gets
a new candidate ID and fresh confirmation period.

## Boundaries

- Existing files in `research/*.lock.json` are immutable experiment inputs.
- Existing decision-log entries are append-only historical records.
- Raw data, model artifacts, predictions, and large reports remain in their
  registered content-addressed stores; candidate manifests link to them.
- Development results may guide exploration but never become confirmation by
  relabeling.
- Availability-biased or previously opened historical data may be used only
  with a machine-readable bias ledger and an `EXPLORATORY` promotion ceiling.
  Intentional target leakage remains prohibited.
- Future observations may be evaluated only after every registered development
  gate passes and one exact candidate is frozen with a fixed quality-duration
  gate, event-count gate, deadline, and no-interim-outcome-access rule.
- A candidate that requires unavailable point-in-time data becomes
  `DATA_BLOCKED`; its space may still contain independent ready work.
- No workspace stage authorizes live orders. Trading authority will be governed
  separately by the repository agent policy.

## Definition of a useful handoff

A handoff makes the next safe action obvious. It identifies what was tested,
what is known, what is merely suspected, which outcomes have been opened, what
must remain frozen, and which exact task can proceed without overlapping other
agents.

## Experiment execution

Follow [experiments.md](experiments.md) before acquisition or preparation. Register
one durable sub-12-hour experiment per candidate, preserve family trial accounting
and predecessor links, and attach both supervisor and research evidence to the handoff.

## Practitioner and operator provenance

Read [the source-grounded roadmap](../inspiration/README.md) when choosing a new
mechanism. Candidate inspirationRefs are optional repo-relative JSON paths using
`marketlab.inspiration.v1`; linked candidates require a transferAssessment.
Shared cases are inspected with `workspace.py inspirations --json`; new cases
can live in a claimed candidate directory. They are IDEA records, not validation.
Task selectionRank is an optional non-negative integer, default 100; priority
precedes rank, then unattended domain balancing resolves ties. It expresses
research sequencing, not expected returns or statistical confidence.
