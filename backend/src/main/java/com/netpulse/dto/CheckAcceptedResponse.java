package com.netpulse.dto;

import java.util.UUID;

public class CheckAcceptedResponse {

    private final String status;
    private final UUID jobId;
    private final UUID monitorId;
    private final String sqsMessageId;
    private final String securityNotice;

    public CheckAcceptedResponse(UUID jobId, UUID monitorId, String sqsMessageId) {
        this.status = "ACCEPTED";
        this.jobId = jobId;
        this.monitorId = monitorId;
        this.sqsMessageId = sqsMessageId;
        this.securityNotice = "UNAUTHENTICATED_LOCALHOST_ONLY";
    }

    public String getStatus() {
        return status;
    }

    public UUID getJobId() {
        return jobId;
    }

    public UUID getMonitorId() {
        return monitorId;
    }

    public String getSqsMessageId() {
        return sqsMessageId;
    }

    public String getSecurityNotice() {
        return securityNotice;
    }
}
