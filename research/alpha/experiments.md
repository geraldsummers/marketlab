# Bounded cross-market experiments

Current agenda stage: `DATA_FEASIBILITY`. Explore underexamined mechanisms across
conventional markets, onchain activity, prediction markets and dollar stablecoins
with balanced initial coverage. Existing crypto evidence remains authoritative.
The four P1 source audits in `workspace.py ready` precede the remaining P2 work.
No predictive experiment is launched by registering this agenda.

## Register before doing experiment work

Copy `templates/experiment.json` into a candidate directory. Declare the exact
hypothesis, target market/venue, decision clock, domains, outcome access, resource
limits, candidate ID and search family. A feasibility contract may make source
selection its deliverable; a predictive contract must already specify instruments,
sources, target, baseline and search through its linked candidate and frozen lock.

Start the durable clock before experiment-specific acquisition or preparation.
The default/maximum is 42,300 seconds (11h45m), with a 900-second reporting reserve.
The work deadline is therefore 11h30m. The watcher terminates work at that deadline
and publishes a minimal result; interruptions and idle time count. Short contracts
may use a smaller positive reserve. This is an elapsed budget, not a GPU-hours quota.

```sh
python3 research/alpha/tools/experiment.py start --contract research/alpha/spaces/relative-value/candidates/conventional-market-feasibility/experiment.json
python3 research/alpha/tools/experiment.py status conventional-market-feasibility-v1
```

Use the one durable registry at `$HOME/.local/share/marketlab/experiments`.
`--root` exists only to isolate software tests. Starting the same contract again
retains its original deadline. Another experiment ID for the same candidate is
rejected. Distinct follow-ups need a new candidate, predecessor links and the
same fixed family trial budget. The family ceiling is 256 attempts across its
experiments, with a maximum of 16 per experiment; choose smaller bounds whenever
possible. This ceiling is not a recommended launch target.

## Execute foreground commands

```sh
python3 research/alpha/tools/experiment.py run EXPERIMENT_ID -- python3 path/to/source_audit.py
python3 research/alpha/tools/experiment.py run --trials 1 EXPERIMENT_ID -- python3 path/to/model_trial.py
```

Replace the illustrative scripts with the registered task implementation.
Reserve all trials in a batch before launching it. Failed attempts and retries
consume reservations. A failed command records its failure and returns nonzero;
a transient failure may retry within the original budget. Launch failure, excess
memory, inconsistent clocks, or lost supervision terminate the experiment as
`OPERATIONALLY_BLOCKED`. Budget exhaustion terminates it as `INCONCLUSIVE`.
Terminal results cannot be overwritten or resumed.

The supervisor admits one model command and one non-model command at a time
(the latter is conservatively below the policy's two-audit ceiling). It limits
CPU affinity and numerical-library threads, hides CUDA for CPU-only contracts,
and samples process-group RSS against the memory budget. RSS is sampled, not a
kernel memory reservation; leave headroom for the shared host. GPU contracts
require a justification and share the model slot. Inspect current VRAM before
execution and record the actual environment with evidence.

Commands must stay in their foreground process group: do not detach, launch
persistent services, submit remote jobs, or use containers that escape it.
Process-group supervision is local cooperative enforcement, not a security
sandbox. The watchdog is independent of the initiating terminal; after a server
reboot, `start` or `status` reconciles the original deadline before any resume.
No software can publish during host downtime. Clock inconsistencies fail closed.

Existing local archive, focused-confirmation and exposure CLI entry points require
supervision; model search also requires trial reservations. Library functions
remain usable for software tests. Distributed production workers retain their
existing manifest contracts; they are not authorized for this local agenda.
The legacy persistent archive dispatcher/controller now refuses new execution.
Register a bounded successor rather than modifying its historical locks.

## Finalize and retain evidence

```sh
python3 research/alpha/tools/experiment.py finalize EXPERIMENT_ID --stage DATA_FEASIBILITY --evidence /absolute/path/to/report.md
```

A zero exit code records only command completion. Finalization requires a real
evidence file and stores its SHA-256. Use `DATA_FEASIBILITY`, `EXPLORATORY`,
`REJECTED`, `INCONCLUSIVE`, `DATA_BLOCKED` or `OPERATIONALLY_BLOCKED` as warranted.
The supervisor cannot grant blind, paper or live promotion. Confirmation requires
both the exact outcome-access contract and all pre-existing freeze/ledger gates.

Each result/handoff states what was tested, epistemic stage, what happened, what
is known/suspected/untested, opened outcomes, what data or authority would change
the answer, and next permitted action. Link supervisor `state.json` and underlying
report hashes in the candidate and evidence inventory. Preserve failed attempts;
mark tasks DONE only after their acceptance criteria and evidence are satisfied.

Prospective observation duration is a separate frozen contract. Do not shorten
it to fit a historical test or wait for fresh outcomes to rescue weak discovery.
The first four source audits use only data that already exists at their start.
