-- Task 9b minimal deletion evidence and separate pending purge intent.
-- Forward-only; V1-V7 remain immutable. No domain payload or credential is stored here.

CREATE TABLE account_deletion_ledger (
    old_account_id uuid PRIMARY KEY,
    subject_digest_version smallint NOT NULL CHECK (subject_digest_version BETWEEN 1 AND 32767),
    subject_digest bytea NOT NULL CHECK (octet_length(subject_digest) = 32),
    deleted_at timestamptz NOT NULL,
    purge_by timestamptz NOT NULL,
    deletion_key uuid NOT NULL,
    request_digest_version smallint NOT NULL CHECK (request_digest_version BETWEEN 1 AND 32767),
    request_digest bytea NOT NULL CHECK (octet_length(request_digest) = 32),
    CHECK (purge_by = deleted_at + interval '86400 seconds')
);
-- Account-scoped replay uses (old_account_id, deletion_key) via the primary key row;
-- no globally unique index on deletion_key alone is created.
CREATE INDEX account_deletion_ledger_subject_lookup
    ON account_deletion_ledger (subject_digest_version, subject_digest, deleted_at DESC);

CREATE TABLE account_purge_intents (
    old_account_id uuid PRIMARY KEY REFERENCES account_deletion_ledger(old_account_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    purge_by timestamptz NOT NULL,
    state text NOT NULL CHECK (state = 'pending'),
    CHECK (purge_by = created_at + interval '86400 seconds')
);
CREATE INDEX account_purge_intents_purge_by ON account_purge_intents (purge_by);
