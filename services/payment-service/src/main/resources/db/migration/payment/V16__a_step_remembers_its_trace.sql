-- Which trace produced this step.
--
-- On the step rather than on the payment, because a payment has several and they are
-- interesting separately: the authorization that was slow, the capture that failed, the refund
-- three days later. One id on the payment would be whichever of those happened last, which is
-- rarely the one anybody is asking about.
--
-- Nullable and it stays that way: a step taken by a scheduler rather than by a request has no
-- trace to belong to, and steps written before this column existed never will.
alter table payment_transition add column trace_id text;

-- 32 lowercase hex characters, which is what a W3C trace id is. Checked rather than trusted,
-- because the failure of a wrong value here is a console offering somebody a link to a trace
-- that does not exist, in the middle of the call where they needed it.
alter table payment_transition add constraint payment_transition_trace_id_is_a_trace_id
    check (trace_id is null or trace_id ~ '^[0-9a-f]{32}$');
