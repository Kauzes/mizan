-- Where a merchant's line sits between approve, review and block.
--
-- Per merchant, because one line for everybody is one line that is wrong for nearly everybody.
-- A marketplace taking a thousand small payments an hour and a car dealer taking three a week
-- have different ideas of alarming, and a platform that holds them to the same number is
-- either blocking the marketplace's ordinary traffic or letting the dealer's fraud through.
--
-- A row here is a decision somebody made. A merchant with no row uses the platform's defaults,
-- which is the right behaviour on day one and is not a row pretending to be a decision.

create table merchant_thresholds (
    merchant_id uuid primary key,
    -- Above this, hold for a person. Above the second, refuse outright.
    review_above integer not null,
    block_above integer not null,
    -- Who last changed it and when, because a threshold is a risk appetite and somebody owns
    -- it. MIZ-59's feedback loop writes here too, and being able to tell a person's decision
    -- from the loop's is the whole reason this column exists.
    set_by text not null,
    updated_at timestamptz not null,
    constraint merchant_thresholds_ordered check (block_above > review_above),
    constraint merchant_thresholds_sane check (review_above >= 0 and block_above <= 1000)
);
