# Alpha campaigns

Campaign locks bound shared search dimensions, compute, development periods,
and single-use confirmation families. They do not replace per-mechanism
candidate records and do not imply that every information lane has usable data.

`archive-directional-gpu-v2.lock.json` is the active assumption-neutral,
archive-backed directional campaign. It supersedes v1 before any archive or
outcome was opened. V2 freezes historical archive discovery, a two-pass daily
then five-minute acquisition, bounded-memory preparation, automatic compute
expansion, immutable checkpoints, and a lightweight-supervision boundary. A
mechanism without point-in-time fields ends `DATA_BLOCKED`; it is never filled
with a proxy after outcomes are visible.

Current stage: `EXPLORATORY` infrastructure only. No development campaign or
confirmation family has been run from either archive lock, and no confirmation
outcomes have been opened. The next permitted action is dispatch of the v2
controller. It must return after its first healthy immutable checkpoint, may
then be supervised by a lightweight agent, and must stop in
`AWAITING_CONFIRMATION_REVIEW`. All three searches share one preregistered
durable confirmation-ledger root and merge before any candidate can freeze.
