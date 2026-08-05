# Alpha campaigns

Campaign locks bound shared search dimensions, compute, development periods,
and single-use confirmation families. They do not replace per-mechanism
candidate records and do not imply that every information lane has usable data.

`archive-directional-gpu-v1.lock.json` is the first assumption-neutral,
archive-backed directional campaign. A mechanism without point-in-time fields
ends `DATA_BLOCKED`; it is never filled with a proxy after outcomes are visible.

Current stage: `EXPLORATORY` infrastructure only. No development campaign or
confirmation family has been run from this lock, and no confirmation outcomes
have been opened. The next permitted action is checksum-verified archive
acquisition followed by separate development/confirmation panel materialization
for baskets 4, 6, and 10. All three searches must use one preregistered durable
confirmation-ledger root and be merged before any candidate can freeze.
