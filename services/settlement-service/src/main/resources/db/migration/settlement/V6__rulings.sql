-- What a person decided about a difference between this platform and the bank.
--
-- A table of its own rather than a column on the difference, and append only, for the reason
-- the journal is: a decision is evidence of what somebody did, and evidence the next decision
-- can overwrite is not evidence. It is also why nothing here marks a difference "resolved" —
-- whether a difference is outstanding is derived from whether anybody has ruled on it, so
-- there is one fact rather than two that can disagree.
--
-- The same shape payment-service settled on for stuck payments in MIZ-53, because it is the
-- same question about a different subject: who decided, what they decided, why, and what it
-- changed. Two operator records that answer that differently is one too many.

create table reconciliation_ruling (
    id uuid primary key,
    difference_id uuid not null references reconciliation_difference (id),
    ruling text not null,
    -- Both required, in the domain and here. A decision nobody owns and nobody explained is
    -- not an audit trail, and a not-null constraint is what makes that true of rows that
    -- arrive by some other route than the endpoint.
    ruled_by text not null,
    why text not null,
    -- The entry an operator says corrects this, when they say one does. Not a movement made
    -- here: ruling on a difference never moves money, and a correction is an entry in the
    -- ledger like any other, visible as a correction rather than as a tidy-up. This is only
    -- the claim, and the claim is checked against the ledger before it is written.
    corrected_by uuid,
    ruled_at timestamptz not null,
    constraint reconciliation_ruling_known check (ruling in ('ACKNOWLEDGED', 'CORRECTED')),
    constraint reconciliation_ruling_said_something check (
        length(trim(ruled_by)) > 0 and length(trim(why)) > 0),
    -- A correction that names no entry is a claim about the books with nothing to check it
    -- against, and an acknowledgement that names one is claiming two different things.
    constraint reconciliation_ruling_correction_names_its_entry check (
        (ruling = 'CORRECTED') = (corrected_by is not null))
);

create index reconciliation_ruling_difference_idx
    on reconciliation_ruling (difference_id, ruled_at);

create index reconciliation_ruling_recent_idx on reconciliation_ruling (ruled_at desc);

create or replace function reconciliation_ruling_is_append_only() returns trigger as $$
begin
    raise exception 'a ruling records what a person decided and cannot be %',
        case when tg_op = 'DELETE' then 'deleted' else 'changed' end;
end;
$$ language plpgsql;

create trigger reconciliation_ruling_no_rewriting
    before update or delete on reconciliation_ruling
    for each row execute function reconciliation_ruling_is_append_only();
