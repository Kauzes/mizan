-- The account money belongs to, and the people who act for it.
--
-- A merchant is the tenant boundary. Every later table that holds money, a payment or a
-- decision refers back to a merchant id, and a query that forgets to filter on it is the
-- bug this schema exists to make obvious.

create table merchant (
    id uuid primary key,
    name text not null,
    created_at timestamptz not null default now()
);

-- "user" is reserved in Postgres, and quoting it forever is worse than naming it once.
create table app_user (
    id uuid primary key,
    merchant_id uuid not null references merchant (id),
    email text not null,
    password_hash text not null,
    full_name text not null,
    created_at timestamptz not null default now(),
    -- Uniqueness is the database's job. The application lowercases before it inserts, and
    -- this refuses the row that did not, so a second account cannot be opened by changing
    -- the case of an address that is already taken.
    constraint app_user_email_lowercase check (email = lower(email)),
    constraint app_user_email_unique unique (email)
);

create index app_user_merchant_idx on app_user (merchant_id);

-- What a user is allowed to do. MIZ-31 enforces these; this table is where they live, and
-- the check constraint is what keeps the set closed rather than whatever a caller sends.
create table user_role (
    user_id uuid not null references app_user (id) on delete cascade,
    role text not null,
    granted_at timestamptz not null default now(),
    primary key (user_id, role),
    constraint user_role_known check (role in ('OWNER', 'ADMIN', 'ANALYST', 'VIEWER'))
);
