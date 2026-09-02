-- What a merchant is to be told, and about what.
--
-- A row here is a decision that something is worth telling somebody, not a delivery. Actually
-- sending it — signed, retried, dead lettered — is Epic 8, and it will read this table rather
-- than the topic, because by then the decision has been made once and should not be made
-- again by whatever happens to redeliver.

create table notification (
    id uuid primary key,
    merchant_id uuid not null,
    -- What it is about. No foreign key: the payment lives in another service's database and
    -- no service reads another's tables.
    payment_id uuid not null,
    kind text not null,
    -- What a person would read. Held rather than generated at send time so that what was
    -- decided and what is delivered cannot drift apart.
    message text not null,
    -- The event this came from, so a notification can be traced back to the thing that
    -- caused it, and so a duplicate would be visible rather than merely suspected.
    caused_by uuid not null,
    created_at timestamptz not null,
    constraint notification_kind_known
        check (kind in ('PAYMENT_CAPTURED', 'PAYMENT_DECLINED', 'PAYMENT_VOIDED'))
);

create index notification_merchant_idx on notification (merchant_id, created_at desc);
create index notification_payment_idx on notification (payment_id);

-- One notification per event, whatever the topic does. The inbox already refuses to handle an
-- event twice; this is the same rule written where it cannot be bypassed by a bug above it,
-- which is the pattern the ledger and the payment service both follow.
create unique index notification_caused_by_once on notification (caused_by);
