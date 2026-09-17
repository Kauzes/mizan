-- A dead letter that can never be handled, closed with a reason.
--
-- Until now the only thing that could be done with one was to redeliver it, which assumes the cause can
-- be fixed. Some cannot: a payload poisoned by a test, an event naming something that never existed, a
-- handler that has since been deleted. Those stayed outstanding forever, and the alert that watches the
-- count kept firing — and an alert that cannot be cleared is an alert people learn to ignore, which costs
-- more than the gap it was covering. MIZ-103.
--
-- Closing is not deleting. The row, its reason and its payload stay exactly where they were, because
-- this is the only record that a merchant was never told something. What changes is that somebody has
-- put their name to a decision that nothing more will be done about it.

alter table dead_letter
    add column closed_at timestamptz,
    add column closed_by text,
    add column closed_why text;

-- A closing with nobody's name on it, or with no reason, is not a decision anybody can audit later. The
-- endpoint refuses one too; this is the rule rather than the manners.
alter table dead_letter
    add constraint dead_letter_closed_is_accounted_for check (
        (closed_at is null and closed_by is null and closed_why is null)
        or (closed_at is not null
            and closed_by is not null and length(trim(closed_by)) > 0
            and closed_why is not null and length(trim(closed_why)) > 0));

-- Outstanding now means neither redelivered nor closed, and the index that answers that question has to
-- mean the same thing or the endpoint and the metric will quietly disagree with it.
drop index if exists dead_letter_outstanding_idx;

create index dead_letter_outstanding_idx
    on dead_letter (attempts desc, first_failed_at)
    where redelivered_at is null and closed_at is null;
