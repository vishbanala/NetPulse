package com.netpulse.analytics;

/**
 * Pure helpers for monitor check aggregations (unit-tested without a database).
 */
public final class MonitorStatsCalculator {

    private MonitorStatsCalculator() {
    }

    /**
     * Uptime as a percentage of successful checks.
     * Returns {@code null} when there are no recorded checks.
     */
    public static Double uptimePercentage(long totalChecks, long successfulChecks) {
        if (totalChecks <= 0) {
            return null;
        }
        if (successfulChecks < 0 || successfulChecks > totalChecks) {
            throw new IllegalArgumentException("successfulChecks out of range");
        }
        return Math.round((successfulChecks * 10000.0) / totalChecks) / 100.0;
    }
}
