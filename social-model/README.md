# Social functional model trainer

The Kotlin backfill application materializes immutable causal feature rows. This
trainer performs the locked chronological model search and emits a trial ledger,
content-hashed model artifacts, and one frozen model-set manifest. It must run
before acquiring the registered July blind extension.

The default search is intentionally expensive: 40 trials for each of four model
families and four targets. Use a smaller `--trials` value only for tests; a
production search must use the lock maximum.

Create a user-space environment on the GPU host (never install system-wide):

```sh
python3 -m venv ~/.venv/marketlab-social-model
~/.venv/marketlab-social-model/bin/pip install -r social-model/requirements.lock
```

Materialize the original period without changing or reading the July extension:

```sh
social-backfill functional-features \
  --output-root /mnt/media/marketlab/raw/social-backfill-v2 \
  --program-lock /opt/marketlab/research/social-backfill-program.lock.json \
  --feature-lock /opt/marketlab/research/social-functional-feature.lock.json \
  --start 2025-10-04T00:00:00Z --end 2026-07-01T00:00:00Z
```

Run `trainer.py train` against the feature object named by its manifest, then
copy the frozen model directory into the evidence root. Only after its hashes
are published may the separately locked June–July acquisition root be created.
June supplies the required 30-day causal warm-up; only July feature rows are
evaluated. `evaluate` opens that extension once; `score` produces
label-independent shadow forecasts.

Both `evaluate` and `score` require `--frozen-sha256`; obtain it when the model
set is first published and pin it in the service configuration. Artifact hashes
are checked before the trusted, locally generated model serialization is read.
