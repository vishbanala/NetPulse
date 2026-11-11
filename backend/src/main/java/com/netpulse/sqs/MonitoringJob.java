package com.netpulse.sqs;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * SQS message payload for a single monitoring check job.
 */
public class MonitoringJob {

    @NotNull
    private UUID jobId;

    @NotNull
    private UUID monitorId;

    @NotBlank
    @Size(max = 2048)
    private String url;

    @NotNull
    private Instant scheduledAt;

    public MonitoringJob() {
    }

    public MonitoringJob(UUID jobId, UUID monitorId, String url, Instant scheduledAt) {
        this.jobId = jobId;
        this.monitorId = monitorId;
        this.url = url;
        this.scheduledAt = scheduledAt;
    }

    public UUID getJobId() {
        return jobId;
    }

    public void setJobId(UUID jobId) {
        this.jobId = jobId;
    }

    public UUID getMonitorId() {
        return monitorId;
    }

    public void setMonitorId(UUID monitorId) {
        this.monitorId = monitorId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public Instant getScheduledAt() {
        return scheduledAt;
    }

    public void setScheduledAt(Instant scheduledAt) {
        this.scheduledAt = scheduledAt;
    }
}
