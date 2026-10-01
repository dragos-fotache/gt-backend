CREATE TABLE resource_mutations (
    owner_id uuid NOT NULL REFERENCES accounts(id),
    idempotency_key uuid NOT NULL,
    request_method text NOT NULL,
    request_route text NOT NULL,
    request_body bytea NOT NULL,
    response_status integer NOT NULL CHECK (response_status BETWEEN 200 AND 599),
    response_body bytea NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (owner_id, idempotency_key)
);
