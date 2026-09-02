-- What this service has already dealt with.
--
-- Events are delivered at least once, deliberately and unavoidably: the relay that publishes
-- them marks the row after sending, so anything that dies in between sends again. Marking
-- first would lose events instead, which is worse. This is the other side of that admission,
-- and without it a redelivered payment.captured is a second receipt sent to a customer.
--
-- Written in the same transaction as the handling, which is the opposite of how the API's
-- idempotency records work: those are committed before the handler runs so a concurrent
-- request can see the claim and wait for its answer. Here nobody is waiting for an answer,
-- and a handler that did its work but failed to record it would be exactly the problem the
-- outbox exists to prevent. The two mechanisms resemble each other and cannot share an
-- implementation.

create table handled_event (
    -- Which consumer. Two handlers in one service may both care about the same event and
    -- each has to see it, so "already handled" is a question about a handler rather than
    -- about a service.
    handler text not null,
    -- The id the producer generated, once, for an event that is then immutable. There is no
    -- request fingerprint here for that reason: a caller can honestly reuse an idempotency
    -- key by mistake, and a producer cannot reuse an event id.
    event_id uuid not null,
    -- Kept for reading, not for deciding. Answering "what has this handler seen" without
    -- joining to something that may not exist in this service's database.
    type text not null,
    handled_at timestamptz not null,
    primary key (handler, event_id)
);

-- For expiring old rows once somebody decides how long is long enough. The table grows with
-- every event this service sees, and nothing here trims it yet: an entry can only be removed
-- once the topic can no longer redeliver the event it names, which is a retention question
-- rather than a code one.
create index handled_event_handled_at_idx on handled_event (handled_at);
