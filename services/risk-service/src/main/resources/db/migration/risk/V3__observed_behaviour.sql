-- What has actually happened, so that "unusual" can mean unusual for this merchant.
--
-- MIZ-56 scored against numbers somebody chose. A rule that fires above three times 10,000 is
-- right for a coffee shop and absurd for a car dealer, and no amount of tuning by hand keeps it
-- right for both as they change. These tables are what the scorer compares against instead.
--
-- Everything here is a projection of the payment events this service consumes. It reads no
-- other service's tables — that boundary is the reason these are separate services — and
-- rebuilding from the events again produces the same rows, which is the property that makes it
-- a projection rather than a cache with better manners.

-- One row per payment this service has been told about.
--
-- Kept rather than folded straight into a running total, for two reasons. A projection has to
-- be rebuildable, and a running total that has forgotten its inputs cannot be rebuilt. And
-- "how many times has this card been used here in the last five minutes" is a question about
-- individual payments that no aggregate answers.
create table observed_payment (
    -- The event that told us. Deduplication is the inbox's job, and this is the second line
    -- of it: replaying the whole topic rebuilds these rows rather than doubling them.
    event_id uuid primary key,
    payment_id uuid not null,
    merchant_id uuid not null,
    amount bigint not null,
    currency text not null,
    -- A stable identifier for the card, never the card. Risk has never held a card number and
    -- this table is not where that changes.
    card_fingerprint text,
    card_country text,
    -- APPROVED or DECLINED. What happened matters as much as that it happened: a declined
    -- payment says something different about a card than a successful one.
    outcome text not null,
    occurred_at timestamptz not null,
    constraint observed_payment_outcome_known check (outcome in ('APPROVED', 'DECLINED')),
    -- One row per payment per outcome, so a redelivered event updates rather than duplicates.
    constraint observed_payment_once unique (payment_id, outcome)
);

-- The three questions the scorer asks, each an index rather than a scan.
create index observed_payment_merchant_idx on observed_payment (merchant_id, occurred_at desc);
create index observed_payment_card_idx
    on observed_payment (merchant_id, card_fingerprint, occurred_at desc);

-- What a merchant's ordinary behaviour looks like, kept up to date as payments arrive.
--
-- Derived from observed_payment and stored, because the scorer sits in front of an
-- authorization and cannot afford to compute a median across a merchant's whole history on
-- every request. It is a cache of a projection, which is allowed as long as it can be rebuilt
-- from the projection — and it can.
create table merchant_baseline (
    merchant_id uuid primary key,
    -- The typical amount. A median rather than a mean: one car sold by a coffee shop should not
    -- redefine what a coffee costs, and a mean lets a single outlier do exactly that. This is
    -- the single most important number in the whole scorer.
    typical_amount bigint not null,
    -- How many payments it is based on. A baseline from three payments is a rumour, and the
    -- scorer needs to know the difference between that and one from three thousand.
    payments_seen integer not null,
    updated_at timestamptz not null,
    constraint merchant_baseline_sane check (typical_amount >= 0 and payments_seen >= 0)
);

-- Which countries a merchant has actually been paid from.
--
-- A table rather than an array, so that "has this merchant seen a GB card" is an index lookup
-- and not a scan through an array in every row.
create table merchant_country (
    merchant_id uuid not null,
    country text not null,
    -- How many times, so that a single payment from somewhere does not make it familiar
    -- forever. One payment from a country is not a pattern.
    seen integer not null default 1,
    first_seen_at timestamptz not null,
    primary key (merchant_id, country)
);
