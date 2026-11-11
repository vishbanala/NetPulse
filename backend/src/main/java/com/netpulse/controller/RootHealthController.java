package com.netpulse.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Minimal liveness probe for hosts (e.g. Render) that expect {@code GET /health}.
 * Does not expose configuration, credentials, or security mode.
 */
@RestController
public class RootHealthController {

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
