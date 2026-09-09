# Installation verification — 2026-09-09 UTC

Decision: the midnight automation is ready for scheduled admission. The expanded
research agenda remains `DATA_FEASIBILITY`; no market experiment was launched by
installation and no predictive or economic claim was tested.

Verification passed in the dedicated automation worktree:

- 27 automation tests, including temporary bare-remote publication, rejected
  pushes, interruption recovery, allowance boundaries and registration ordering.
- 35 workspace and bounded-supervisor tests, 91 alpha-model tests and eight
  social-model tests.
- Workspace validation and the full `./gradlew check` gate.
- Installed systemd unit verification, fresh-login launcher resolution, a real
  systemd fake-worker lifecycle and a two-second timeout killing its child tree.
- A live account allowance RPC using installed Codex CLI 0.153.4.

Automation-only trace coverage was 90% of core, 64% of controller and 80% of
worker statements. This does not measure subprocess coverage. No real model turn
was launched: model research quality and a complete production night remain
untested. Detailed installation logs are local under
`$HOME/.tmp/marketlab-automation-*`; future publication logs are content-hashed
and linked from their commits.

The last pre-installation allowance reading was 6% remaining in the main weekly
bucket. The first timer deadline is 2026-09-10 00:00 Australia/Hobart
(2026-09-09 14:00 UTC); admission requires a fresh reading above 50%.

Next permitted action: the scheduled source-feasibility queue after allowance
and ownership checks. Live orders, paid acquisition and sealed outcomes remain
outside the unattended authorization.
