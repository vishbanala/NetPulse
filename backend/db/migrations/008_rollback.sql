-- Restore previous interval range (10–86400). Does not shrink existing intervals.

ALTER TABLE monitors DROP CONSTRAINT IF EXISTS monitors_interval_range;

ALTER TABLE monitors
    ADD CONSTRAINT monitors_interval_range
    CHECK (interval_seconds BETWEEN 10 AND 86400);
