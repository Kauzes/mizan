-- What has reached the books, and what has been paid.
--
-- Two entries per batch, at two different moments, because they are two different facts: the
-- platform earns its fee when the batch closes, and the merchant is paid when the money is
-- actually sent. Each is recorded here by the id of the entry that says it happened, so this
-- service can tell "not yet" from "done" without asking the ledger.

alter table settlement_batch add column fee_entry_id uuid;
alter table settlement_batch add column fee_recorded_at timestamptz;
alter table settlement_batch add column payout_entry_id uuid;
alter table settlement_batch add column paid_at timestamptz;

-- An id and a moment arrive together or not at all. Half of a record is worse than none: it
-- would make a batch look paid to one query and unpaid to another.
alter table settlement_batch add constraint settlement_batch_fee_recorded
    check ((fee_entry_id is null) = (fee_recorded_at is null));
alter table settlement_batch add constraint settlement_batch_payout_recorded
    check ((payout_entry_id is null) = (paid_at is null));

-- An entry records one movement, so two batches cannot both claim one. Without this, a bug
-- that reused a reference would leave two batches looking paid by a single payout.
create unique index settlement_batch_payout_entry_once on settlement_batch (payout_entry_id)
    where payout_entry_id is not null;
create unique index settlement_batch_fee_entry_once on settlement_batch (fee_entry_id)
    where fee_entry_id is not null;

-- What the sweeps look for: a closed batch whose fee is not in the books yet, and a batch
-- that has been charged and not yet paid.
create index settlement_batch_unrecorded_idx on settlement_batch (closed_at)
    where fee_entry_id is null;
create index settlement_batch_unpaid_idx on settlement_batch (settled_for)
    where paid_at is null;
