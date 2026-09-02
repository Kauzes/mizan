-- A payment, and everything that has happened to it.
--
-- The payment row says where it is now. The transition rows say how it got there, which is a
-- different question and the one asked when something has gone wrong. Deriving the history
-- from the current state is not possible, so it is recorded rather than reconstructed.

create table payment (
    id uuid primary key,
    merchant_id uuid not null,
    -- Minor units and an ISO 4217 code, per ADR 0002. No floating point anywhere near this.
    amount bigint not null,
    currency text not null,
    status text not null,
    -- What the merchant calls this payment, for their own reconciliation.
    reference text not null,
    description text,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    constraint payment_amount_positive check (amount > 0),
    constraint payment_currency_shape check (currency ~ '^[A-Z]{3}$'),
    constraint payment_status_known
        check (status in ('CREATED', 'AUTHORIZED', 'DECLINED', 'CAPTURED', 'VOIDED')),
    -- A merchant's own reference for a payment identifies one payment.
    constraint payment_reference_per_merchant unique (merchant_id, reference)
);

create index payment_merchant_idx on payment (merchant_id, created_at desc);

create table payment_transition (
    id uuid primary key,
    payment_id uuid not null references payment (id),
    -- Null for the first one: a payment does not come from anywhere.
    from_status text,
    to_status text not null,
    -- Why, when there is a why: the acquirer's decline reason, or what a void was for.
    reason text,
    at timestamptz not null,
    constraint payment_transition_status_known
        check (to_status in ('CREATED', 'AUTHORIZED', 'DECLINED', 'CAPTURED', 'VOIDED'))
);

create index payment_transition_payment_idx on payment_transition (payment_id, at);

-- History is written, never rewritten. The same reasoning as the journal in the ledger: a
-- record that can be edited is a record that can be made to say anything.
create or replace function refuse_to_change_the_history() returns trigger as $$
begin
    raise exception 'a payment''s history is append only: % on % is not allowed',
        tg_op, tg_table_name;
end;
$$ language plpgsql;

create trigger payment_transition_is_append_only
    before update or delete on payment_transition
    for each row execute function refuse_to_change_the_history();
