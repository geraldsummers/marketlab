# Automated market exploration

The user-authorized workflow starts at **midnight Australia/Hobart**, following
Tasmanian daylight-saving rules. It checks the main Codex account bucket and
runs only while **more than 50% remains in every reported window**. A weekly-only
account is valid. There is no morning cutoff. Spark, paid API capacity, credit
purchases and earned resets never substitute for the main allowance.

Current research stage: `DATA_FEASIBILITY`. Start with ranked corporate-event terms,
prediction resolution and stablecoin redemption audits, followed by broader
conventional, onchain, prediction-market and dollar-stablecoin audits. Once the queue is
exhausted, a bounded metadata discovery task can register distinct hypotheses
and data-ready successors. Historical exploration is authorized; sealed
confirmation, prospective activation, paid acquisition and trading are not.

## Inspect and control

```sh
marketlab-explore status
marketlab-explore quota
journalctl --user-unit marketlab-explore.service --since today
systemctl --user list-timers marketlab-explore.timer
marketlab-explore pause
marketlab-explore resume
marketlab-explore disable
```

`pause` stops new admissions and requests an active worker stop; deterministic
reporting and publication can finish. `resume` reconciles interrupted work and
unpublished commits and permits the next midnight; it does not start a new
immediate research cycle. `disable` also disables the timer. The `dispatch`
subcommand is the timer's internal entry point, not a way to override the
scheduled allowance gate.

The dedicated worktree is `$HOME/.local/share/marketlab/automation-worktree`.
Published history is on `origin/automation/market-exploration`. Inspect
`research/automation/runs/<Hobart-date>/README.md` and `events.jsonl` there.
They record qualification/skips, registration, meaningful checkpoints, results
and cycle completion. Commit messages link verification logs and their hashes.
Use Git history to inspect milestones, including negative and interrupted work.

Live phase, task, allowance and deadline are in the status command. Detailed
worker status and thread/turn IDs are under
`$HOME/.local/state/marketlab/automation/jobs/EXPERIMENT_ID/`.
Per-turn structured handoffs and token-usage telemetry, when available, live in
`$HOME/artifacts/marketlab-automation/EXPERIMENT_ID/`.
Raw data, model outputs and validation logs remain on the lab server; committed
manifests contain their hashes and locations, not an off-server backup of bytes.
Secrets and large/raw artifacts must never enter Git.

## Experiment and publishing guarantees

- The model is pinned to **gpt-6-astra, medium reasoning**. Use simple baselines,
  existing source inventories, reusable scripts and early rejection to maximize
  useful evidence per token. Allowance exhaustion is not a research objective.
- There is one dispatcher and one sequential research worker. Unit cgroups
  contain the agent and descendants; every experiment retains its original
  maximum 11h45m budget and reporting reserve. Each agent turn is capped at
  20 minutes; a concise handoff starts the next step without rereading everything.
- The supervisor starts the clock before acquisition, preparation and registration
  checks. Contracts and search budgets must be pushed before research execution.
  A rejected registration push therefore prevents data opening.
- Check allowance before each step and every 30 seconds while working. Interrupt
  at 50% or below. Stop for telemetry stale beyond 60 seconds. Percentages are
  coarse and usage can arrive late, so record any in-flight overshoot honestly.
- Each model/acquisition command uses the existing experiment supervisor. The
  trusted dispatcher is the only exception to the project's ban on detached
  research jobs: it creates bounded systemd worker units, not independent
  unbudgeted services. Agents cannot change units, policy, quota or registry state.
- RuntimeMaxSec uses remaining work time, not a fresh duration on each step.
  MemoryMax is the contract limit plus 2 GiB of agent overhead; admission also
  checks available shared RAM. Jobs cannot use service restarts to reset budgets.
- All checkpoints stage explicit claimed paths and pass workspace validation,
  workspace tests and Gradle checks, plus relevant Python regressions. Test logs
  are hashed in commit messages. Refuse publication if files change during checks.
- Failed code is retained locally in a recovery artifact with a binary Git patch
  and file copies. Restore only owned changes, then publish a separately validated
  evidence-only result. Unowned edits require inspection; they are never discarded.
- Preserve failed, inconclusive and interrupted outcomes. The dispatcher updates
  the selected candidate and its task, retaining prior evidence decisions. New
  development exposures are explicitly non-blind; no stage above EXPLORATORY can
  be produced by unattended work.
- A failed push leaves a durable outbox. Retry transient failures with bounded
  backoff; do not begin another experiment until publication recovers. Never
  force-push or automatically merge divergent remote history.

Systemd must be running inside the lane. Missed midnights are skipped
(`Persistent=false`), and a cycle cannot start twice on one Hobart date. There
is no automatic daytime retry after reaching the reserve. On restart, reconcile
original deadlines and active/unpublished work before the next admission.
Local controller state is coordination, not a replacement research backlog;
workspace task/candidate manifests remain the durable research map.

## Installation and checks

Run installation from the dedicated worktree:

```sh
python3 research/automation/install.py
python3 -m unittest discover -s research/automation -p 'test_*.py'
python3 research/alpha/tools/workspace.py validate
python3 -m unittest research/alpha/tools/test_workspace.py research/alpha/tools/test_experiment.py
./gradlew check
python3 research/automation/install.py --enable
```

Installation backs up differing existing unit files, verifies units, reloads the
user manager and installs a HOME-relative launcher. Only `--enable` activates
the timer; it never starts an immediate research cycle. The app-server protocol
is documented at <https://learn.chatgpt.com/docs/app-server> and was checked
against installed codex-cli 0.153.4 schemas. Authentication uses existing lane
credentials; no secrets appear in unit files.

Tests use a deterministic fake worker and temporary bare Git remotes. They
exercise allowance boundaries, weekly-only accounts, skips, interruption,
registration-before-execution, failed-code preservation, rejected pushes and
history divergence without spending research allowance or opening market data.

## Research ordering

The [practitioner-informed roadmap](../inspiration/README.md) replaces equal-domain
first admission with three ranked P1 source audits. Priority precedes optional
selectionRank (default 100), then existing domain balancing breaks ties. There is
no recurring literature slot. Each new audit has a two-hour total deadline with
15 minutes reserved for reporting; longer target holding horizons do not extend
that clock. `workspace.py ready` shows effective ranks and `task TASK_ID --json`
shows linked inspiration references. Registration is not execution.
