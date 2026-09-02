-- The chart of accounts: where a number lives.
--
-- An account carries one currency for its whole life. Two currencies in one account means
-- every read has to know which part is which, and every sum has to be told what it is summing.
-- A merchant trading in two currencies has two accounts.
--
-- merchant_id has no foreign key, on purpose. Merchants live in the identity database and no
-- service reads another's tables, so this is the id identity issued, checked at the edge
-- before a request gets here rather than by a constraint that would need a join across a
-- boundary the platform does not cross.

create table account (
    id uuid primary key,
    -- Null for the platform's own accounts. Every merchant's money passes through those, so
    -- they belong to nobody in particular and are not reachable under a merchant's path.
    merchant_id uuid,
    code text not null,
    name text not null,
    type text not null,
    -- text rather than char(3): Postgres pads a char, and the shape is already
    -- guaranteed by the constraint below.
    currency text not null,
    created_at timestamptz not null,
    constraint account_type_known
        check (type in ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    constraint account_currency_shape check (currency ~ '^[A-Z]{3}$')
);

-- A code identifies an account within the books it belongs to. Two merchants may both call
-- an account "settlement" without colliding; the platform's own codes are unique among
-- themselves.
create unique index account_code_per_merchant
    on account (merchant_id, code)
    where merchant_id is not null;

create unique index account_code_platform
    on account (code)
    where merchant_id is null;

create index account_merchant_idx on account (merchant_id);

-- The platform's own accounts, seeded rather than created by an API, because a chart of
-- accounts is a deliberate thing and not something that should appear because a request
-- arrived. Adding a currency the platform settles in means another migration, which is the
-- point: somebody decides.
insert into account (id, merchant_id, code, name, type, currency, created_at) values
    ('00000000-0000-4000-8000-000000000001', null, 'platform.clearing.try',
     'Funds held at the acquirer, TRY', 'ASSET', 'TRY', now()),
    ('00000000-0000-4000-8000-000000000002', null, 'platform.fees.try',
     'What Mizan charges, TRY', 'REVENUE', 'TRY', now());
