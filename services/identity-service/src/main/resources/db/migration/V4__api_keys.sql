-- The credential a merchant's own server integrates with.
--
-- A person signed in to the console carries a token issued to them. A server carries one of
-- these instead: it is long lived, it belongs to the merchant rather than to anybody, and it
-- proves itself by signing each request rather than by presenting a bearer secret.
--
-- The secret is stored encrypted rather than hashed, which is not the usual advice and is
-- deliberate: HMAC is symmetric, so whoever verifies a signature must hold the same secret
-- that made it. A hash could not verify anything. The key that decrypts this column lives in
-- configuration, never in this database, so a copy of these rows on its own signs nothing.
-- ADR 0010 sets out the alternative that would allow hash only storage and why it was not
-- taken.

create table api_key (
    id uuid primary key,
    merchant_id uuid not null references merchant (id) on delete cascade,
    -- What the caller sends in X-Mizan-Key. Public, and not a secret.
    key_id text not null,
    -- What a person calls it in the console: "billing worker", "nightly reconciliation".
    name text not null,
    secret_encrypted text not null,
    -- One role per key. A server integration does one job, and a key that could do
    -- everything is a key whose theft costs everything.
    role text not null,
    created_at timestamptz not null,
    -- Answers "is this key still in use" before somebody revokes it and finds out the hard way.
    last_used_at timestamptz,
    revoked_at timestamptz,
    -- The key this one replaced, so a rotation is a chain rather than an unrelated pair.
    rotated_from uuid references api_key (id),
    constraint api_key_key_id_unique unique (key_id),
    constraint api_key_role_known check (role in ('OWNER', 'ADMIN', 'ANALYST', 'VIEWER'))
);

create index api_key_merchant_idx on api_key (merchant_id);
