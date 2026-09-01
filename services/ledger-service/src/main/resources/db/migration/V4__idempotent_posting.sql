-- What a caller calls this movement, so that a retry does not post it twice.
--
-- A caller that does not hear back does not know whether the money moved, so it retries. In a
-- ledger a retry that posts twice is money invented out of a dropped response, which is why
-- the reference is required rather than offered.

alter table journal_entry add column external_reference text;

-- A digest of what was asked for, so that the same reference sent with a different body is
-- refused rather than quietly answered with the earlier entry. The whole request is not kept:
-- the question is only ever whether this is the same request, and a digest answers that.
alter table journal_entry add column request_fingerprint text;

-- Entries written before this migration have no reference, and the column is about to become
-- required. Their own id is the only thing that identifies them, so that is what they get.
--
-- The journal refuses updates, which is the point of it, so the trigger has to be stood down
-- for the length of this statement. Doing that here, in a reviewed migration, is the intended
-- way past that rule and the only one.
alter table journal_entry disable trigger journal_entry_is_append_only;
update journal_entry
   set external_reference = 'backfilled:' || id,
       request_fingerprint = 'backfilled'
 where external_reference is null;
alter table journal_entry enable trigger journal_entry_is_append_only;

alter table journal_entry alter column external_reference set not null;
alter table journal_entry alter column request_fingerprint set not null;

-- Uniqueness is the database's answer, discovered by inserting rather than by asking first:
-- two retries racing would otherwise both be told the reference was free. Scoped to the
-- merchant, so two merchants may use the same reference without colliding.
create unique index journal_entry_reference_per_merchant
    on journal_entry (merchant_id, external_reference);
