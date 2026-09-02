-- A payment whose outcome nobody knows yet.
--
-- An acquirer that stops answering has not refused. It may well have reserved the money and
-- lost the reply, so the platform records that it does not know, rather than guessing either
-- way. Guessing "declined" charges nobody and loses the merchant a sale; guessing "approved"
-- takes money the acquirer may never have reserved. Neither is a decision code should make.

alter table payment drop constraint payment_status_known;
alter table payment add constraint payment_status_known
    check (status in ('CREATED', 'AUTHORIZATION_UNKNOWN', 'AUTHORIZED', 'DECLINED',
                      'CAPTURED', 'VOIDED'));

alter table payment_transition drop constraint payment_transition_status_known;
alter table payment_transition add constraint payment_transition_status_known
    check (to_status in ('CREATED', 'AUTHORIZATION_UNKNOWN', 'AUTHORIZED', 'DECLINED',
                         'CAPTURED', 'VOIDED'));

-- Resolution runs on a schedule and can also be asked for, so two of them can reach one
-- payment at the same moment. The version is what stops the second from writing a second
-- answer over the first.
alter table payment add column version bigint not null default 0;

-- Found by the sweep. Partial, because the whole point is that few payments are ever here.
create index payment_unknown_idx
    on payment (updated_at)
    where status = 'AUTHORIZATION_UNKNOWN';
