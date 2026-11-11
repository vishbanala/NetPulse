package com.netpulse.security;

import java.util.Locale;
import java.util.Set;

/**
 * Explicit public-URL allowlist — kept in sync with {@code monitoring-engine/src/url_guard.c}.
 * SSRF defenses (private/link-local/metadata hosts, credentials, scheme checks) remain in the
 * C worker independently of this list. Expand only with known-safe public targets.
 */
public final class ApprovedMonitorUrls {

    private static final Set<String> ALLOWED = Set.of(
            "https://example.com",
            "https://example.com/",
            "https://www.google.com",
            "https://www.google.com/",
            "https://google.com",
            "https://google.com/",
            "https://httpbin.org/get",
            "https://httpbin.org/status/200",
            "https://httpbin.org/status/500",
            "https://httpbin.org/delay/5"
    );

    private ApprovedMonitorUrls() {
    }

    public static boolean isApproved(String url) {
        if (url == null) {
            return false;
        }
        return ALLOWED.contains(url.trim().toLowerCase(Locale.ROOT));
    }
}
