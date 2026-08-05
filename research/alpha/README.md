# Alpha workspace

This directory is the operating map for parallel alpha research. It overlays
the existing compiled theory registry, immutable locks, evidence reports, and
decision log without moving or weakening them.

The workspace is deliberately decentralized:

- `spaces/<space-id>/space.json` defines a durable research surface and its
  currently ready or blocked work;
- `spaces/<space-id>/candidates/<candidate-id>/` contains one independently
  editable candidate record;
- `templates/` defines the minimum record for a new candidate, result, and
  handoff;
- `tools/workspace.py` discovers the tree, lists ready work, and validates its
  contracts.

There is no hand-maintained global candidate index. Agents can add candidates
in separate directories without contending on one registry file. The workspace
tool builds the index from the filesystem.

## Start here

```sh
python3 research/alpha/tools/workspace.py list
python3 research/alpha/tools/workspace.py ready
python3 research/alpha/tools/workspace.py validate
```

Use the [repository inventory](../inventory/README.md) to discover compiled
theories, prior evidence, source readiness, component ownership, and operational
entry points before creating overlapping work.

Then:

1. Read the selected space's `README.md` and `space.json`.
2. Read the linked prior evidence and frozen locks.
3. Claim the exact candidate directory and code/data touchpoints in
   `$HOME/.local/share/worklane/agent-work/agent--<id>.md`.
4. Create a uniquely named candidate directory from the templates. Do not
   reuse or rewrite a rejected candidate.
5. Keep exploratory work visibly exploratory. Freeze an experiment through
   the existing theory/lock machinery before opening confirmation outcomes.
6. Add a result and handoff even when the candidate fails or becomes blocked.

Claims are ephemeral coordination state and do not belong in this directory.
Candidate state and evidence are durable research state and do.

## Research surfaces

- `directional-returns`: signed return and price-distribution forecasts
- `volatility`: realized-variance, range, jump, and tail-risk forecasts
- `microstructure`: trades, books, liquidity, and short-horizon price formation
- `derivatives-carry`: funding, basis, open interest, liquidations, and carry
- `cross-venue`: price discovery, venue migration, and lead/lag
- `social-information`: attention, sentiment, disagreement, and news diffusion
- `relative-value`: cross-sectional factors, pairs, and related-instrument value
- `execution-portfolio`: costs, capacity, sizing, risk, and portfolio combination

These are information/mechanism spaces, not model-family silos. Boosted trees,
linear models, and neural networks are estimators that may serve several spaces.

See [workflow.md](workflow.md) for lifecycle and parallel-work rules.
