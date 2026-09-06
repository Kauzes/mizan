-- Telling a merchant, and what happened when we tried.
--
-- A delivery is one notification to one endpoint. Two endpoints subscribed to the same type
-- means two deliveries with two independent fates: one merchant's endpoint being down is not
-- a reason for anybody else to wait, and that requirement is what decides this whole design.

create table webhook_delivery (
    id uuid primary key,
    merchant_id uuid not null,
    endpoint_id uuid not null references webhook_endpoint (id) on delete cascade,
    -- What this is telling them about. Not a foreign key to notification, because a delivery
    -- outlives nothing and the notification is in this database anyway; it is here so a
    -- merchant asking "did you tell me about this payment" has something to match on.
    notification_id uuid not null,
    payment_id uuid not null,
    event_type text not null,

    -- The exact bytes that were signed and sent, stored once at creation.
    --
    -- Not rebuilt per attempt. A signature covers a body, and a body rebuilt from the same
    -- data by the same code can still differ by a field order or a timestamp — at which point
    -- the retry carries a signature for something else and the merchant rejects it. Storing
    -- the bytes means every attempt of one delivery is byte-identical, which is also what
    -- lets the merchant treat repeats as repeats.
    body text not null,

    status text not null,
    attempts integer not null default 0,
    next_attempt_at timestamptz,
    -- What the endpoint said last time, so a merchant can see 404 or 500 without opening
    -- every attempt.
    last_status_code integer,
    last_error text,
    delivered_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,

    constraint webhook_delivery_status_known
        check (status in ('PENDING', 'DELIVERED', 'FAILED')),
    -- A delivered delivery says when. One that has not been delivered does not claim to have
    -- been.
    constraint webhook_delivery_delivered_is_timed
        check ((status = 'DELIVERED') = (delivered_at is not null)),
    -- One delivery per notification per endpoint. Two would tell the merchant twice, which
    -- from the receiving end is indistinguishable from a platform that cannot count.
    constraint webhook_delivery_once unique (notification_id, endpoint_id)
);

-- What a worker asks for: waiting, due, oldest first. Partial, because the answer is nearly
-- always small while the table grows with every payment.
create index webhook_delivery_due_idx
    on webhook_delivery (next_attempt_at)
    where status = 'PENDING';

-- What a merchant asks for: what have you sent me, and how did it go.
create index webhook_delivery_endpoint_idx on webhook_delivery (endpoint_id, created_at desc);
create index webhook_delivery_merchant_idx on webhook_delivery (merchant_id, created_at desc);

-- Every attempt, not just the last one.
--
-- A merchant debugging their endpoint wants to see that we tried at 10:00 and got a 502, tried
-- again at 10:00:04 and timed out, and succeeded at 10:00:12. Keeping only the last attempt
-- answers "is it working now" and nothing else, and "is it working now" is the one question
-- they can already answer themselves.
create table webhook_delivery_attempt (
    id uuid primary key,
    delivery_id uuid not null references webhook_delivery (id) on delete cascade,
    attempt integer not null,
    at timestamptz not null,
    -- Null when nothing answered at all, which is a different fact from a 500 and is worth
    -- being able to tell apart.
    status_code integer,
    -- How long we waited, whether or not anything came back. A merchant whose endpoint is
    -- slow rather than broken finds out here.
    duration_ms bigint not null,
    error text,
    constraint webhook_delivery_attempt_once unique (delivery_id, attempt)
);

create index webhook_delivery_attempt_idx on webhook_delivery_attempt (delivery_id, attempt);
