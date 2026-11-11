package com.netpulse.security;

import java.time.Clock;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Simple in-memory login attempt limiter (single-instance portfolio deploy).
 */
@Component
public class LoginRateLimiter {

    private final Clock clock;
    private final int maxAttempts;
    private final Duration window;
    private final ConcurrentHashMap<String, Window> attempts = new ConcurrentHashMap<>();

    public LoginRateLimiter(Clock clock,
                            @Value("${netpulse.auth.login-max-attempts:5}") int maxAttempts,
                            @Value("${netpulse.auth.login-window-seconds:60}") long windowSeconds) {
        this.clock = clock;
        this.maxAttempts = Math.max(1, maxAttempts);
        this.window = Duration.ofSeconds(Math.max(1, windowSeconds));
    }

    public boolean allow(String clientKey) {
        prune();
        String key = clientKey == null || clientKey.isBlank() ? "unknown" : clientKey;
        long now = clock.millis();
        Window w = attempts.compute(key, (k, existing) -> {
            if (existing == null || now - existing.windowStartMs >= window.toMillis()) {
                return new Window(now, new AtomicInteger(0));
            }
            return existing;
        });
        return w.count.incrementAndGet() <= maxAttempts;
    }

    public void reset(String clientKey) {
        if (clientKey != null) {
            attempts.remove(clientKey);
        }
    }

    private void prune() {
        long now = clock.millis();
        long ttl = window.toMillis() * 2;
        Iterator<Map.Entry<String, Window>> it = attempts.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Window> e = it.next();
            if (now - e.getValue().windowStartMs > ttl) {
                it.remove();
            }
        }
    }

    private static final class Window {
        private final long windowStartMs;
        private final AtomicInteger count;

        private Window(long windowStartMs, AtomicInteger count) {
            this.windowStartMs = windowStartMs;
            this.count = count;
        }
    }
}
