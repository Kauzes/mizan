-- Events this service could not handle, kept where a person can find them.
--
-- A dead letter topic on its own satisfies the machine and not the person. It stops a poison
-- message blocking its partition, and it leaves the evidence somewhere nobody looks until they
-- already know to. What an operator needs to ask is "what is broken, why, and how much of it",
-- and that is a query. So the dead letter topic is consumed into this table: the topic is what
-- unblocks delivery at the moment of failure, and this is what makes the failure legible
-- afterwards and redeliverable once the bug is fixed.

create table dead_letter (
    id uuid primary key,
    event_id uuid not null,
    type text not null,
    -- Which consumer could not handle it. Two handlers may fail on the same event for
    -- entirely different reasons, and fixing one does not fix the other.
    handler text not null,
    -- Where it came from, so an operator can go and look at the original if they need to.
    topic text not null,
    partition integer,
    "offset" bigint,
    -- The key the message was published under, kept because redelivering under a different
    -- one would put the event in a different partition and lose the ordering that was the
    -- point of keying it in the first place.
    message_key text,
    -- Why. The exception and its message rather than a stack trace: the useful sentence is
    -- almost always the first one, and a text column full of frames is a text column nobody
    -- reads.
    reason text not null,
    -- The request that caused the event, several services ago. This is usually the fastest
    -- way to find out what a customer was actually doing when it went wrong.
    correlation_id text,
    -- The original message, byte for byte. Not a summary: redelivering a reconstruction would
    -- redeliver this platform's idea of the event rather than the event.
    payload text not null,
    -- How many times this has been set aside. An event redelivered before the bug was really
    -- fixed comes back here, and one row with a count says more than five rows saying the
    -- same thing.
    attempts integer not null,
    first_failed_at timestamptz not null,
    last_failed_at timestamptz not null,
    -- Null until somebody sends it back for another try. Not deleted then, because what went
    -- wrong and how often is the useful part, and a table that forgets its failures the moment
    -- somebody retries them cannot answer whether the retry helped.
    redelivered_at timestamptz,
    -- One row per event per handler, which is what makes the count above meaningful.
    constraint dead_letter_once_per_handler unique (event_id, handler)
);

-- What the endpoint asks for: what is still outstanding, worst first.
create index dead_letter_outstanding_idx
    on dead_letter (attempts desc, first_failed_at)
    where redelivered_at is null;
