-- When a capture was started, and not yet finished.
--
-- A capture asks the acquirer first and the ledger second. If anything stops it between the
-- two, a ledger that is down or a service that is killed, the acquirer holds the money and the
-- books do not know. The payment still says AUTHORIZED, which is honest, and nothing about it
-- says a capture was ever attempted, which is the problem: no sweep could find it, and unless
-- the merchant sent the capture again the money stayed taken and unrecorded. MIZ-90.
--
-- So the start is written down, and committed, before the acquirer is asked. A column rather
-- than a status, because the payment is still exactly as authorized as it was; what is new is
-- that something began, which is a different fact, as needs_attention_since is.
--
-- Cleared when the capture finishes, or when the acquirer is found never to have taken it.
alter table payment add column capture_started_at timestamptz;

-- What the capture sweep asks every fifteen seconds. Partial, because at any moment almost no
-- payment has a capture in flight, and the index should be the size of that, not of the table.
create index payment_capture_in_flight
    on payment (capture_started_at)
    where capture_started_at is not null and status = 'AUTHORIZED';
