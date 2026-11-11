package com.netpulse.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight liveness endpoint. Database status is under /actuator/health.
 * UNAUTHENTICATED — localhost development only.
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    @Value("${netpulse.security.mode:UNAUTHENTICATED_LOCALHOST_ONLY}")
    private String securityMode;

    @GetMapping("/health")
    public Map<String, String> health() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("application", "NetPulse");
        body.put("securityMode", securityMode);
        return body;
    }
}
