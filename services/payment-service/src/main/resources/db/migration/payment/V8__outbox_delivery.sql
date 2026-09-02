-- What a relay needs in order to publish this table, which nothing did until now.
--
-- V7 wrote events and told nobody, on purpose: writing the row is the only step that can be
-- made atomic with the change that caused it. This is the other half.

-- A total order, which occurred_at alone is not. Two events a microsecond apart are ordered
-- by their timestamps; two in the same microsecond are ordered by nothing, and a relay that
-- has to publish a payment's events in the order they happened cannot be left guessing. The
-- sequence is assigned at insert, so it also survives a clock that moves.
--
-- Commit order and sequence order are not the same thing: a transaction that took a number
-- early can commit after one that took a later number. That is harmless here because the
-- relay asks for unpublished rows rather than for everything past a high water mark, so a
-- late arrival is picked up on the next pass rather than skipped forever.
alter table outbox_event add column sequence bigserial;

-- How many times publishing this has been tried, and when it may be tried again. A publish
-- that fails is not a publish that should be retried immediately and forever: that is how one
-- unreachable broker turns into a service doing nothing else.
alter table outbox_event add column attempts integer not null default 0;
alter table outbox_event add column next_attempt_at timestamptz;
-- What went wrong last time, kept so that a row stuck at the front of a payment's queue can
-- be explained without reading a log that has rotated.
alter table outbox_event add column last_error text;

-- What the relay asks for: unpublished, due, oldest first. Partial, because the answer shrinks
-- to nothing while the table grows without limit.
drop index if exists outbox_event_unpublished_idx;
create index outbox_event_unpublished_idx
    on outbox_event (sequence)
    where published_at is null;

-- And the question asked before publishing an aggregate: is anything older still unpublished?
-- If so, somebody else has it or it is waiting to be retried, and publishing this one now
-- would put a payment's events on the topic out of order.
create index outbox_event_aggregate_pending_idx
    on outbox_event (aggregate_id, sequence)
    where published_at is null;
