-- What the platform found when it compared its own records with the bank's.
--
-- Two tables, because a run and a difference have different lifetimes. A run is a moment: it
-- happened, it found what it found, and nothing about it changes afterwards. A difference
-- outlives the run that discovered it and stays outstanding until somebody deals with it —
-- which is the whole point, because a difference that stopped being reported because a later
-- run did not notice it is a difference nobody decided about.

create table reconciliation_run (
    id uuid primary key,
    settled_for date not null,
    currency text not null,
    ran_at timestamptz not null,
    -- What the statement itself claimed, kept beside what was made of it. A statement is a
    -- file somebody else sent, and it cannot be asked for again in the state it arrived in:
    -- a run that did not keep the header and trailer cannot be audited afterwards.
    statement_rows integer not null,
    statement_total bigint not null,
    -- The four answers. Stored as counts rather than derived from the items, because the
    -- matched ones are deliberately not kept row by row: a table of thousands of rows saying
    -- "this one was fine" is a table nobody reads and a cost everybody pays.
    matched integer not null,
    missing_from_statement integer not null,
    extra_on_statement integer not null,
    amounts_differ integer not null,
    -- And the fifth answer the story's four did not cover: a capture with no acquirer
    -- reference at all. Nothing can be said about it — calling it missing would send
    -- somebody looking for it in a file where it could never appear.
    unmatchable integer not null,
    constraint reconciliation_run_counts_sane check (
        statement_rows >= 0 and matched >= 0 and missing_from_statement >= 0
        and extra_on_statement >= 0 and amounts_differ >= 0 and unmatchable >= 0)
);

create index reconciliation_run_day_idx on reconciliation_run (settled_for desc, ran_at desc);

create or replace function reconciliation_run_is_append_only() returns trigger as $$
begin
    raise exception 'a reconciliation run records what was found and cannot be % ',
        case when tg_op = 'DELETE' then 'deleted' else 'changed' end;
end;
$$ language plpgsql;

create trigger reconciliation_run_no_rewriting
    before update or delete on reconciliation_run
    for each row execute function reconciliation_run_is_append_only();

-- One outstanding difference. Keyed by what it is about rather than by which run found it, so
-- running reconciliation again finds the same row instead of reporting the same problem twice.
create table reconciliation_difference (
    id uuid primary key,
    settled_for date not null,
    currency text not null,
    -- What it was matched on. Kept explicitly, because "matched on the acquirer's reference"
    -- is an answerable question and a difference that does not say what it was compared by is
    -- a difference nobody can check.
    acquirer_reference text not null,
    outcome text not null,
    -- Both sides, and by how much they differ. An operator who is told only "these disagree"
    -- has to go and look; one who is told both figures can act.
    platform_amount bigint,
    statement_amount bigint,
    -- What this platform thinks it belongs to, when it knows. Null for something only the
    -- statement has: there is nothing on this side to name.
    merchant_id uuid,
    payment_id uuid,
    first_seen_at timestamptz not null,
    last_seen_at timestamptz not null,
    first_seen_in uuid not null references reconciliation_run (id),
    constraint reconciliation_difference_known check (outcome in (
        'MISSING_FROM_STATEMENT', 'EXTRA_ON_STATEMENT', 'AMOUNTS_DIFFER', 'UNMATCHABLE')),
    -- One row per problem. The same day reconciled twice must find the row it already made
    -- rather than a second one.
    constraint reconciliation_difference_once
        unique (settled_for, currency, acquirer_reference, outcome)
);

create index reconciliation_difference_outstanding_idx
    on reconciliation_difference (settled_for, outcome);
