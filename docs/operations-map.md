# Operations map

The machine-readable source of this map is
`research/inventory/operations.json`. Read the linked runbook before executing
anything that changes a deployment, opens a sealed outcome, or writes an
external system.

## Local validation

```sh
python3 research/alpha/tools/workspace.py validate
python3 -m unittest research/alpha/tools/test_workspace.py
./gradlew check --no-daemon
```

The social-model test also requires the locked Python dependencies in
`social-model/requirements.lock`.

## Release lifecycle

| Operation | Entry point | External mutation |
|---|---|---|
| Build release | `deploy/build-release.sh` | Yes: images and release state |
| Activate release | `deploy/activate-release.sh` | Yes: active deployment |
| Verify deployment | `deploy/verify.sh` | No intended mutation |
| Roll back | `deploy/rollback.sh` | Yes: active deployment |
| Prepare host | `deploy/prepare-host.sh` | Yes: host directories, roles, and units |
| Service health | `deploy/service-health.sh` | Read-only |

Follow `deploy/README.md` for prerequisites, environment files, identities,
digest pins, and recovery details.

## Research operations

| Operation | Entry point | Current boundary |
|---|---|---|
| Funding diagnostic | `research-cli/README.md` | Retrospective diagnostic; no paper promotion |
| Historical social backfill | `research/social-backfill-program.md` and `deploy/verify-backfill.sh` | Discovery/falsification only |
| Social blind July evaluation | `research/social-functional-blind-extension-runbook.md` | Already opened; never reusable as blind data |
| Social variance prospective shadow | `research/social-functional-prospective.lock.json` | Registered but orchestration not implemented |
| Prospective social/news v2 | `research/social-program.md` | D0 not started; readiness checks remain mandatory |

No operation in this repository implicitly authorizes live orders or asset
transfers.
