-- Giving money back.
--
-- A refund is not a state of the payment. A payment that has been half refunded is still
-- captured: the money moved and that remains true. So refunds are their own things that happen
-- to a captured payment, and the payment keeps only the one number the rules depend on.
--
-- Nothing here undoes anything. The capture stays, its entry stays, and a refund is a new
-- movement that partly or wholly reverses it. ADR 0012 refused to let the journal be edited
-- and this is the story that cashes that in.

-- What has been given back so far. Kept on the payment rather than summed from the refunds on
-- every request, because it is the number the limit is checked against and it has to be read
-- under a lock. Summing would mean locking every refund row instead of one payment row.
alter table payment add column refunded_amount bigint not null default 0;

-- The invariant, at the level that cannot be bypassed. The service checks it too, under a row
-- lock, so a caller gets a sentence rather than a constraint violation; this is what holds if
-- the service is ever wrong.
alter table payment add constraint payment_refund_within_capture
    check (refunded_amount >= 0 and refunded_amount <= amount);

-- And money can only be given back if it was taken. A payment that was authorized and voided
-- moved nothing, so there is nothing to give back.
alter table payment add constraint payment_refund_needs_capture
    check (refunded_amount = 0 or status = 'CAPTURED');

create table refund (
    id uuid primary key,
    payment_id uuid not null references payment (id),
    merchant_id uuid not null,
    amount bigint not null,
    currency text not null,
    -- The merchant's own name for this refund. Unique per payment, which is what makes a
    -- retry safe: a caller who never heard the answer sends the same one again and gets the
    -- first refund back rather than a second one.
    reference text not null,
    -- One value today, because a refund row exists only once the money has actually gone
    -- back and the books say so. In-flight states arrive in MIZ-52, together with the
    -- machinery that can resolve them: a status nothing can move out of is a status that
    -- strands rows.
    status text not null,
    reason text,
    -- The acquirer's reference for the money going back, and the entry in the ledger that
    -- records it. Both null until those steps have happened.
    acquirer_reference text,
    ledger_entry_id uuid,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    constraint refund_amount_positive check (amount > 0),
    constraint refund_status_known check (status in ('SUCCEEDED')),
    constraint refund_reference_per_payment unique (payment_id, reference)
);

create index refund_payment_idx on refund (payment_id, created_at desc);
create index refund_merchant_idx on refund (merchant_id, created_at desc);

-- One entry per refund, for the same reason a payment may point at only one capture entry:
-- two would mean the money was recorded as going back twice.
create unique index refund_ledger_entry_once
    on refund (ledger_entry_id)
    where ledger_entry_id is not null;

-- A refund that succeeded has to say where the money went back through and where the books
-- record it. One that failed has neither, and says why instead.
alter table refund add constraint refund_succeeded_is_recorded
    check (status <> 'SUCCEEDED' or (acquirer_reference is not null and ledger_entry_id is not null));
