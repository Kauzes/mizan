-- What the acquirer said, kept on the payment.
--
-- No card number anywhere. The last four digits are what a person needs to recognise a
-- payment in a list, and are all that is worth the risk of holding.

alter table payment add column acquirer_reference text;
alter table payment add column card_last_four text;
alter table payment add column decline_reason text;

-- One authorization per payment, so the acquirer can be asked with the payment's own id and
-- will answer with the decision it already made rather than making a second one.
create unique index payment_acquirer_reference_unique
    on payment (acquirer_reference)
    where acquirer_reference is not null;
