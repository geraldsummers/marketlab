# Successor task handoff

- Task ID, priority, and status:
- Candidate IDs and current epistemic stages:
- Claim surfaces acquired and released:
- Required deliverables completed:
- Acceptance criteria passed or failed:
- Resource class and observed compute use:
- Outcome-access boundary:
- Development or confirmation outcomes opened:
- Exact periods no longer blind:
- Immutable artifacts and SHA-256 identities:
- Completion evidence ID:
- Remaining task or external blockers:
- Smallest next permitted task:
- Validation already run:

A successor must retrieve the canonical contract with:

    python3 research/alpha/tools/workspace.py task TASK_ID --json

Do not infer permission from this handoff when the canonical task remains
blocked. Do not wait for future outcomes in historical mode.
