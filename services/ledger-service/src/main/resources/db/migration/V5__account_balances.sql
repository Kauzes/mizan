-- What an account holds, kept on the account.
--
-- A balance summed from every posting an account ever had is correct and gets slower every
-- day. Kept here it is one row read, and one lost update away from being a lie, which is what
-- the version column is for: a writer that reads a balance and writes it back is refused if
-- somebody else moved it in between.
--
-- The stored number is the signed sum of the account's postings, debit positive. It is not
-- flipped to read naturally for a liability, because a ledger should return one number with
-- one meaning; how to present it is the console's business, and the account's type says which
-- way to read it.

alter table account add column balance bigint not null default 0;
alter table account add column version bigint not null default 0;

-- Accounts that already have postings against them start from what those postings say rather
-- than from zero.
update account a
   set balance = coalesce((select sum(p.amount) from posting p where p.account_id = a.id), 0);
