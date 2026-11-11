package com.netpulse.schedule;

import java.time.Instant;

/**
 * Pure timing helpers that mirror the SQL used when claiming due monitors.
 * Kept separate so regressions can assert {@code next_run_at} math without a database.
 */
public final class MonitorScheduleTiming {

    private MonitorScheduleTiming() {
    }

    /**
     * Advances the schedule clock by the monitor's configured interval.
     * Equivalent to PostgreSQL:
     * {@code CAST(claimedAt AS TIMESTAMPTZ) + (intervalSeconds * INTERVAL '1 second')}.
     */
    public static Instant nextRunAt(Instant claimedAt, int intervalSeconds) {
        if (claimedAt == null) {
            throw new IllegalArgumentException("claimedAt is required");
        }
        if (intervalSeconds < 0) {
            throw new IllegalArgumentException("intervalSeconds must be non-negative");
        }
        return claimedAt.plusSeconds(intervalSeconds);
    }
}
