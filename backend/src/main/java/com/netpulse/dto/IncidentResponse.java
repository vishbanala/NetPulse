package com.netpulse.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.netpulse.model.Incident;

/**
 * Incident view with derived status from {@code resolved_at}.
 */
public class IncidentResponse {

    public static final String STATUS_OPEN = "open";
    public static final String STATUS_RESOLVED = "resolved";

    private final UUID id;
    private final UUID monitorId;
    private final OffsetDateTime startedAt;
    private final OffsetDateTime resolvedAt;
    private final String reason;
    private final String status;

    public IncidentResponse(UUID id,
                            UUID monitorId,
                            OffsetDateTime startedAt,
                            OffsetDateTime resolvedAt,
                            String reason,
                            String status) {
        this.id = id;
        this.monitorId = monitorId;
        this.startedAt = startedAt;
        this.resolvedAt = resolvedAt;
        this.reason = reason;
        this.status = status;
    }

    public static IncidentResponse from(Incident incident) {
        boolean open = incident.getResolvedAt() == null;
        return new IncidentResponse(
                incident.getId(),
                incident.getMonitorId(),
                incident.getStartedAt(),
                incident.getResolvedAt(),
                incident.getReason(),
                open ? STATUS_OPEN : STATUS_RESOLVED);
    }

    public UUID getId() {
        return id;
    }

    public UUID getMonitorId() {
        return monitorId;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public OffsetDateTime getResolvedAt() {
        return resolvedAt;
    }

    public String getReason() {
        return reason;
    }

    public String getStatus() {
        return status;
    }
}
