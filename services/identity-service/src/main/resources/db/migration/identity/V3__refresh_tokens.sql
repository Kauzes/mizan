-- Refresh tokens, and the families they belong to.
--
-- An access token is not stored anywhere: it is verified by its signature and its expiry,
-- which is the whole point of signing it. A refresh token is different. It lives for days,
-- it has to be revocable, and rotation means the platform must be able to tell a token it
-- has already spent from one it has not.
--
-- Every refresh token issued from one sign in shares a family id. Presenting a token that
-- was already spent means either the holder replayed it or somebody else stole it, and
-- neither case can be told apart from here, so the whole family goes.

create table refresh_token (
    id uuid primary key,
    family_id uuid not null,
    user_id uuid not null references app_user (id) on delete cascade,
    -- A sha-256 of the token, not the token. bcrypt exists to make guessing a low entropy
    -- secret expensive; this one is 256 random bits, where guessing is not the threat and a
    -- fast digest is enough to keep a stolen database from being a set of usable tokens.
    token_hash text not null,
    issued_at timestamptz not null,
    expires_at timestamptz not null,
    -- When it was exchanged for a new pair. A second presentation after this is a replay.
    spent_at timestamptz,
    -- Set on every token of a family when one of them is replayed.
    revoked_at timestamptz,
    constraint refresh_token_hash_unique unique (token_hash)
);

create index refresh_token_family_idx on refresh_token (family_id);
create index refresh_token_user_idx on refresh_token (user_id);
