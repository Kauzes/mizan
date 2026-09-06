-- Where a payment goes when no amount of retrying will move it.
--
-- Three stories have each left one kind of stuck behind, each handled correctly in isolation
-- and each invisible unless somebody goes looking in a different place for it: an
-- authorization whose outcome the acquirer has no record of, a capture the books refused, a
-- refund whose saga gave up. This is the one place that answers "what is stuck, and why".

-- How many times resolving this payment has been tried.
--
-- MIZ-44's sweep asked the acquirer about an unresolved payment on every pass, forever. That
-- is right while there is any chance of an answer and wrong once there plainly is not: an
-- acquirer that has never heard of a request will not have heard of it tomorrow either, and a
-- sweep that keeps asking is a service that never notices it is not getting anywhere.
alter table payment add column resolve_attempts integer not null default 0;

-- When this payment stopped being something the platform could sort out on its own, and why.
-- Null for the overwhelming majority, which is the point: this column is a list of what needs
-- a person, and it should almost always be empty.
alter table payment add column needs_attention_since timestamptz;
alter table payment add column attention_reason text;

-- What the sweep asks for, and what an operator asks for. Both partial, because both answers
-- are nearly always tiny while the table is not.
create index payment_needs_attention_idx
    on payment (needs_attention_since)
    where needs_attention_since is not null;

-- When a person dealt with an abandoned refund.
--
-- A separate column rather than another status, because ABANDONED stays true: nobody could
-- finish it, and that remains a fact about the refund forever. What changes is that somebody
-- has looked, which is a fact about the operator rather than about the money.
alter table refund add column attention_handled_at timestamptz;

-- Superseded by the one below, which asks the question an operator actually asks: what is
-- abandoned and nobody has dealt with yet.
drop index if exists refund_abandoned_idx;

create index refund_needs_a_person_idx
    on refund (updated_at)
    where status = 'ABANDONED' and attention_handled_at is null;

-- What a person decided about something the platform could not.
--
-- Append only, and deliberately separate from the thing it is about: a decision is evidence of
-- what somebody did, and evidence that can be overwritten by the next decision is not evidence.
-- The same reasoning as the journal.
create table operator_decision (
    id uuid primary key,
    -- What was decided about. Not a foreign key, because it points at either a payment or a
    -- refund and a column cannot reference two tables.
    subject_kind text not null,
    subject_id uuid not null,
    merchant_id uuid not null,
    -- RETRY puts it back in front of the sweep; CLOSED says a person has dealt with it and it
    -- should stop appearing.
    decision text not null,
    -- Who. Taken from the request rather than proven: this endpoint sits outside the
    -- platform's authorization machinery, which only guards /api/. Written down here and in
    -- ADR 0028 rather than left to be discovered, because an audit trail whose "who" is
    -- self-declared is worth exactly as much as the honesty of whoever declared it.
    decided_by text not null,
    why text not null,
    -- What it actually changed, so the record is of an effect rather than an intention.
    changed text not null,
    at timestamptz not null,
    constraint operator_decision_subject_known check (subject_kind in ('PAYMENT', 'REFUND')),
    constraint operator_decision_known check (decision in ('RETRY', 'CLOSED'))
);

create index operator_decision_subject_idx on operator_decision (subject_kind, subject_id, at desc);

-- Nothing rewrites a decision. A correction is another decision saying so.
create or replace function operator_decision_is_append_only() returns trigger as $$
begin
    raise exception 'a decision is a record of what somebody did and cannot be % ',
        case when tg_op = 'DELETE' then 'deleted' else 'changed' end;
end;
$$ language plpgsql;

create trigger operator_decision_no_rewriting
    before update or delete on operator_decision
    for each row execute function operator_decision_is_append_only();
