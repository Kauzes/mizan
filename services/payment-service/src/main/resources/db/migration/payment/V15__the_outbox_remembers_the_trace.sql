-- The one hop nobody can reconstruct by hand.
--
-- A consumer three services away can be linked back to the request that caused it only if
-- something carries the trace across the topic, and with an outbox it is worse than that: by
-- the time the relay publishes, the request finished minutes ago, on another thread, possibly
-- on another machine. The row is the only thing that was there for both.
--
-- Nullable, and it stays nullable. Events written before this column existed have no trace and
-- never will; so does anything recorded by work that was not part of a request. A trace id
-- invented at publishing time would be worse than none — it would produce a trace of one span
-- that looks like an answer and is not.
alter table outbox_event add column trace_parent text;

-- W3C trace context, version 00: "00-<32 hex trace id>-<16 hex span id>-<2 hex flags>". Checked
-- rather than trusted, because a malformed value here is silently ignored by every consumer and
-- the loss shows up as a trace that mysteriously stops at the topic.
alter table outbox_event add constraint outbox_event_trace_parent_is_w3c
    check (trace_parent is null or trace_parent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$');
