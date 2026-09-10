-- What a person decided about a payment this platform held.
--
-- A ruling is kept beside the payment rather than moving it, because releasing a held payment
-- cannot authorize it: this service keeps only four digits of the card, so the merchant has to
-- present it again. The payment therefore stays held until somebody authorizes it, and this
-- column is what says it is no longer waiting for a person.
alter table payment add column review_ruling text;
alter table payment add column review_ruled_by text;
alter table payment add column review_ruled_at timestamptz;

alter table payment add constraint payment_review_ruling_known
    check (review_ruling is null or review_ruling in ('RELEASED', 'REFUSED'));

-- A ruling only means anything on a payment that was held.
alter table payment add constraint payment_ruling_needs_a_hold
    check (review_ruling is null or held_at is not null or status <> 'CREATED');

-- What the queue asks for: held, and nobody has ruled yet. Replaces the index the expiry
-- sweep used, because a released payment is not waiting for a person any more and should not
-- appear in either answer.
drop index if exists payment_held_idx;
create index payment_held_idx
    on payment (held_at)
    where status = 'HELD_FOR_REVIEW' and review_ruling is null;
