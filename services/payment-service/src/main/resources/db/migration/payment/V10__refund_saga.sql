-- A refund happens in three steps across two other systems, and a process can die between any
-- two of them. This is what makes that survivable.
--
-- MIZ-51 wrote the refund row only once everything had worked, which meant a crash left no
-- record that anything had been attempted. That was honest for a story with no way to resume,
-- and it is the thing this story exists to fix: the row is now written before the acquirer is
-- asked, and says how far it has got.

-- The states a refund passes through. Each one is a real answer to "what is true right now",
-- and each says something different about where the money is.
--
--   REQUESTED  the amount is reserved against the payment and the acquirer has not confirmed
--              anything. The money may or may not have gone back: a call that timed out looks
--              exactly like one that never happened.
--   RETURNED   the acquirer has given the money back and the books do not yet say so. The
--              platform owes the ledger an entry.
--   SUCCEEDED  the money is back and the books say so.
--   FAILED     the acquirer refused outright. Nothing moved, and the reservation is released.
--   ABANDONED  it could not be finished, and a person has to look. The reservation is NOT
--              released, because the money may have gone back and releasing it would let the
--              merchant refund the same money twice.
alter table refund drop constraint refund_status_known;
alter table refund add constraint refund_status_known
    check (status in ('REQUESTED', 'RETURNED', 'SUCCEEDED', 'FAILED', 'ABANDONED'));

-- What each state has to be able to show. A refund that says the money went back has to name
-- where it went back through; one that says the books agree has to name the entry.
alter table refund drop constraint refund_succeeded_is_recorded;
alter table refund add constraint refund_state_is_evidenced check (
    (status in ('REQUESTED', 'FAILED', 'ABANDONED'))
    or (status = 'RETURNED' and acquirer_reference is not null)
    or (status = 'SUCCEEDED' and acquirer_reference is not null and ledger_entry_id is not null)
);

-- How many times finishing it has been tried, and when it may be tried again. A refund that
-- cannot be finished is retried with a growing delay and then given up on, because retrying
-- forever is how one broken refund becomes a service doing nothing else.
alter table refund add column attempts integer not null default 0;
alter table refund add column next_attempt_at timestamptz;
-- What went wrong last time, so a refund stuck in front of a person can be explained without
-- reading a log that has rotated.
alter table refund add column last_error text;

-- What the sweep asks for: unfinished, due, oldest first. Partial, because the answer shrinks
-- to nothing while the table grows without limit.
create index refund_unfinished_idx
    on refund (created_at)
    where status in ('REQUESTED', 'RETURNED');

-- And what an operator asks for: what needs a person.
create index refund_abandoned_idx
    on refund (updated_at)
    where status = 'ABANDONED';

-- The payment's refunded total counts everything that is not known to have failed. A
-- REQUESTED refund holds its reservation because the money may already be gone; a FAILED one
-- releases it because the acquirer said nothing happened. This is a comment rather than a
-- constraint because it is a rule about two tables, and the service is where it lives.
