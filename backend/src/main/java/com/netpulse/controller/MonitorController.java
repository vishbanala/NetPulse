package com.netpulse.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.netpulse.dto.CheckAcceptedResponse;
import com.netpulse.dto.CreateMonitorRequest;
import com.netpulse.dto.IncidentResponse;
import com.netpulse.dto.MonitorStatsResponse;
import com.netpulse.model.Monitor;
import com.netpulse.model.MonitoringCheck;
import com.netpulse.repository.IncidentRepository;
import com.netpulse.repository.MonitorRepository;
import com.netpulse.repository.MonitoringCheckRepository;
import com.netpulse.security.ApprovedMonitorUrls;
import com.netpulse.sqs.MonitoringJob;
import com.netpulse.sqs.MonitoringJobPublisher;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import software.amazon.awssdk.services.sqs.model.SqsException;

/**
 * UNAUTHENTICATED — localhost development only.
 * Do not expose publicly until authentication, authorization, and rate limiting exist.
 */
@RestController
@RequestMapping("/api/monitors")
@Validated
public class MonitorController {

    private static final Logger log = LoggerFactory.getLogger(MonitorController.class);

    private static final int DEFAULT_CHECK_LIMIT = 50;

    private final MonitorRepository monitorRepository;
    private final MonitoringCheckRepository monitoringCheckRepository;
    private final IncidentRepository incidentRepository;
    private final MonitoringJobPublisher monitoringJobPublisher;

    public MonitorController(MonitorRepository monitorRepository,
                             MonitoringCheckRepository monitoringCheckRepository,
                             IncidentRepository incidentRepository,
                             MonitoringJobPublisher monitoringJobPublisher) {
        this.monitorRepository = monitorRepository;
        this.monitoringCheckRepository = monitoringCheckRepository;
        this.incidentRepository = incidentRepository;
        this.monitoringJobPublisher = monitoringJobPublisher;
    }

    @GetMapping
    public List<Monitor> listMonitors() {
        return monitorRepository.findAll();
    }

    @PostMapping
    public ResponseEntity<Monitor> createMonitor(@Valid @RequestBody CreateMonitorRequest request) {
        boolean enabled = request.getEnabled() == null || request.getEnabled();
        Monitor created = monitorRepository.insert(
                request.getName().trim(),
                request.getUrl().trim(),
                request.getIntervalSeconds(),
                enabled);
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/{id}")
    public Monitor getMonitor(@PathVariable UUID id) {
        return requireMonitor(id);
    }

    /**
     * Full update of an existing monitor (name, URL, interval, enabled).
     * Uses the same validation rules as create.
     */
    @PutMapping("/{id}")
    public Monitor updateMonitor(@PathVariable UUID id, @Valid @RequestBody CreateMonitorRequest request) {
        boolean enabled = request.getEnabled() == null || request.getEnabled();
        return monitorRepository.update(
                        id,
                        request.getName().trim(),
                        request.getUrl().trim(),
                        request.getIntervalSeconds(),
                        enabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Monitor not found"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMonitor(@PathVariable UUID id) {
        if (!monitorRepository.deleteById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Monitor not found");
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Recent monitoring checks for a monitor, newest first.
     *
     * @param limit max rows to return (default 50, max 100)
     */
    @GetMapping("/{id}/checks")
    public List<MonitoringCheck> listChecks(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit) {
        requireMonitorExists(id);
        int effectiveLimit = limit > 0 ? limit : DEFAULT_CHECK_LIMIT;
        return monitoringCheckRepository.findByMonitorId(id, effectiveLimit);
    }

    /**
     * Aggregated check statistics for a monitor.
     */
    @GetMapping("/{id}/stats")
    public MonitorStatsResponse getStats(@PathVariable UUID id) {
        requireMonitorExists(id);
        return monitoringCheckRepository.aggregateStats(id);
    }

    /**
     * Incidents for a monitor, newest first. Status is {@code open} or {@code resolved}.
     */
    @GetMapping("/{id}/incidents")
    public List<IncidentResponse> listIncidents(@PathVariable UUID id) {
        requireMonitorExists(id);
        return incidentRepository.findByMonitorId(id).stream()
                .map(IncidentResponse::from)
                .toList();
    }

    /**
     * Temporary local-dev endpoint: enqueue one monitoring job to real AWS SQS.
     * Does not run the C engine and does not write check results.
     */
    @PostMapping("/{id}/check")
    public ResponseEntity<CheckAcceptedResponse> enqueueCheck(@PathVariable UUID id) {
        Monitor monitor = requireMonitor(id);

        if (!monitor.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Monitor is disabled");
        }

        if (!ApprovedMonitorUrls.isApproved(monitor.getUrl())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Monitor URL is not an approved monitoring target");
        }

        if (!monitoringJobPublisher.isConfigured()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "SQS is not configured. Set AWS_REGION and NETPULSE_SQS_QUEUE_URL");
        }

        UUID jobId = UUID.randomUUID();
        MonitoringJob job = new MonitoringJob(jobId, monitor.getId(), monitor.getUrl(), Instant.now());

        try {
            String sqsMessageId = monitoringJobPublisher.publish(job);
            log.info("Accepted check enqueue monitorId={} jobId={} sqsMessageId={}",
                    monitor.getId(), jobId, sqsMessageId);
            return ResponseEntity.accepted()
                    .body(new CheckAcceptedResponse(jobId, monitor.getId(), sqsMessageId));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        } catch (SqsException ex) {
            String awsMessage = ex.awsErrorDetails() != null
                    ? ex.awsErrorDetails().errorMessage()
                    : "SQS publish failed";
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "SQS publish failed: " + awsMessage);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage());
        }
    }

    private Monitor requireMonitor(UUID id) {
        return monitorRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Monitor not found"));
    }

    private void requireMonitorExists(UUID id) {
        if (!monitorRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Monitor not found");
        }
    }
}
