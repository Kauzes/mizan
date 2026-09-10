-- What analysts decided about payments this platform held, and what it learns from that.
--
-- This is the only place on the platform where what somebody did changes what the platform
-- will decide next time, which makes it the one mechanism most worth being careful about: a
-- loop nobody bounded is a loop an attacker teaches.

create table ruling (
    id uuid primary key,
    merchant_id uuid not null,
    payment_id uuid not null,
    -- RELEASED or REFUSED. What the analyst decided, in their words for the platform.
    ruling text not null,
    -- What the scorer had said, kept beside the ruling rather than looked up later. A scorer
    -- is a function of what was known at the time, and what was known at the time is exactly
    -- what nobody can reconstruct afterwards -- so a ruling that did not keep it cannot be
    -- used to judge the scorer.
    risk_score integer,
    risk_reasons text,
    -- Who and why. Not rewritable: a ruling is evidence of a decision somebody made, and
    -- evidence the next decision can overwrite is not evidence. The same reasoning as the
    -- journal and as MIZ-53's operator decisions.
    ruled_by text not null,
    why text not null,
    at timestamptz not null,
    constraint ruling_known check (ruling in ('RELEASED', 'REFUSED')),
    -- One ruling per payment. A payment ruled on twice is either a bug or somebody changing
    -- their mind, and both should be visible as such rather than as two equal opinions.
    constraint ruling_once_per_payment unique (payment_id)
);

create index ruling_merchant_idx on ruling (merchant_id, at desc);

create or replace function ruling_is_append_only() returns trigger as $$
begin
    raise exception 'a ruling records what somebody decided and cannot be % ',
        case when tg_op = 'DELETE' then 'deleted' else 'changed' end;
end;
$$ language plpgsql;

create trigger ruling_no_rewriting
    before update or delete on ruling
    for each row execute function ruling_is_append_only();

-- Where the merchant's line has been moved to by rulings, as distinct from where a person set
-- it.
--
-- Separate from merchant_thresholds on purpose. That table is somebody's stated risk appetite;
-- this is what the loop has inferred, and keeping them apart means a person can always see how
-- far the platform has drifted from what they asked for -- and set it back.
alter table merchant_thresholds add column learned_adjustment integer not null default 0;

-- How far the loop may move a merchant's line, in either direction.
--
-- A hard bound in the database rather than only in the code, because the failure being guarded
-- against is a loop moving further than anybody intended, and a bound the loop itself enforces
-- is a bound the loop can be wrong about.
alter table merchant_thresholds add constraint merchant_thresholds_adjustment_bounded
    check (learned_adjustment between -20 and 20);
