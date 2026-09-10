-- What risk thought of a payment, kept on the payment.
--
-- Kept here rather than asked for again, because the question "why was this held" is asked long
-- after the scorer's view of the world has moved on. A scorer is a function of what was known at
-- the time, and what was known at the time is exactly what nobody can reconstruct later.

alter table payment drop constraint payment_status_known;
alter table payment add constraint payment_status_known
    check (status in ('CREATED', 'AUTHORIZATION_UNKNOWN', 'HELD_FOR_REVIEW', 'AUTHORIZED',
                      'DECLINED', 'CAPTURED', 'VOIDED'));

alter table payment_transition drop constraint payment_transition_status_known;
alter table payment_transition add constraint payment_transition_status_known
    check (to_status in ('CREATED', 'AUTHORIZATION_UNKNOWN', 'HELD_FOR_REVIEW', 'AUTHORIZED',
                         'DECLINED', 'CAPTURED', 'VOIDED'));

-- APPROVE, REVIEW, BLOCK, or UNAVAILABLE when risk could not be asked.
--
-- UNAVAILABLE is a real verdict rather than a null, because "nobody scored this" is a fact a
-- merchant and an analyst both need, and a null says only that somebody forgot to write it down.
alter table payment add column risk_verdict text;
alter table payment add column risk_score integer;
-- What fired, as the scorer said it. Stored rather than the rule names, so a change to a rule's
-- wording later does not rewrite the reason a payment was held last year.
alter table payment add column risk_reasons text;
alter table payment add column risk_checked_at timestamptz;

alter table payment add constraint payment_risk_verdict_known
    check (risk_verdict is null
           or risk_verdict in ('APPROVE', 'REVIEW', 'BLOCK', 'UNAVAILABLE'));

-- A payment that was held has to say when, so that expiry has something to measure from.
alter table payment add column held_at timestamptz;
alter table payment add constraint payment_held_is_timed
    check ((status = 'HELD_FOR_REVIEW') = (held_at is not null));

-- What the expiry sweep asks for. Partial, because a platform where this answer is large has a
-- staffing problem rather than a query problem.
create index payment_held_idx
    on payment (held_at)
    where status = 'HELD_FOR_REVIEW';

-- And what an analyst asks for, in MIZ-59.
create index payment_risk_verdict_idx
    on payment (merchant_id, risk_checked_at desc)
    where risk_verdict in ('REVIEW', 'UNAVAILABLE');
