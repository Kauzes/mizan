-- Finding what was sent about one payment.
--
-- A merchant debugging their own integration asks "did you tell me about this payment", and
-- until now the only way to answer was to list an endpoint's deliveries and read. The column
-- was already there; what was missing was a way to reach it that does not scan.
create index webhook_delivery_payment_idx
    on webhook_delivery (merchant_id, payment_id, created_at desc);
