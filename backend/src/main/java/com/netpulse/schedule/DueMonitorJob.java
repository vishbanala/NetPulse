package com.netpulse.schedule;

import java.time.Instant;
import java.util.UUID;

/**
 * A monitor interval that has been claimed for enqueue (or is pending retry).
 */
public final class DueMonitorJob {

    private final UUID monitorId;
    private final UUID jobId;
    private final String url;
    private final Instant scheduledAt;
    private final int intervalSeconds;

    public DueMonitorJob(UUID monitorId, UUID jobId, String url, Instant scheduledAt, int intervalSeconds) {
        this.monitorId = monitorId;
        this.jobId = jobId;
        this.url = url;
        this.scheduledAt = scheduledAt;
        this.intervalSeconds = intervalSeconds;
    }

    public UUID getMonitorId() {
        return monitorId;
    }

    public UUID getJobId() {
        return jobId;
    }

    public String getUrl() {
        return url;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public int getIntervalSeconds() {
        return intervalSeconds;
    }
}
