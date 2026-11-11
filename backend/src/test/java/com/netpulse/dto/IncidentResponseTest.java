package com.netpulse.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.netpulse.model.Incident;

class IncidentResponseTest {

    @Test
    void from_marksOpenWhenResolvedAtIsNull() {
        Incident incident = new Incident();
        incident.setId(UUID.randomUUID());
        incident.setMonitorId(UUID.randomUUID());
        incident.setStartedAt(OffsetDateTime.of(2026, 11, 10, 12, 0, 0, 0, ZoneOffset.UTC));
        incident.setResolvedAt(null);
        incident.setReason("timeout");

        IncidentResponse response = IncidentResponse.from(incident);

        assertThat(response.getStatus()).isEqualTo(IncidentResponse.STATUS_OPEN);
        assertThat(response.getResolvedAt()).isNull();
        assertThat(response.getReason()).isEqualTo("timeout");
        assertThat(response.getStartedAt()).isEqualTo(incident.getStartedAt());
    }

    @Test
    void from_marksResolvedWhenResolvedAtPresent() {
        Incident incident = new Incident();
        incident.setId(UUID.randomUUID());
        incident.setMonitorId(UUID.randomUUID());
        OffsetDateTime started = OffsetDateTime.of(2026, 11, 10, 12, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime resolved = started.plusMinutes(5);
        incident.setStartedAt(started);
        incident.setResolvedAt(resolved);
        incident.setReason("recovered");

        IncidentResponse response = IncidentResponse.from(incident);

        assertThat(response.getStatus()).isEqualTo(IncidentResponse.STATUS_RESOLVED);
        assertThat(response.getResolvedAt()).isEqualTo(resolved);
    }
}
