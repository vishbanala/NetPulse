package com.netpulse.security;

import java.util.Locale;
import java.util.Set;

/**
 * Step 3/5 temporary allowlist — same public test URLs as the C monitoring engine.
 * Replace with ownership-backed authorization before accepting untrusted targets.
 */
public final class ApprovedMonitorUrls {

    private static final Set<String> ALLOWED = Set.of(
            "https://example.com",
            "https://example.com/",
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
