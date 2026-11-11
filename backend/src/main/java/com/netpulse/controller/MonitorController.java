package com.netpulse.controller;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
import com.netpulse.repository.MonitoringCheckRepository;
import com.netpulse.security.AuthRateLimiter;
import com.netpulse.security.CurrentUser;
import com.netpulse.security.PublicUrlValidator;
import com.netpulse.service.MonitorService;
import com.netpulse.sqs.MonitoringJob;
import com.netpulse.sqs.MonitoringJobPublisher;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import software.amazon.awssdk.services.sqs.model.SqsException;

@RestController
@RequestMapping("/api/monitors")
@Validated
public class MonitorController {

    private static final Logger log = LoggerFactory.getLogger(MonitorController.class);
    private static final int DEFAULT_CHECK_LIMIT = 50;

    private final MonitorService monitorService;
    private final MonitoringCheckRepository monitoringCheckRepository;
    private final IncidentRepository incidentRepository;
    private final MonitoringJobPublisher monitoringJobPublisher;
    private final PublicUrlValidator publicUrlValidator;
    private final AuthRateLimiter authRateLimiter;

    public MonitorController(MonitorService monitorService,
                             MonitoringCheckRepository monitoringCheckRepository,
                             IncidentRepository incidentRepository,
                             MonitoringJobPublisher monitoringJobPublisher,
                             PublicUrlValidator publicUrlValidator,
                             AuthRateLimiter authRateLimiter) {
        this.monitorService = monitorService;
        this.monitoringCheckRepository = monitoringCheckRepository;
        this.incidentRepository = incidentRepository;
        this.monitoringJobPublisher = monitoringJobPublisher;
        this.publicUrlValidator = publicUrlValidator;
        this.authRateLimiter = authRateLimiter;
    }

    @GetMapping
    public List<Monitor> listMonitors(Authentication authentication) {
        UUID userId = CurrentUser.requireUserId(authentication);
        return monitorService.listForUser(userId);
    }

    @PostMapping
    public ResponseEntity<Monitor> createMonitor(@Valid @RequestBody CreateMonitorRequest request,
                                                 Authentication authentication,
                                                 HttpServletRequest httpRequest) {
        UUID userId = CurrentUser.requireUserId(authentication);
        rateLimitMutate(userId, httpRequest);
        boolean enabled = request.getEnabled() == null || request.getEnabled();
        try {
            Monitor created = monitorService.create(
                    userId,
                    request.getName().trim(),
                    request.getUrl().trim(),
                    request.getIntervalSeconds(),
                    enabled);
            return ResponseEntity.status(HttpStatus.CREATED).body(created);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @GetMapping("/{id}")
    public Monitor getMonitor(@PathVariable UUID id, Authentication authentication) {
        return monitorService.requireOwned(id, CurrentUser.requireUserId(authentication));
    }

    @PutMapping("/{id}")
    public Monitor updateMonitor(@PathVariable UUID id,
                                 @Valid @RequestBody CreateMonitorRequest request,
                                 Authentication authentication,
                                 HttpServletRequest httpRequest) {
        UUID userId = CurrentUser.requireUserId(authentication);
        rateLimitMutate(userId, httpRequest);
        boolean enabled = request.getEnabled() == null || request.getEnabled();
        try {
            return monitorService.update(
                    userId,
                    id,
                    request.getName().trim(),
                    request.getUrl().trim(),
                    request.getIntervalSeconds(),
                    enabled);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMonitor(@PathVariable UUID id,
                                              Authentication authentication,
                                              HttpServletRequest httpRequest) {
        UUID userId = CurrentUser.requireUserId(authentication);
        rateLimitMutate(userId, httpRequest);
        monitorService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/checks")
    public List<MonitoringCheck> listChecks(
            @PathVariable UUID id,
            @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit,
            Authentication authentication) {
        UUID userId = CurrentUser.requireUserId(authentication);
        monitorService.requireOwnedExists(id, userId);
        int effectiveLimit = limit > 0 ? limit : DEFAULT_CHECK_LIMIT;
        return monitoringCheckRepository.findByMonitorId(id, effectiveLimit);
    }

    @GetMapping("/{id}/stats")
    public MonitorStatsResponse getStats(@PathVariable UUID id, Authentication authentication) {
        UUID userId = CurrentUser.requireUserId(authentication);
        monitorService.requireOwnedExists(id, userId);
        return monitoringCheckRepository.aggregateStats(id);
    }

    @GetMapping("/{id}/incidents")
    public List<IncidentResponse> listIncidents(@PathVariable UUID id, Authentication authentication) {
        UUID userId = CurrentUser.requireUserId(authentication);
        monitorService.requireOwnedExists(id, userId);
        return incidentRepository.findByMonitorId(id).stream()
                .map(IncidentResponse::from)
                .toList();
    }

    @PostMapping("/{id}/check")
    public ResponseEntity<CheckAcceptedResponse> enqueueCheck(@PathVariable UUID id,
                                                              Authentication authentication,
                                                              HttpServletRequest httpRequest) {
        UUID userId = CurrentUser.requireUserId(authentication);
        rateLimitMutate(userId, httpRequest);
        Monitor monitor = monitorService.requireOwned(id, userId);

        if (!monitor.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Monitor is disabled");
        }

        try {
            publicUrlValidator.validateOrThrow(monitor.getUrl());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
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

    private void rateLimitMutate(UUID userId, HttpServletRequest request) {
        String key = "monitor:" + userId + ":" + clientKey(request);
        if (!authRateLimiter.allow(key, 30, 60_000L)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Try again later.");
        }
    }

    private static String clientKey(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}
