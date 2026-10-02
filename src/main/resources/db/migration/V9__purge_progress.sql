-- Task 9c purge progress. Forward-only; V1-V8 remain immutable.
-- Widens the purge-intent state machine from pending-only to
-- pending/in_progress/complete and adds durable progress columns plus the
-- scheduler claim index. No live-account cascade is added.

ALTER TABLE account_purge_intents DROP CONSTRAINT IF EXISTS account_purge_intents_state_check;
ALTER TABLE account_purge_intents
    ADD CONSTRAINT account_purge_intents_state_check
    CHECK (state IN ('pending', 'in_progress', 'complete'));

ALTER TABLE account_purge_intents ADD COLUMN IF NOT EXISTS phase text NOT NULL DEFAULT 'pending';
ALTER TABLE account_purge_intents ADD COLUMN IF NOT EXISTS attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0);
ALTER TABLE account_purge_intents ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE account_purge_intents ADD COLUMN IF NOT EXISTS completed_at timestamptz NULL;
ALTER TABLE account_purge_intents ADD COLUMN IF NOT EXISTS last_error text NULL;

CREATE INDEX IF NOT EXISTS account_purge_intents_state_purge_by
    ON account_purge_intents (state, purge_by);
