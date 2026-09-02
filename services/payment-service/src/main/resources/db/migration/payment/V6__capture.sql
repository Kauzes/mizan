-- Capture: where a payment stops being a promise and becomes a movement.
--
-- The payment keeps the id of the entry that recorded it. Not a foreign key, because the
-- entry lives in the ledger's database and no service reads another's tables; what it is for
-- is answering "where in the books is this payment", which is the first question anybody asks
-- when the two disagree.
--
-- Null until the money is actually taken. An authorization posts nothing, so a payment that
-- has only been authorized has nothing to point at, and a void never posts anything at all.
alter table payment add column ledger_entry_id uuid;

-- One entry per payment. Two would mean the money was recorded twice, which is the failure
-- this whole story is built to prevent, so the database refuses it rather than trusting that
-- the code upstream never does it.
create unique index payment_ledger_entry_once
    on payment (ledger_entry_id)
    where ledger_entry_id is not null;

-- A captured payment has to have one. The state and the books agree here, at the level that
-- cannot be bypassed by a bug, rather than only in the service that writes both.
alter table payment add constraint payment_captured_is_recorded
    check (status <> 'CAPTURED' or ledger_entry_id is not null);
