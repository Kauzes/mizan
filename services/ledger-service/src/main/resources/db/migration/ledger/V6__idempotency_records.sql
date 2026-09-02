-- Where an Idempotency-Key and what it produced are kept.
--
-- Each service owns its own copy, because each service owns its own schema and none of them
-- reads another's. The shape is the same everywhere on purpose: the code that uses it is
-- shared, so the table it writes to has to be.

create table idempotency_record (
    id uuid primary key,
    merchant_id uuid not null,
    -- The method and the mapped pattern, so one key means one thing per operation rather
    -- than per resource it happened to name.
    endpoint text not null,
    idempotency_key text not null,
    -- A digest of what was asked for, so a key reused for a different request is refused
    -- rather than answered with somebody else's result.
    request_fingerprint text not null,
    -- Null until the handler finished. A row with no status is a request in flight.
    status integer,
    response_body text,
    created_at timestamptz not null,
    completed_at timestamptz,
    constraint idempotency_record_key_unique unique (merchant_id, endpoint, idempotency_key)
);

create index idempotency_record_created_idx on idempotency_record (created_at);
