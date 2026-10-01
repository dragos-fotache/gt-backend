CREATE UNIQUE INDEX sync_cursor_tokens_account_token
    ON sync_cursor_tokens(account_id, token);

CREATE TABLE sync_snapshots (
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    snapshot_id uuid NOT NULL,
    baseline_cursor text NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    record_count bigint NOT NULL DEFAULT 0 CHECK (record_count >= 0),
    PRIMARY KEY (account_id, snapshot_id),
    FOREIGN KEY (account_id, baseline_cursor)
        REFERENCES sync_cursor_tokens(account_id, token) ON DELETE RESTRICT,
    CHECK (expires_at = created_at + interval '24 hours')
);

CREATE TABLE sync_snapshot_records (
    account_id uuid NOT NULL,
    snapshot_id uuid NOT NULL,
    ordinal bigint NOT NULL CHECK (ordinal >= 0),
    aggregate_type text NOT NULL CHECK (aggregate_type IN ('customExercise', 'routine', 'workout')),
    aggregate_id uuid NOT NULL,
    payload jsonb NOT NULL,
    PRIMARY KEY (account_id, snapshot_id, ordinal),
    UNIQUE (account_id, snapshot_id, aggregate_type, aggregate_id),
    FOREIGN KEY (account_id, snapshot_id)
        REFERENCES sync_snapshots(account_id, snapshot_id) ON DELETE CASCADE,
    CHECK (COALESCE((
        jsonb_typeof(payload) = 'object'
        AND payload ?& ARRAY['aggregateType', 'aggregateId', 'operation', 'revision', 'record']
        AND jsonb_typeof(payload->'aggregateType') = 'string'
        AND payload->>'aggregateType' = aggregate_type
        AND jsonb_typeof(payload->'aggregateId') = 'string'
        AND payload->>'aggregateId' = aggregate_id::text
        AND jsonb_typeof(payload->'operation') = 'string'
        AND payload->>'operation' = 'upsert'
        AND jsonb_typeof(payload->'revision') = 'number'
        AND CASE
            WHEN payload->>'revision' ~ '^[1-9][0-9]*$' AND length(payload->>'revision') <= 19
            THEN (payload->>'revision')::numeric <= 9223372036854775807
            ELSE false
        END
        AND jsonb_typeof(payload->'record') = 'object'
        AND (payload->'record') ?& ARRAY['id', 'revision']
        AND jsonb_typeof(payload->'record'->'id') = 'string'
        AND payload->'record'->>'id' = aggregate_id::text
        AND jsonb_typeof(payload->'record'->'revision') = 'number'
        AND payload->'record'->>'revision' = payload->>'revision'
    ), false))
);

CREATE INDEX sync_snapshots_expiry ON sync_snapshots(expires_at, account_id, snapshot_id);
