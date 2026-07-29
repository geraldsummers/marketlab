\set ON_ERROR_STOP on

DO $marketlab$
DECLARE
    app_role RECORD;
BEGIN
    FOR app_role IN
        SELECT *
        FROM pg_roles
        WHERE rolname IN ('marketlab_api', 'marketlab_coordinator')
    LOOP
        IF app_role.rolsuper
            OR app_role.rolcreatedb
            OR app_role.rolcreaterole
            OR app_role.rolreplication
            OR app_role.rolbypassrls
            OR app_role.rolinherit
        THEN
            RAISE EXCEPTION 'application role % has privileged role attributes', app_role.rolname;
        END IF;
    END LOOP;

    IF (SELECT count(*) FROM pg_roles
        WHERE rolname IN ('marketlab_api', 'marketlab_coordinator')) <> 2
    THEN
        RAISE EXCEPTION 'application roles are missing';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_auth_members memberships
        JOIN pg_roles members ON members.oid = memberships.member
        WHERE members.rolname IN ('marketlab_api', 'marketlab_coordinator')
    ) THEN
        RAISE EXCEPTION 'application roles unexpectedly inherit another role';
    END IF;

    IF has_database_privilege('marketlab_api', 'marketlab', 'CREATE')
        OR has_database_privilege('marketlab_api', 'marketlab', 'TEMPORARY')
        OR has_database_privilege('marketlab_coordinator', 'marketlab', 'CREATE')
        OR has_database_privilege('marketlab_coordinator', 'marketlab', 'TEMPORARY')
    THEN
        RAISE EXCEPTION 'application roles have database DDL or temporary-table privileges';
    END IF;

    IF has_schema_privilege('marketlab_api', 'public', 'CREATE')
        OR has_schema_privilege('marketlab_coordinator', 'public', 'CREATE')
    THEN
        RAISE EXCEPTION 'application roles can create objects in the evidence schema';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_class objects
        JOIN pg_namespace namespaces ON namespaces.oid = objects.relnamespace
        JOIN pg_roles owners ON owners.oid = objects.relowner
        WHERE namespaces.nspname = 'public'
          AND objects.relkind IN ('r', 'p', 'S', 'v', 'm')
          AND owners.rolname <> 'marketlab_owner'
    ) THEN
        RAISE EXCEPTION 'a public schema object is not owned by marketlab_owner';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM pg_proc functions
        JOIN pg_namespace namespaces ON namespaces.oid = functions.pronamespace
        JOIN pg_roles owners ON owners.oid = functions.proowner
        WHERE namespaces.nspname = 'public'
          AND owners.rolname <> 'marketlab_owner'
    ) THEN
        RAISE EXCEPTION 'a public schema function is not owned by marketlab_owner';
    END IF;

    IF has_any_column_privilege('marketlab_api', 'paper_events', 'UPDATE')
        OR has_table_privilege('marketlab_api', 'paper_events', 'DELETE')
        OR has_table_privilege('marketlab_api', 'paper_events', 'TRUNCATE')
        OR has_table_privilege('marketlab_coordinator', 'paper_events', 'INSERT')
        OR has_any_column_privilege('marketlab_coordinator', 'paper_events', 'UPDATE')
        OR has_table_privilege('marketlab_coordinator', 'paper_events', 'DELETE')
        OR has_table_privilege('marketlab_api', 'data_objects', 'INSERT')
        OR has_any_column_privilege('marketlab_api', 'data_objects', 'UPDATE')
        OR has_table_privilege('marketlab_api', 'data_objects', 'DELETE')
        OR has_any_column_privilege('marketlab_coordinator', 'data_objects', 'UPDATE')
        OR has_table_privilege('marketlab_coordinator', 'data_objects', 'DELETE')
    THEN
        RAISE EXCEPTION 'append-only evidence grants are broader than intended';
    END IF;

    IF NOT has_table_privilege('marketlab_api', 'paper_events', 'INSERT')
        OR NOT has_table_privilege('marketlab_coordinator', 'data_objects', 'INSERT')
        OR NOT has_column_privilege('marketlab_coordinator', 'jobs', 'status', 'UPDATE')
    THEN
        RAISE EXCEPTION 'required application DML grants are missing';
    END IF;

    IF has_function_privilege(
        'marketlab_api',
        'reject_paper_event_mutation()',
        'EXECUTE'
    ) OR has_function_privilege(
        'marketlab_coordinator',
        'reject_paper_event_mutation()',
        'EXECUTE'
    ) THEN
        RAISE EXCEPTION 'application role can directly execute the evidence trigger function';
    END IF;

    IF has_function_privilege(
        'marketlab_api',
        'synchronize_experiment_run_from_job()',
        'EXECUTE'
    ) OR has_function_privilege(
        'marketlab_coordinator',
        'synchronize_experiment_run_from_job()',
        'EXECUTE'
    ) THEN
        RAISE EXCEPTION 'application role can directly execute the owner-only job synchronization function';
    END IF;
END
$marketlab$;
