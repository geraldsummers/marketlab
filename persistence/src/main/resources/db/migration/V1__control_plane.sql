CREATE TABLE market_datasets (
    id UUID PRIMARY KEY,
    source TEXT NOT NULL,
    venue TEXT NOT NULL,
    instrument TEXT NOT NULL,
    data_kind TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT market_datasets_identity UNIQUE (source, venue, instrument, data_kind)
);

CREATE TABLE data_objects (
    id UUID PRIMARY KEY,
    dataset_id UUID NOT NULL REFERENCES market_datasets(id),
    content_hash CHAR(64) NOT NULL UNIQUE
        CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    object_uri TEXT NOT NULL,
    retrieved_at TIMESTAMPTZ NOT NULL,
    event_time_start TIMESTAMPTZ,
    event_time_end TIMESTAMPTZ,
    available_time_start TIMESTAMPTZ,
    available_time_end TIMESTAMPTZ,
    row_count BIGINT NOT NULL CHECK (row_count >= 0),
    byte_count BIGINT NOT NULL CHECK (byte_count >= 0),
    schema_version TEXT NOT NULL,
    source_metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT data_objects_event_range CHECK (
        event_time_start IS NULL OR event_time_end IS NULL OR event_time_end >= event_time_start
    ),
    CONSTRAINT data_objects_available_range CHECK (
        available_time_start IS NULL OR available_time_end IS NULL OR available_time_end >= available_time_start
    )
);

CREATE INDEX data_objects_dataset_time_idx
    ON data_objects (dataset_id, event_time_start, event_time_end);

CREATE TABLE data_snapshots (
    id UUID PRIMARY KEY,
    manifest_hash CHAR(64) NOT NULL UNIQUE
        CHECK (manifest_hash ~ '^[0-9a-f]{64}$'),
    status TEXT NOT NULL DEFAULT 'READY'
        CHECK (status IN ('BUILDING', 'READY', 'REJECTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE TABLE snapshot_requirements (
    snapshot_id UUID NOT NULL REFERENCES data_snapshots(id) ON DELETE CASCADE,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    requirement JSONB NOT NULL,
    PRIMARY KEY (snapshot_id, ordinal)
);

CREATE TABLE snapshot_objects (
    snapshot_id UUID NOT NULL REFERENCES data_snapshots(id) ON DELETE RESTRICT,
    data_object_id UUID NOT NULL REFERENCES data_objects(id) ON DELETE RESTRICT,
    ordinal INTEGER NOT NULL CHECK (ordinal >= 0),
    PRIMARY KEY (snapshot_id, data_object_id),
    CONSTRAINT snapshot_objects_ordinal UNIQUE (snapshot_id, ordinal)
);

CREATE TABLE data_quality_findings (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    snapshot_id UUID REFERENCES data_snapshots(id) ON DELETE CASCADE,
    data_object_id UUID REFERENCES data_objects(id) ON DELETE CASCADE,
    severity TEXT NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'ERROR', 'FATAL')),
    code TEXT NOT NULL,
    event_time_start TIMESTAMPTZ,
    event_time_end TIMESTAMPTZ,
    details JSONB NOT NULL,
    detected_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT data_quality_owner CHECK (snapshot_id IS NOT NULL OR data_object_id IS NOT NULL),
    CONSTRAINT data_quality_range CHECK (
        event_time_start IS NULL OR event_time_end IS NULL OR event_time_end >= event_time_start
    )
);

CREATE INDEX data_quality_snapshot_idx ON data_quality_findings (snapshot_id, severity);
CREATE INDEX data_quality_object_idx ON data_quality_findings (data_object_id, severity);

CREATE TABLE theory_versions (
    theory_id TEXT NOT NULL,
    version TEXT NOT NULL,
    plan_hash CHAR(64) NOT NULL UNIQUE
        CHECK (plan_hash ~ '^[0-9a-f]{64}$'),
    descriptor JSONB NOT NULL,
    plan JSONB NOT NULL,
    descriptor_canonical TEXT NOT NULL,
    plan_canonical TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (theory_id, version)
);

CREATE TABLE experiment_runs (
    id UUID PRIMARY KEY,
    theory_id TEXT NOT NULL,
    theory_version TEXT NOT NULL,
    theory_plan_hash CHAR(64) NOT NULL,
    snapshot_id UUID NOT NULL REFERENCES data_snapshots(id),
    status TEXT NOT NULL
        CHECK (status IN (
            'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED',
            'REJECTED', 'INCONCLUSIVE'
        )),
    promotion_status TEXT NOT NULL DEFAULT 'NOT_EVALUATED'
        CHECK (promotion_status IN ('NOT_EVALUATED', 'PASSED', 'FAILED', 'BLOCKED')),
    parameters JSONB NOT NULL,
    manifest JSONB,
    metrics JSONB,
    failure JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    CONSTRAINT experiment_runs_theory_fk
        FOREIGN KEY (theory_id, theory_version)
        REFERENCES theory_versions(theory_id, version),
    CONSTRAINT experiment_runs_plan_fk
        FOREIGN KEY (theory_plan_hash)
        REFERENCES theory_versions(plan_hash),
    CONSTRAINT experiment_runs_terminal_time CHECK (
        completed_at IS NULL OR status IN ('SUCCEEDED', 'FAILED', 'CANCELLED', 'REJECTED', 'INCONCLUSIVE')
    )
);

CREATE INDEX experiment_runs_theory_created_idx
    ON experiment_runs (theory_id, created_at DESC);
CREATE INDEX experiment_runs_snapshot_idx
    ON experiment_runs (snapshot_id, created_at DESC);

CREATE TABLE run_trials (
    run_id UUID NOT NULL REFERENCES experiment_runs(id) ON DELETE CASCADE,
    trial_number INTEGER NOT NULL CHECK (trial_number >= 0),
    parameters JSONB NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'ABANDONED')),
    metrics JSONB,
    failure JSONB,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    PRIMARY KEY (run_id, trial_number)
);

CREATE TABLE artifacts (
    id UUID PRIMARY KEY,
    run_id UUID REFERENCES experiment_runs(id) ON DELETE RESTRICT,
    kind TEXT NOT NULL,
    content_hash CHAR(64) NOT NULL
        CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    object_uri TEXT NOT NULL,
    byte_count BIGINT NOT NULL CHECK (byte_count >= 0),
    media_type TEXT NOT NULL,
    manifest JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT artifacts_content_identity UNIQUE (content_hash, kind)
);

CREATE INDEX artifacts_run_idx ON artifacts (run_id, created_at);

CREATE TABLE jobs (
    id UUID PRIMARY KEY,
    kind TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    resource_id TEXT NOT NULL,
    payload JSONB NOT NULL,
    status TEXT NOT NULL DEFAULT 'QUEUED'
        CHECK (status IN ('QUEUED', 'LEASED', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    priority INTEGER NOT NULL DEFAULT 0,
    available_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    max_attempts INTEGER NOT NULL DEFAULT 3 CHECK (max_attempts > 0),
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    lease_owner TEXT,
    lease_token UUID,
    lease_until TIMESTAMPTZ,
    cancellation_requested BOOLEAN NOT NULL DEFAULT FALSE,
    result JSONB,
    last_error JSONB,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    CONSTRAINT jobs_lease_shape CHECK (
        (status = 'LEASED' AND lease_owner IS NOT NULL AND lease_token IS NOT NULL AND lease_until IS NOT NULL)
        OR
        (status <> 'LEASED' AND lease_owner IS NULL AND lease_token IS NULL AND lease_until IS NULL)
    )
);

CREATE INDEX jobs_claim_idx
    ON jobs (priority DESC, available_at, created_at)
    WHERE status IN ('QUEUED', 'LEASED') AND cancellation_requested = FALSE;
CREATE INDEX jobs_resource_idx ON jobs (resource_type, resource_id);

CREATE FUNCTION synchronize_experiment_run_from_job() RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE
    run_uuid UUID;
BEGIN
    IF NEW.kind <> 'EXPERIMENT_RUN' OR NEW.resource_type <> 'run' THEN
        RETURN NEW;
    END IF;
    run_uuid := NEW.resource_id::UUID;
    IF NOT EXISTS (SELECT 1 FROM public.experiment_runs WHERE id = run_uuid) THEN
        RAISE EXCEPTION
            'experiment job % references missing run %',
            NEW.id,
            run_uuid;
    END IF;

    -- Cancellation wins even if a stale attempt raced far enough to publish a
    -- result. Evidence remains stored, but it cannot become paper-eligible.
    IF NEW.cancellation_requested OR NEW.status = 'CANCELLED' THEN
        UPDATE public.experiment_runs
        SET status = 'CANCELLED',
            promotion_status = 'BLOCKED',
            failure = pg_catalog.jsonb_build_object(
                'code', 'JOB_CANCELLED',
                'jobId', NEW.id::TEXT,
                'message', 'The experiment job was cancelled before terminal success'
            ),
            completed_at = pg_catalog.clock_timestamp()
        WHERE id = run_uuid AND status <> 'CANCELLED';
        RETURN NEW;
    END IF;

    -- A final infrastructure failure blocks an otherwise raced successful run.
    -- Explicit scientific terminals (REJECTED/INCONCLUSIVE) remain distinct.
    IF NEW.status = 'FAILED' THEN
        UPDATE public.experiment_runs
        SET status = 'FAILED',
            promotion_status = 'BLOCKED',
            failure = COALESCE(
                NEW.last_error,
                pg_catalog.jsonb_build_object(
                    'code', 'EXPERIMENT_JOB_FAILED',
                    'jobId', NEW.id::TEXT
                )
            ),
            completed_at = pg_catalog.clock_timestamp()
        WHERE id = run_uuid AND status IN ('QUEUED', 'RUNNING', 'SUCCEEDED');
        RETURN NEW;
    END IF;

    IF NEW.status = 'SUCCEEDED' AND EXISTS (
        SELECT 1
        FROM public.experiment_runs
        WHERE id = run_uuid AND status IN ('QUEUED', 'RUNNING')
    ) THEN
        RAISE EXCEPTION
            'experiment job % cannot succeed before run % is terminal',
            NEW.id,
            run_uuid;
    END IF;
    RETURN NEW;
END;
$$;

REVOKE ALL ON FUNCTION synchronize_experiment_run_from_job() FROM PUBLIC;

CREATE TRIGGER jobs_synchronize_experiment_run
    AFTER UPDATE OF status, cancellation_requested, last_error ON jobs
    FOR EACH ROW EXECUTE FUNCTION synchronize_experiment_run_from_job();

CREATE TABLE job_attempts (
    id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES jobs(id) ON DELETE CASCADE,
    attempt_number INTEGER NOT NULL CHECK (attempt_number > 0),
    worker_id TEXT NOT NULL,
    lease_token UUID NOT NULL UNIQUE,
    leased_at TIMESTAMPTZ NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL,
    finished_at TIMESTAMPTZ,
    outcome TEXT CHECK (outcome IN ('SUCCEEDED', 'RETRYABLE_FAILURE', 'FINAL_FAILURE', 'CANCELLED', 'LEASE_EXPIRED')),
    details JSONB,
    CONSTRAINT job_attempt_number UNIQUE (job_id, attempt_number)
);

CREATE INDEX job_attempts_job_idx ON job_attempts (job_id, attempt_number DESC);

CREATE TABLE job_schedules (
    id UUID PRIMARY KEY,
    name TEXT NOT NULL UNIQUE,
    job_kind TEXT NOT NULL,
    resource_type TEXT NOT NULL,
    payload JSONB NOT NULL,
    cron_utc TEXT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    next_fire_at TIMESTAMPTZ NOT NULL,
    last_fire_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX job_schedules_due_idx ON job_schedules (next_fire_at) WHERE enabled = TRUE;

CREATE TABLE idempotency_records (
    scope TEXT NOT NULL,
    idempotency_key TEXT NOT NULL,
    request_hash CHAR(64) NOT NULL
        CHECK (request_hash ~ '^[0-9a-f]{64}$'),
    resource_type TEXT NOT NULL,
    resource_id TEXT NOT NULL,
    response_status INTEGER NOT NULL,
    response_body JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ,
    PRIMARY KEY (scope, idempotency_key)
);

CREATE INDEX idempotency_expiry_idx ON idempotency_records (expires_at)
    WHERE expires_at IS NOT NULL;

CREATE TABLE paper_sessions (
    id UUID PRIMARY KEY,
    run_id UUID NOT NULL REFERENCES experiment_runs(id) ON DELETE RESTRICT,
    theory_id TEXT NOT NULL,
    theory_version TEXT NOT NULL,
    status TEXT NOT NULL CHECK (status IN ('CREATED', 'RUNNING', 'PAUSED', 'STOPPED', 'HALTED')),
    initial_equity NUMERIC(38, 18) NOT NULL CHECK (initial_equity > 0),
    risk_profile TEXT NOT NULL,
    last_sequence BIGINT NOT NULL DEFAULT 0 CHECK (last_sequence >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    stopped_at TIMESTAMPTZ,
    CONSTRAINT paper_sessions_theory_fk
        FOREIGN KEY (theory_id, theory_version)
        REFERENCES theory_versions(theory_id, version)
);

CREATE TABLE paper_events (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES paper_sessions(id) ON DELETE RESTRICT,
    sequence BIGINT NOT NULL CHECK (sequence > 0),
    event_type TEXT NOT NULL,
    event_time TIMESTAMPTZ NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    market_event_ref JSONB,
    payload JSONB NOT NULL,
    idempotency_key TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT paper_events_sequence UNIQUE (session_id, sequence),
    CONSTRAINT paper_events_idempotency UNIQUE (session_id, idempotency_key)
);

CREATE INDEX paper_events_session_time_idx
    ON paper_events (session_id, sequence);

CREATE TABLE paper_balances_projection (
    session_id UUID PRIMARY KEY REFERENCES paper_sessions(id) ON DELETE CASCADE,
    equity NUMERIC(38, 18) NOT NULL,
    cash NUMERIC(38, 18) NOT NULL,
    realized_pnl NUMERIC(38, 18) NOT NULL,
    unrealized_pnl NUMERIC(38, 18) NOT NULL,
    fees NUMERIC(38, 18) NOT NULL,
    funding NUMERIC(38, 18) NOT NULL,
    as_of_sequence BIGINT NOT NULL
);

CREATE TABLE paper_positions_projection (
    session_id UUID NOT NULL REFERENCES paper_sessions(id) ON DELETE CASCADE,
    instrument TEXT NOT NULL,
    quantity NUMERIC(38, 18) NOT NULL,
    average_price NUMERIC(38, 18),
    mark_price NUMERIC(38, 18),
    realized_pnl NUMERIC(38, 18) NOT NULL,
    unrealized_pnl NUMERIC(38, 18) NOT NULL,
    as_of_sequence BIGINT NOT NULL,
    PRIMARY KEY (session_id, instrument)
);

CREATE TABLE paper_orders_projection (
    session_id UUID NOT NULL REFERENCES paper_sessions(id) ON DELETE CASCADE,
    order_id UUID NOT NULL,
    instrument TEXT NOT NULL,
    side TEXT NOT NULL CHECK (side IN ('BUY', 'SELL')),
    order_type TEXT NOT NULL,
    status TEXT NOT NULL,
    quantity NUMERIC(38, 18) NOT NULL,
    filled_quantity NUMERIC(38, 18) NOT NULL,
    limit_price NUMERIC(38, 18),
    as_of_sequence BIGINT NOT NULL,
    PRIMARY KEY (session_id, order_id)
);

CREATE FUNCTION reject_paper_event_mutation() RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'paper_events is append-only';
END;
$$;

CREATE TRIGGER paper_events_no_update
    BEFORE UPDATE OR DELETE ON paper_events
    FOR EACH ROW EXECUTE FUNCTION reject_paper_event_mutation();
