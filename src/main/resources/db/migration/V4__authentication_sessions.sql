CREATE TYPE auth_challenge_purpose AS ENUM ('signIn', 'export', 'deleteAccount', 'accountStatus');

CREATE TABLE google_identity_bindings (
    google_subject text PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX google_identity_account ON google_identity_bindings(account_id);
-- Account deletion must remove this binding in the same acceptance transaction. The
-- lifecycle deletion ledger, not this table, retains only its keyed subject digest/receipt;
-- deleting the binding releases the subject for a later fresh signup with a new account UUID.

CREATE TABLE auth_challenges (
    id uuid PRIMARY KEY,
    nonce text NOT NULL,
    purpose auth_challenge_purpose NOT NULL,
    created_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz
);
CREATE INDEX auth_challenges_expiry ON auth_challenges(expires_at);

CREATE TABLE auth_sessions (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id),
    created_at timestamptz NOT NULL,
    revoked_at timestamptz
);
CREATE INDEX auth_sessions_account ON auth_sessions(account_id);

CREATE TABLE auth_credentials (
    fingerprint bytea PRIMARY KEY CHECK (octet_length(fingerprint) = 32),
    session_id uuid NOT NULL REFERENCES auth_sessions(id),
    kind text NOT NULL CHECK (kind IN ('access', 'refresh')),
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz
);
CREATE INDEX auth_credentials_session ON auth_credentials(session_id);
CREATE INDEX auth_credentials_expiry ON auth_credentials(expires_at);

CREATE TABLE auth_idempotency (
    operation text NOT NULL CHECK (operation IN ('google', 'refresh')),
    request_key uuid NOT NULL,
    request_digest bytea NOT NULL CHECK (octet_length(request_digest) = 32),
    credential_fingerprint bytea NOT NULL CHECK (octet_length(credential_fingerprint) = 32),
    session_id uuid NOT NULL REFERENCES auth_sessions(id),
    encrypted_response bytea NOT NULL,
    refresh_fingerprint bytea NOT NULL CHECK (octet_length(refresh_fingerprint) = 32),
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (operation, request_key)
);
CREATE INDEX auth_idempotency_expiry ON auth_idempotency(expires_at);
