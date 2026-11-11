package com.netpulse.controller;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.netpulse.dto.MonitorStatsResponse;
import com.netpulse.exception.ApiExceptionHandler;
import com.netpulse.model.Incident;
import com.netpulse.model.MonitoringCheck;
import com.netpulse.repository.IncidentRepository;
import com.netpulse.repository.MonitorRepository;
import com.netpulse.repository.MonitoringCheckRepository;
import com.netpulse.sqs.MonitoringJobPublisher;

@WebMvcTest(controllers = MonitorController.class)
@Import(ApiExceptionHandler.class)
class MonitorAnalyticsControllerTest {

    private static final UUID MONITOR_ID = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID MISSING_ID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MonitorRepository monitorRepository;
    @MockitoBean
    private MonitoringCheckRepository monitoringCheckRepository;
    @MockitoBean
    private IncidentRepository incidentRepository;
    @MockitoBean
    private MonitoringJobPublisher monitoringJobPublisher;

    @Test
    void listChecks_returns404WhenMonitorMissing() throws Exception {
        when(monitorRepository.existsById(MISSING_ID)).thenReturn(false);

        mockMvc.perform(get("/api/monitors/{id}/checks", MISSING_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Monitor not found"));

        verify(monitoringCheckRepository, never()).findByMonitorId(any(), any(Integer.class));
    }

    @Test
    void listChecks_returnsNewestFirstWithDefaultLimit() throws Exception {
        when(monitorRepository.existsById(MONITOR_ID)).thenReturn(true);
        when(monitoringCheckRepository.findByMonitorId(MONITOR_ID, 50)).thenReturn(List.of(sampleCheck()));

        mockMvc.perform(get("/api/monitors/{id}/checks", MONITOR_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].httpStatus").value(200))
                .andExpect(jsonPath("$[0].responseTimeMs").value(12.5))
                .andExpect(jsonPath("$[0].success").value(true));

        verify(monitoringCheckRepository).findByMonitorId(MONITOR_ID, 50);
    }

    @Test
    void listChecks_honorsConfiguredLimit() throws Exception {
        when(monitorRepository.existsById(MONITOR_ID)).thenReturn(true);
        when(monitoringCheckRepository.findByMonitorId(MONITOR_ID, 10)).thenReturn(List.of());

        mockMvc.perform(get("/api/monitors/{id}/checks", MONITOR_ID).param("limit", "10"))
                .andExpect(status().isOk());

        verify(monitoringCheckRepository).findByMonitorId(eq(MONITOR_ID), eq(10));
    }

    @Test
    void listChecks_rejectsLimitAboveMaximum() throws Exception {
        mockMvc.perform(get("/api/monitors/{id}/checks", MONITOR_ID).param("limit", "101"))
                .andExpect(status().isBadRequest());

        verify(monitorRepository, never()).existsById(any());
        verify(monitoringCheckRepository, never()).findByMonitorId(any(), any(Integer.class));
    }

    @Test
    void listChecks_rejectsLimitBelowMinimum() throws Exception {
        mockMvc.perform(get("/api/monitors/{id}/checks", MONITOR_ID).param("limit", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listChecks_rejectsInvalidUuid() throws Exception {
        mockMvc.perform(get("/api/monitors/{id}/checks", "not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid monitor id — expected a UUID"));
    }

    @Test
    void getStats_returnsEmptyDefaultsWhenNoChecks() throws Exception {
        when(monitorRepository.existsById(MONITOR_ID)).thenReturn(true);
        when(monitoringCheckRepository.aggregateStats(MONITOR_ID)).thenReturn(
                new MonitorStatsResponse(MONITOR_ID, 0, 0, 0, null, null, null, null, null));

        mockMvc.perform(get("/api/monitors/{id}/stats", MONITOR_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.monitorId").value(MONITOR_ID.toString()))
                .andExpect(jsonPath("$.totalChecks").value(0))
                .andExpect(jsonPath("$.successfulChecks").value(0))
                .andExpect(jsonPath("$.failedChecks").value(0))
                .andExpect(jsonPath("$.uptimePercentage").value(nullValue()))
                .andExpect(jsonPath("$.averageResponseTimeMs").value(nullValue()))
                .andExpect(jsonPath("$.lastCheckedAt").value(nullValue()))
                .andExpect(jsonPath("$.lastCheckSuccess").value(nullValue()))
                .andExpect(jsonPath("$.lastHttpStatus").value(nullValue()));
    }

    @Test
    void getStats_returnsAggregates() throws Exception {
        when(monitorRepository.existsById(MONITOR_ID)).thenReturn(true);
        OffsetDateTime last = OffsetDateTime.of(2026, 11, 10, 22, 0, 0, 0, ZoneOffset.UTC);
        when(monitoringCheckRepository.aggregateStats(MONITOR_ID)).thenReturn(
                new MonitorStatsResponse(MONITOR_ID, 10, 9, 1, 90.0, 42.5, last, true, 200));

        mockMvc.perform(get("/api/monitors/{id}/stats", MONITOR_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalChecks").value(10))
                .andExpect(jsonPath("$.successfulChecks").value(9))
                .andExpect(jsonPath("$.failedChecks").value(1))
                .andExpect(jsonPath("$.uptimePercentage").value(90.0))
                .andExpect(jsonPath("$.averageResponseTimeMs").value(42.5))
                .andExpect(jsonPath("$.lastCheckSuccess").value(true))
                .andExpect(jsonPath("$.lastHttpStatus").value(200));
    }

    @Test
    void getStats_returns404WhenMonitorMissing() throws Exception {
        when(monitorRepository.existsById(MISSING_ID)).thenReturn(false);

        mockMvc.perform(get("/api/monitors/{id}/stats", MISSING_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Monitor not found"));
    }

    @Test
    void listIncidents_returnsEmptyListWhenNone() throws Exception {
        when(monitorRepository.existsById(MONITOR_ID)).thenReturn(true);
        when(incidentRepository.findByMonitorId(MONITOR_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/monitors/{id}/incidents", MONITOR_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    void listIncidents_includesDerivedStatus() throws Exception {
        when(monitorRepository.existsById(MONITOR_ID)).thenReturn(true);

        Incident open = new Incident();
        open.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        open.setMonitorId(MONITOR_ID);
        open.setStartedAt(OffsetDateTime.of(2026, 11, 10, 20, 0, 0, 0, ZoneOffset.UTC));
        open.setResolvedAt(null);
        open.setReason("5xx");

        Incident resolved = new Incident();
        resolved.setId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        resolved.setMonitorId(MONITOR_ID);
        resolved.setStartedAt(OffsetDateTime.of(2026, 11, 10, 18, 0, 0, 0, ZoneOffset.UTC));
        resolved.setResolvedAt(OffsetDateTime.of(2026, 11, 10, 18, 5, 0, 0, ZoneOffset.UTC));
        resolved.setReason("recovered");

        when(incidentRepository.findByMonitorId(MONITOR_ID)).thenReturn(List.of(open, resolved));

        mockMvc.perform(get("/api/monitors/{id}/incidents", MONITOR_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("open"))
                .andExpect(jsonPath("$[0].reason").value("5xx"))
                .andExpect(jsonPath("$[1].status").value("resolved"))
                .andExpect(jsonPath("$[1].resolvedAt").exists());
    }

    @Test
    void listIncidents_returns404WhenMonitorMissing() throws Exception {
        when(monitorRepository.existsById(MISSING_ID)).thenReturn(false);

        mockMvc.perform(get("/api/monitors/{id}/incidents", MISSING_ID))
                .andExpect(status().isNotFound());
    }

    private static MonitoringCheck sampleCheck() {
        MonitoringCheck check = new MonitoringCheck();
        check.setId(UUID.randomUUID());
        check.setMonitorId(MONITOR_ID);
        check.setHttpStatus(200);
        check.setResponseTimeMs(12.5);
        check.setSuccess(true);
        check.setCheckedAt(OffsetDateTime.of(2026, 11, 10, 22, 0, 0, 0, ZoneOffset.UTC));
        return check;
    }
}
