-- A day of captures, as one batch per merchant per currency, and what each capture
-- contributed to it.
--
-- Two tables rather than one, because they answer different questions. A merchant asks "what
-- am I owed for Tuesday", which is the batch. A merchant disputing a figure asks "which
-- payments made that up and what did each cost me", which is the items — and an answer that
-- could only be recomputed is an answer that changes when the fee rule does.

create table settlement_batch (
    id uuid primary key,
    merchant_id uuid not null,
    -- The day being settled, in the platform's settlement zone. A date rather than a range,
    -- because "Tuesday" is what a merchant and a bank both say, and two systems that disagree
    -- about when Tuesday started cannot reconcile.
    settled_for date not null,
    currency text not null,
    -- All three stored rather than two stored and one derived. A derived figure is a figure
    -- that changes when the rule changes, and a settlement a merchant has already been shown
    -- must not move afterwards.
    captured bigint not null,
    fee bigint not null,
    net bigint not null,
    payments integer not null,
    -- The fee rule as it was applied, kept with the batch. Without it, nobody can answer
    -- "why was I charged this" about a batch from before the rule changed.
    fee_basis_points integer not null,
    fee_fixed_per_payment bigint not null,
    closed_at timestamptz not null,
    constraint settlement_batch_once unique (merchant_id, settled_for, currency),
    -- The arithmetic, asserted by the database. A batch whose parts do not add up is worse
    -- than no batch, and this is the half of the check that does not depend on the code
    -- being right.
    constraint settlement_batch_adds_up check (net = captured - fee),
    constraint settlement_batch_sane check (
        captured >= 0 and fee >= 0 and net >= 0 and payments > 0
        and fee_basis_points >= 0 and fee_fixed_per_payment >= 0)
);

create index settlement_batch_merchant_idx on settlement_batch (merchant_id, settled_for desc);

-- What has been captured and is waiting to be settled, learned from payment events.
--
-- This is the service's own copy of a fact it was told, not a reach into the payment
-- database. It exists because a batch is a decision about a set, and a set has to be
-- enumerable at the moment the decision is made.
create table settleable (
    payment_id uuid primary key,
    merchant_id uuid not null,
    amount bigint not null,
    currency text not null,
    -- When the money was taken, from the event. The day a capture belongs to is decided from
    -- this rather than from when this service heard about it: a consumer that was down for an
    -- hour must not move payments into the wrong day.
    captured_at timestamptz not null,
    settled_for date not null,
    -- The acquirer's reference, which is what a bank statement will name it by. Kept now
    -- because reconciliation cannot ask for it later.
    acquirer_reference text,
    -- Which batch claimed it, or null while it is still waiting. A payment belongs to exactly
    -- one batch forever, which is what makes closing a day repeatable. A capture that arrives
    -- after its own day was settled keeps its date here and is claimed by the next close: a
    -- batch a merchant has been shown must not change, and a payment must not be stranded.
    batch_id uuid references settlement_batch (id),
    -- What this payment contributed to that batch's fee. Allocated from the batch figure
    -- rather than computed per payment, so the parts always add back to the whole.
    fee bigint,
    constraint settleable_sane check (amount > 0),
    constraint settleable_fee_with_batch check ((batch_id is null) = (fee is null))
);

-- The query the close runs: everything up to a day, for a merchant and a currency, that no
-- batch has claimed yet. Up to rather than on, so a late capture is paid in the next batch
-- instead of waiting for a day that will never be closed again.
create index settleable_waiting_idx on settleable (settled_for, merchant_id, currency)
    where batch_id is null;

create index settleable_batch_idx on settleable (batch_id);
