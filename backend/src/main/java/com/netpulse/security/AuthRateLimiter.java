package com.netpulse.security;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Simple in-memory sliding window limiter for auth and monitor-management abuse controls.
 */
@Component
public class AuthRateLimiter {

    private final Clock clock;
    private final int maxAttempts;
    private final long windowMillis;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    public AuthRateLimiter(Clock clock,
                           @Value("${netpulse.auth.login-max-attempts:5}") int maxAttempts,
                           @Value("${netpulse.auth.login-window-seconds:60}") long windowSeconds) {
        this.clock = clock;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.windowMillis = Math.max(1L, windowSeconds) * 1000L;
    }

    public boolean allow(String key) {
        return allow(key, maxAttempts, windowMillis);
    }

    public boolean allow(String key, int limit, long windowMs) {
        long now = clock.millis();
        Window window = windows.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStartMillis >= windowMs) {
                return new Window(now, 1);
            }
            existing.count++;
            return existing;
        });
        return window.count <= limit;
    }

    public void reset(String key) {
        windows.remove(key);
    }

    private static final class Window {
        final long windowStartMillis;
        int count;

        Window(long windowStartMillis, int count) {
            this.windowStartMillis = windowStartMillis;
            this.count = count;
        }
    }
}
