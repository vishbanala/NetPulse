package com.netpulse.service;

import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.netpulse.model.Monitor;
import com.netpulse.repository.MonitorRepository;
import com.netpulse.repository.UserRepository;
import com.netpulse.security.PublicUrlValidator;

@Service
public class MonitorService {

    public static final int MAX_ENABLED_MONITORS_PER_USER = 3;
    public static final int MIN_INTERVAL_SECONDS = 300;

    private final MonitorRepository monitorRepository;
    private final UserRepository userRepository;
    private final PublicUrlValidator publicUrlValidator;
    private final int maxEnabledMonitorsGlobal;

    public MonitorService(MonitorRepository monitorRepository,
                          UserRepository userRepository,
                          PublicUrlValidator publicUrlValidator,
                          @Value("${netpulse.monitors.max-enabled-global:40}") int maxEnabledMonitorsGlobal) {
        this.monitorRepository = monitorRepository;
        this.userRepository = userRepository;
        this.publicUrlValidator = publicUrlValidator;
        this.maxEnabledMonitorsGlobal = Math.max(1, maxEnabledMonitorsGlobal);
    }

    public List<Monitor> listForUser(UUID userId) {
        return monitorRepository.findAllByUserId(userId);
    }

    public Monitor requireOwned(UUID monitorId, UUID userId) {
        return monitorRepository.findByIdAndUserId(monitorId, userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Monitor not found"));
    }

    public void requireOwnedExists(UUID monitorId, UUID userId) {
        if (!monitorRepository.existsByIdAndUserId(monitorId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Monitor not found");
        }
    }

    @Transactional
    public Monitor create(UUID userId, String name, String url, int intervalSeconds, boolean enabled) {
        validateInterval(intervalSeconds);
        publicUrlValidator.validateOrThrow(url);
        userRepository.lockById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated"));
        if (enabled) {
            enforceEnabledCaps(userId);
        }
        return monitorRepository.insert(userId, name, url.trim(), intervalSeconds, enabled);
    }

    @Transactional
    public Monitor update(UUID userId, UUID monitorId, String name, String url, int intervalSeconds, boolean enabled) {
        validateInterval(intervalSeconds);
        publicUrlValidator.validateOrThrow(url);
        Monitor existing = requireOwned(monitorId, userId);
        userRepository.lockById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated"));
        if (enabled && !existing.isEnabled()) {
            enforceEnabledCaps(userId);
        }
        return monitorRepository.update(monitorId, userId, name, url.trim(), intervalSeconds, enabled)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Monitor not found"));
    }

    @Transactional
    public void delete(UUID userId, UUID monitorId) {
        if (!monitorRepository.deleteByIdAndUserId(monitorId, userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Monitor not found");
        }
    }

    private void enforceEnabledCaps(UUID userId) {
        int enabledCount = monitorRepository.countEnabledByUserId(userId);
        if (enabledCount >= MAX_ENABLED_MONITORS_PER_USER) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Maximum of " + MAX_ENABLED_MONITORS_PER_USER + " active monitors per account");
        }
        int globalEnabled = monitorRepository.countEnabledGlobal();
        if (globalEnabled >= maxEnabledMonitorsGlobal) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Site-wide monitoring capacity is full. Try again later or disable another monitor.");
        }
    }

    private static void validateInterval(int intervalSeconds) {
        if (intervalSeconds < MIN_INTERVAL_SECONDS || intervalSeconds > 86400) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "intervalSeconds must be between " + MIN_INTERVAL_SECONDS + " and 86400");
        }
    }
}
