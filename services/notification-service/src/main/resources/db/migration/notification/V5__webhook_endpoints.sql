-- Where a merchant wants to be told, and the secret that proves it was us.
--
-- Registered rather than configured: a merchant changes theirs without anybody deploying
-- anything, and one merchant may have several — a production endpoint and a staging one, or
-- one service that cares about captures and another that cares about refunds.

create table webhook_endpoint (
    id uuid primary key,
    merchant_id uuid not null,
    url text not null,
    -- A name the merchant chose, so a list of three URLs is readable by the person who made
    -- them rather than only by whoever wrote them down.
    description text,
    -- The shared secret, encrypted at rest exactly as an API key secret is, and bound to this
    -- endpoint's id so the ciphertext cannot be moved to another merchant's row and used to
    -- sign their traffic. Never returned after it is created: a secret this platform can show
    -- twice is one this platform is storing badly.
    --
    -- A different encryption key from the one that opens API key secrets, on purpose. A
    -- compromise of one should not be a compromise of the other.
    secret text not null,
    -- When it was last rotated, so a merchant can see whether the secret they are holding is
    -- the current one without being shown the secret.
    secret_rotated_at timestamptz not null,
    -- Disabled rather than deleted is the ordinary way to stop deliveries, because an endpoint
    -- that stops receiving and then starts again should not lose its history.
    enabled boolean not null default true,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint webhook_endpoint_https check (url like 'https://%'),
    -- One registration per URL per merchant. Two rows for one URL would deliver everything
    -- twice, which looks like a platform bug from the receiving end.
    constraint webhook_endpoint_url_once unique (merchant_id, url)
);

create index webhook_endpoint_merchant_idx on webhook_endpoint (merchant_id, created_at desc);

-- Which event types an endpoint wants.
--
-- A table rather than a column, because "which of these do you want" is a set and a set in a
-- column is a string somebody parses. It also means the query that finds who to deliver an
-- event to is an index lookup rather than a scan with a LIKE in it.
create table webhook_subscription (
    endpoint_id uuid not null references webhook_endpoint (id) on delete cascade,
    event_type text not null,
    primary key (endpoint_id, event_type)
);

-- The question asked once per notification: who wants this type?
create index webhook_subscription_type_idx on webhook_subscription (event_type);
