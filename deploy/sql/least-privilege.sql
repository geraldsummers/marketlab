\set ON_ERROR_STOP on

REVOKE ALL PRIVILEGES ON DATABASE marketlab FROM PUBLIC;
REVOKE ALL PRIVILEGES ON DATABASE marketlab FROM marketlab_api, marketlab_coordinator;
GRANT CONNECT ON DATABASE marketlab TO marketlab_api, marketlab_coordinator;

REVOKE ALL PRIVILEGES ON SCHEMA public FROM PUBLIC;
REVOKE ALL PRIVILEGES ON SCHEMA public FROM marketlab_api, marketlab_coordinator;
GRANT USAGE ON SCHEMA public TO marketlab_api, marketlab_coordinator;

REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA public
    FROM marketlab_api, marketlab_coordinator;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public
    FROM marketlab_api, marketlab_coordinator;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public FROM PUBLIC;
REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public
    FROM marketlab_api, marketlab_coordinator;

GRANT SELECT ON
    data_snapshots,
    snapshot_objects,
    data_quality_findings,
    theory_versions,
    experiment_runs,
    artifacts,
    jobs,
    idempotency_records,
    paper_sessions,
    paper_events,
    paper_balances_projection,
    paper_positions_projection,
    paper_orders_projection
TO marketlab_api;

GRANT SELECT ON
    market_datasets,
    data_objects,
    data_snapshots,
    snapshot_requirements,
    snapshot_objects,
    data_quality_findings,
    theory_versions,
    experiment_runs,
    run_trials,
    artifacts,
    jobs,
    job_attempts
TO marketlab_coordinator;

GRANT INSERT ON theory_versions TO marketlab_api;
GRANT UPDATE (enabled) ON theory_versions TO marketlab_api;
GRANT INSERT ON experiment_runs TO marketlab_api;
GRANT INSERT ON jobs TO marketlab_api;
GRANT UPDATE (
    cancellation_requested,
    status,
    completed_at,
    updated_at
) ON jobs TO marketlab_api;
GRANT INSERT ON idempotency_records TO marketlab_api;
GRANT INSERT ON paper_sessions TO marketlab_api;
GRANT UPDATE (
    last_sequence,
    status,
    updated_at,
    stopped_at
) ON paper_sessions TO marketlab_api;
GRANT INSERT ON paper_events TO marketlab_api;
GRANT INSERT ON paper_balances_projection TO marketlab_api;
GRANT UPDATE (
    equity,
    cash,
    realized_pnl,
    unrealized_pnl,
    fees,
    funding,
    as_of_sequence
) ON paper_balances_projection TO marketlab_api;
GRANT INSERT ON paper_positions_projection TO marketlab_api;
GRANT UPDATE (
    quantity,
    average_price,
    mark_price,
    realized_pnl,
    unrealized_pnl,
    as_of_sequence
) ON paper_positions_projection TO marketlab_api;
GRANT INSERT ON paper_orders_projection TO marketlab_api;
GRANT UPDATE (
    status,
    filled_quantity,
    as_of_sequence
) ON paper_orders_projection TO marketlab_api;

GRANT INSERT ON
    market_datasets,
    data_objects,
    data_snapshots,
    snapshot_requirements,
    snapshot_objects,
    data_quality_findings,
    job_attempts,
    run_trials,
    artifacts
TO marketlab_coordinator;
GRANT UPDATE (status) ON data_snapshots TO marketlab_coordinator;
GRANT UPDATE (
    status,
    attempt_count,
    lease_owner,
    lease_token,
    lease_until,
    available_at,
    result,
    last_error,
    updated_at,
    completed_at
) ON jobs TO marketlab_coordinator;
GRANT UPDATE (
    lease_until,
    finished_at,
    outcome,
    details
) ON job_attempts TO marketlab_coordinator;
GRANT UPDATE (
    status,
    promotion_status,
    manifest,
    metrics,
    failure,
    started_at,
    completed_at
) ON experiment_runs TO marketlab_coordinator;
GRANT UPDATE (
    status,
    metrics,
    failure,
    started_at,
    completed_at
) ON run_trials TO marketlab_coordinator;

GRANT USAGE, SELECT ON SEQUENCE data_quality_findings_id_seq TO marketlab_coordinator;

ALTER DEFAULT PRIVILEGES FOR ROLE marketlab_owner IN SCHEMA public
    REVOKE ALL PRIVILEGES ON TABLES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE marketlab_owner IN SCHEMA public
    REVOKE ALL PRIVILEGES ON SEQUENCES FROM PUBLIC;
ALTER DEFAULT PRIVILEGES FOR ROLE marketlab_owner IN SCHEMA public
    REVOKE ALL PRIVILEGES ON FUNCTIONS FROM PUBLIC;
