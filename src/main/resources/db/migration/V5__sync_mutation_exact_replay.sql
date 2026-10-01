ALTER TABLE processed_mutations
    ADD COLUMN request_bytes bytea;

-- Existing rows, if any, predate exact-byte push execution. A digest cannot be
-- treated as exact identity evidence, so those rows never match fresh requests.
CREATE TABLE sync_cursor_tokens (
    token text PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id),
    cursor bigint NOT NULL CHECK (cursor >= 0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (account_id, cursor)
);
