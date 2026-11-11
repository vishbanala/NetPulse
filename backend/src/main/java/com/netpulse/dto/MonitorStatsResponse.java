package com.netpulse.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Aggregated check analytics for a single monitor.
 */
public class MonitorStatsResponse {

    private final UUID monitorId;
    private final long totalChecks;
    private final long successfulChecks;
    private final long failedChecks;
    /** Null when there are no recorded checks. */
    private final Double uptimePercentage;
    /** Null when there are no response-time samples. */
    private final Double averageResponseTimeMs;
    private final OffsetDateTime lastCheckedAt;
    private final Boolean lastCheckSuccess;
    private final Integer lastHttpStatus;

    public MonitorStatsResponse(UUID monitorId,
                                long totalChecks,
                                long successfulChecks,
                                long failedChecks,
                                Double uptimePercentage,
                                Double averageResponseTimeMs,
                                OffsetDateTime lastCheckedAt,
                                Boolean lastCheckSuccess,
                                Integer lastHttpStatus) {
        this.monitorId = monitorId;
        this.totalChecks = totalChecks;
        this.successfulChecks = successfulChecks;
        this.failedChecks = failedChecks;
        this.uptimePercentage = uptimePercentage;
        this.averageResponseTimeMs = averageResponseTimeMs;
        this.lastCheckedAt = lastCheckedAt;
        this.lastCheckSuccess = lastCheckSuccess;
        this.lastHttpStatus = lastHttpStatus;
    }

    public UUID getMonitorId() {
        return monitorId;
    }

    public long getTotalChecks() {
        return totalChecks;
    }

    public long getSuccessfulChecks() {
        return successfulChecks;
    }

    public long getFailedChecks() {
        return failedChecks;
    }

    public Double getUptimePercentage() {
        return uptimePercentage;
    }

    public Double getAverageResponseTimeMs() {
        return averageResponseTimeMs;
    }

    public OffsetDateTime getLastCheckedAt() {
        return lastCheckedAt;
    }

    public Boolean getLastCheckSuccess() {
        return lastCheckSuccess;
    }

    public Integer getLastHttpStatus() {
        return lastHttpStatus;
    }
}
