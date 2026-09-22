-- An authorization writes down that it began, the way a capture already does (V17).
--
-- Until now the whole of authorize ran inside one transaction: the payment was read, risk was asked, the
-- acquirer was asked, and the answer was written, all while holding a database connection. That is what
-- the connection was protecting — two authorizations of one payment could not interleave, because the
-- second waited on the first.
--
-- It was also the platform's entire latency tail. At thirty payments a second, 47 request threads were
-- queued for a pool of ten connections while those threads sat waiting on somebody else's network
-- (MIZ-104, docs/performance). A connection is for talking to Postgres, not for holding a place in a
-- queue.
--
-- So the transaction is broken into short ones with the outbound calls between them, and this column is
-- what the short ones hand to each other: written before anybody outside is asked, cleared when the
-- answer is recorded. A second authorization arriving in between finds the mark and is refused rather
-- than contacting the acquirer a second time. Exactly the capture's arrangement, for the same reason.

alter table payment add column authorization_started_at timestamptz;

-- What the resolver looks for, and what an operator asks when something is stuck: authorizations that
-- began and never finished. Partial, because the answer is almost always none of them.
create index payment_authorization_began_idx
    on payment (authorization_started_at)
    where authorization_started_at is not null;
