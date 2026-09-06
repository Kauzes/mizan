-- Closing a stuck payment has to mean stop, not try again.
--
-- MIZ-53 gave an operator two verbs and implemented them as one. Both cleared the attention
-- flag and reset the attempt count, so a payment somebody had looked at and closed went
-- straight back into the sweep, was asked about five more times, and became stuck again. The
-- smoke check found it by refusing to pass on a stack where somebody had closed one.
--
-- The fix is the shape the refund already uses: abandoned stays true, and a separate column
-- records that a person has dealt with it. Being stuck is a fact about the payment; having
-- been looked at is a fact about the operator, and they are not the same fact.
alter table payment add column attention_handled_at timestamptz;

drop index if exists payment_needs_attention_idx;

-- What an operator asks for: what needs a person and nobody has dealt with.
create index payment_needs_attention_idx
    on payment (needs_attention_since)
    where needs_attention_since is not null and attention_handled_at is null;
