-- The journal: every movement of money, and the invariant that keeps it honest.
--
-- An entry is a set of postings that sum to zero. A posting carries a signed amount in minor
-- units, where a positive amount is a debit and a negative one is a credit. Whether a debit
-- makes an account larger is a property of the account's type, not of the posting, so the
-- arithmetic here is simply that every entry adds up to nothing.
--
-- A posting has no currency of its own. It borrows the currency of the account it names,
-- which is the only way the two cannot disagree.

create table journal_entry (
    id uuid primary key,
    merchant_id uuid not null,
    description text not null,
    -- When the money moved, which is not when this row was written. Reports need the former
    -- and audits need the latter, so both are kept.
    occurred_at timestamptz not null,
    recorded_at timestamptz not null,
    -- Nothing is ever edited, so a mistake is corrected by a new entry that says what it
    -- corrects.
    corrects uuid references journal_entry (id)
);

create index journal_entry_merchant_idx on journal_entry (merchant_id, occurred_at);
create index journal_entry_corrects_idx on journal_entry (corrects) where corrects is not null;

create table posting (
    id uuid primary key,
    entry_id uuid not null references journal_entry (id),
    account_id uuid not null references account (id),
    -- Signed minor units. Positive is a debit, negative is a credit, and zero says nothing
    -- so it is refused.
    amount bigint not null,
    constraint posting_amount_says_something check (amount <> 0)
);

create index posting_entry_idx on posting (entry_id);
create index posting_account_idx on posting (account_id);

-- The invariant, enforced here as well as in the application.
--
-- Deferred to the end of the transaction on purpose: postings arrive one row at a time, so a
-- check that ran per statement would refuse the first one every time. At commit, an entry
-- must have at least two postings and must sum to zero within each currency it touches.
create or replace function assert_entry_balanced() returns trigger as $$
declare
    entry uuid;
    postings integer;
    offending record;
begin
    if tg_table_name = 'posting' then
        entry := new.entry_id;
    else
        entry := new.id;
    end if;

    select count(*) into postings from posting where entry_id = entry;
    if postings < 2 then
        raise exception 'journal entry % has % posting(s); an entry moves money between at '
            'least two accounts', entry, postings;
    end if;

    select a.currency as currency, sum(p.amount) as total
      into offending
      from posting p
      join account a on a.id = p.account_id
     where p.entry_id = entry
     group by a.currency
    having sum(p.amount) <> 0
     limit 1;

    if found then
        raise exception 'journal entry % does not balance in %: its postings sum to %',
            entry, offending.currency, offending.total;
    end if;

    return null;
end;
$$ language plpgsql;

create constraint trigger journal_entry_balanced
    after insert on journal_entry
    deferrable initially deferred
    for each row execute function assert_entry_balanced();

-- Also on the posting side, so that postings added to an entry after the transaction that
-- created it are checked too. The entry trigger alone would never see them.
create constraint trigger posting_keeps_entry_balanced
    after insert on posting
    deferrable initially deferred
    for each row execute function assert_entry_balanced();

-- Append only, said by the database rather than by the absence of an endpoint. An update
-- path that exists only in SQL is still an update path.
create or replace function refuse_to_change_the_journal() returns trigger as $$
begin
    raise exception 'the journal is append only: % on % is not allowed. Correct a mistake '
        'with a new entry that says what it corrects', tg_op, tg_table_name;
end;
$$ language plpgsql;

create trigger journal_entry_is_append_only
    before update or delete on journal_entry
    for each row execute function refuse_to_change_the_journal();

create trigger posting_is_append_only
    before update or delete on posting
    for each row execute function refuse_to_change_the_journal();
