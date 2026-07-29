CREATE UNIQUE INDEX data_snapshots_ingestion_operation_key_uq
    ON data_snapshots ((metadata ->> 'ingestionOperationKey'))
    WHERE metadata ? 'ingestionOperationKey';
