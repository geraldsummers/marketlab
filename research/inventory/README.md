# Repository inventory

This directory indexes stable repository facts without moving contractual
files. It answers five questions:

1. Which compiled theories exist and where is each one represented?
2. Which evidence artifacts have been produced, what decision did they earn,
   and which outcome periods are no longer blind?
3. Which point-in-time data sources are implemented, accumulating, blocked, or
   deliberately excluded?
4. Which code component owns a capability and which alpha spaces consume it?
5. Which operational entry point should an agent use for deployment, recovery,
   validation, or research continuation?
6. Which compute configurations succeeded or failed, and what limits should a
   new campaign inherit?

Records are machine-readable and validated by
`research/alpha/tools/workspace.py`. Theory and evidence records are one file
per identity so parallel agents can append new work without editing a shared
ledger. Component and operations maps change rarely and remain centralized.

```sh
python3 research/alpha/tools/workspace.py theories
python3 research/alpha/tools/workspace.py evidence
python3 research/alpha/tools/workspace.py data
python3 research/alpha/tools/workspace.py components
python3 research/alpha/tools/workspace.py operations
python3 research/alpha/tools/workspace.py outcomes
python3 research/alpha/tools/workspace.py compute
python3 research/alpha/tools/workspace.py synthesis
python3 research/alpha/tools/workspace.py validate
```

Inventory records summarize and link. Compiled Kotlin plans, frozen locks,
content-addressed artifacts, and the append-only decision log remain the source
of truth.

Evidence records expose structured candidate decisions and opened-outcome
periods. Compute-run records preserve observed resource behavior separately
from recommendations. Remote verification is explicit and read-only:

```sh
python3 research/alpha/tools/workspace.py verify-artifacts --ssh-host gerald@192.168.0.11
```
