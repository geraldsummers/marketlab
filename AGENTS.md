# Marketlab agent policy

This policy applies to the entire repository. It complements the lane and
coordination instructions supplied by the agent environment.

## Prime directive

Agents may autonomously pursue evidence of crypto or market alpha, but may not
autonomously redefine success, erase failures, consume a sealed outcome more
than once, or expose capital.

The objective is durable **net** alpha under point-in-time information,
realistic execution, and prospective evidence. Producing an attractive chart or
backtest is not the objective.

## Discovery and future-data boundary

Ordinary research must complete using data that already exists when the task
begins. Do not wait for future outcomes to rescue feasibility work, weak
development, or a failed candidate.

Historical discovery may use previously opened outcomes, a current survivor
universe, revised values, or an explicitly classified availability-clock proxy
when cleaner data does not exist. Every such compromise must be recorded in a
bias ledger. This evidence is capped at `EXPLORATORY` and cannot be described as
blind, validated, tradable, paper-eligible, or live-eligible. Deliberate target
leakage and features computed from information after the decision timestamp
remain prohibited even for discovery.

Waiting for new prospective outcomes is permitted only after a bounded
historical discovery passes all preregistered predictive, stability, coverage,
and economic gates; one exact candidate is frozen; feature publication and
outcome sealing are operational; and the prospective duration, sample gate,
deadline, and no-peeking rule are locked. Until all activation gates pass,
future-data waiting is out of scope. Prospective activation never authorizes
paper trading, live trading, or capital exposure.

## Mandatory orientation

At the start of every research turn:

1. Inspect `git status --short` and treat existing changes as user-owned.
2. Read every claim in `$HOME/.local/share/worklane/agent-work/`.
3. Run `python3 research/alpha/tools/workspace.py list` and
   `python3 research/alpha/tools/workspace.py ready`.
4. Consult the relevant `theories`, `evidence`, `data`, `components`, or
   `operations` inventory command before duplicating work or selecting a path.
5. Read the chosen alpha space, overlapping candidate manifests, linked frozen
   locks, relevant decision-log entries, and source policy before acting.
6. Claim the exact candidate directory and every shared code, data-schema,
   collector, or deployment surface before editing.

The default ready view exposes only the highest-priority runnable work. Before
claiming, run `python3 research/alpha/tools/workspace.py task TASK_ID --json`
and treat its deliverables, acceptance criteria, claim surfaces, outcome-access
boundary, and compute limits as mandatory. Use `ready --all-priorities` only for
planning; do not bypass a runnable P0 task to start lower-priority research.

Task state is durable research coordination. Mark a task `DONE` only with a
linked evidence record. Unblock a dependent task only when its blocker is done
and its acceptance criteria are satisfied. Historical confirmation may proceed
without additional user approval only when the task explicitly permits
`SINGLE_USE_HISTORICAL_CONFIRMATION`, the candidate is `FROZEN_CANDIDATE`, all
artifact hashes and an empty ledger are verified, and the exact period does not
overlap the opened-outcome inventory. This authority never extends to paper or
live trading.

Use separate candidate directories for parallel work. Coordinate before
touching a surface named by another live claim. Claims are ephemeral soft locks;
candidate manifests and immutable artifacts are durable research state.

## Alpha workspace

`research/alpha/` is the navigation and coordination layer:

- `spaces/<space-id>/space.json` exposes ready and blocked work;
- `spaces/<space-id>/candidates/<candidate-id>/` owns one falsifiable claim;
- `templates/` defines candidate, result, and handoff records;
- `tools/workspace.py` discovers and validates the decentralized workspace.

Do not create a central mutable backlog or model-family workstream. Alpha spaces
represent information and economic mechanisms; algorithms are estimators.
Existing `research/*.lock.json` files, compiled theory plans, immutable reports,
and decision-log entries remain authoritative.

`research/inventory/` maps the full compiled theory catalog, material evidence,
point-in-time source readiness, components, and operational entry points. Add a
new per-theory or per-evidence record rather than hiding results in prose or an
external artifact store alone.

## Required epistemic states

Every candidate and every user-facing status report must use one of these
states:

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

Never describe an exploratory result as validated. Never use “works,”
“successful,” “confirmed,” “tradeable,” or “alpha” without naming the exact
stage and claim that earned the description.

## Separate the claims

Agents must distinguish these gates:

1. **Predictive signal:** improves a frozen forecast metric over the strongest
   reasonable point-in-time baseline.
2. **Tradable signal:** remains useful after spread, fees, slippage, latency,
   funding, borrow, and capacity.
3. **Portfolio alpha:** improves risk-adjusted performance after sizing,
   correlation, turnover, and drawdown constraints.
4. **Operational strategy:** survives prospective shadowing, observed paper
   execution, failure recovery, and kill-switch testing.
5. **Live authorization:** the user separately authorizes venue, instruments,
   capital, leverage, credentials, and loss limits.

Passing one gate never implies passing the next. A variance forecast is not
return direction. A direction forecast is not a fill. A backtest is not paper
trading. `LIVE_ELIGIBLE` is not permission to place an order.

## Hypothesis before estimator

Every candidate must state before confirmation outcomes are opened:

- mechanism and why the information may not already be in price;
- predicted sign or conditional relationship;
- exact target, horizon, universe, and decision clock;
- point-in-time feature set and historical availability semantics;
- strongest baseline and primary loss;
- model families, transformations, assets, horizons, and variants in the search
  budget;
- dependence-aware inference and family-wide multiplicity rule;
- economic failure threshold and expected execution path;
- conditions that reject this candidate version.

“Try XGBoost,” a new indicator, another lookback, or a larger neural network is
not a new hypothesis. A materially new information source or market mechanism
may justify a new candidate with a fresh ID and untouched outcomes.

## Exploration and freezing

Agents may use development data to audit quality, engineer features, compare a
bounded set of model families, diagnose failure, and estimate whether an effect
could matter economically. Record all tried variants in the candidate's search
family.

Before confirmation, freeze the target, feature schema, availability rules,
sample boundaries, purge and embargo, baselines, search result, inference,
costs, thresholds, and artifact hashes through the existing lock and theory
machinery. Once outcomes are opened:

- do not refit, relabel, or replace the primary metric;
- do not change the predicted sign;
- do not promote an attractive subgroup or secondary metric to primary;
- do not delete and reacquire data to claim blindness;
- do not reuse the period as an untouched holdout for a revised candidate.

## Failed theories remain failed

Null, negative, and contradictory outcomes are durable evidence. Preserve them
in the candidate manifest, result record, decision log, and immutable artifact
store as applicable.

A rejected candidate can be revisited only when at least one is true:

- a materially distinct mechanism or point-in-time information set is proposed;
- a trustworthy archive supplies previously missing information;
- a registered live-data duration and quality gate is reached; or
- a fresh confirmation period is preregistered before its outcomes are read.

Do not conduct local parameter search around a failed holdout. Do not rename a
positive coefficient as momentum after preregistering reversal. A surprising
holdout after failed development is hypothesis-generating, not confirmation.

## Point-in-time data policy

For every feature, answer: **could the deployed system have known this exact
value at the decision timestamp?**

Preserve source bytes and identity; exchange/publisher, receipt, availability,
and processing clocks; revisions; query parameters; historical universe
membership; gaps; reconnects; and quality findings. Follow
`research/source-policy.md`.

Never outside explicitly bias-labeled `EXPLORATORY` discovery:

- join today's revised value to a historical decision without vintage data;
- project today's survivor universe backward;
- forward-fill missing prices or reconstruct an unobserved book;
- fabricate prices, fills, queue position, borrow, funding legs, or latency;
- treat publisher time as receipt time without an explicitly classified proxy;
- treat current depth as historical capacity.

When the required causal data is absent, mark the candidate `DATA_BLOCKED` and
advance acquisition or audit work instead of weakening the claim.

Inside bias-labeled discovery, the same defects must remain visible in the
bias ledger and promotion ceiling. They may motivate a prospectively
reproducible candidate, but they cannot support retrospective validation.

## Validation standard

Default requirements are:

- chronological expanding or rolling evaluation, never shuffled market time;
- target-overlap purge and appropriate embargo;
- preprocessing, selection, and tuning fitted only inside training data;
- zero-return, historical-mean, persistence, and strongest relevant market-only
  baselines;
- forecast loss and calibration before strategy returns;
- dependence-aware inference and correction over the full research family;
- stability across folds, assets, liquidity, venues, and regimes where the
  claim permits;
- an untouched single-use confirmation period;
- a prospective shadow period for any tradability claim;
- doubled-cost and capacity stress before paper eligibility.

Accuracy above 50% is neither necessary nor sufficient. Prefer expected return,
probability of exceeding total cost, calibrated quantiles, and an explicit
abstention region. Report performance and coverage for both acted-on and
rejected observations.

## Model and compute policy

Begin with strong interpretable controls and the smallest model capable of the
mechanism. Boosted trees are the default nonlinear tabular candidate. Neural
models are justified only when sequence or representation structure is part of
the frozen hypothesis, simpler models leave stable residual signal, and the
independent sample can support the added search.

Compute follows evidence. More trials or larger models do not compensate for
an information set that lacks the direction, horizon, or economic state needed
by the target. Respect registered compute and memory bounds and record every
trial, including failures.

## Execution and capital boundary

Research code may create forecasts, shadow decisions, historical replays, and
paper-ledger records only when the relevant stage permits them. It must not
place, modify, or cancel a live order; transfer assets; create exchange keys;
or change live risk limits without explicit user authorization for that exact
external action.

Paper and live promotion require separate checks for fees, spread, book walk,
latency, partial fills, queue assumptions, funding and borrow, liquidation,
capacity, turnover, concentration, correlation, drawdown, stale data, clock
skew, disconnects, restart recovery, and kill switches.

## Reporting and handoff

Lead with the decision, not the most exciting metric. Every material report and
handoff states:

```text
What we tested
Current epistemic stage
What happened
What we know
What we suspect
What remains untested
Opened outcomes and periods that are no longer blind
What data or authority would change the answer
Next permitted action
```

Update the candidate record even when work fails or blocks. Link immutable
artifact hashes rather than committing raw evidence, predictions, models, or
large reports. Before handoff run:

```sh
python3 research/alpha/tools/workspace.py validate
python3 -m unittest research/alpha/tools/test_workspace.py
./gradlew check
```

Also run any narrower data, model, deployment, or live-contract validation
required by the touched surface. Do not push while a required local check
fails.

## Authorized midnight exploration

The user authorized the workflow in `research/automation/README.md`: start at
midnight Australia/Hobart when the main Codex bucket has more than 50% remaining,
then pursue sequential bounded historical exploration until the reserve is
reached. Use gpt-6-astra with medium reasoning. Optimize evidence per token and
experiment; do not spend allowance merely because it exists.

The trusted dispatcher may manage systemd user worker units and commit/push
validated research to `origin/automation/market-exploration` from its isolated
worktree. This is the specific exception to the foreground-only rule above;
research agents still cannot detach independent jobs, alter the automation,
consume credits/resets, or change live services. No automatic main-branch merge.
Registration is pushed before data access. Preserve failed results and publish
validated evidence checkpoints; retain failing code locally without pushing it.
Keep raw artifacts on the server with committed hashes and explicit locations.

Unattended authority stops at historical exploration. It does not include sealed
confirmation or prospective activation, even where general task policy could
otherwise permit confirmation. Do not revisit consumed outcomes or reset an
experiment budget. Consult live claims; defer overlapping interactive work.

## Practitioner-informed roadmap

Consult `research/inspiration/README.md` and `workspace.py inspirations` before
new mechanism selection. The three focused source audits lead the P1 queue:
corporate-event terms (selectionRank 10), prediction resolution (20), and
stablecoin redemption constraints (30). Broad domain audits retain rank 100.
Priority always precedes rank; domain balancing breaks equal-rank ties.

Treat established managers, lesser-known specialists and market operators as
sources of hypotheses. Separate independently supported analysis, practitioner
claims and inference; record failure cases and transferable access/cost conditions.
Link candidate inspirationRefs and transferAssessment; preserve existing failed
candidates and known outcome boundaries. Historical stories are never blind data.
There is no recurring literature slot. Longer holding horizons and long-format
historical datasets are permitted; each experiment still retains its original
maximum 11h45m wall-clock deadline, including preparation and reporting.
