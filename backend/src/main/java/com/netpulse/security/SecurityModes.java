package com.netpulse.security;

/**
 * Values for {@code netpulse.security.mode} / {@code NETPULSE_SECURITY_MODE}.
 */
public final class SecurityModes {

    /** Local development: full unauthenticated API (prefer loopback bind). */
    public static final String UNAUTHENTICATED_LOCALHOST_ONLY = "UNAUTHENTICATED_LOCALHOST_ONLY";

    /** Public deploy without login: only health endpoints. */
    public static final String HEALTH_ONLY = "HEALTH_ONLY";

    /** Public deploy with single-admin session authentication. */
    public static final String AUTHENTICATED = "AUTHENTICATED";

    private SecurityModes() {
    }

    public static String normalize(String mode) {
        if (mode == null || mode.isBlank()) {
            return UNAUTHENTICATED_LOCALHOST_ONLY;
        }
        return mode.trim();
    }

    public static boolean isHealthOnly(String mode) {
        return HEALTH_ONLY.equalsIgnoreCase(normalize(mode));
    }

    public static boolean isAuthenticated(String mode) {
        return AUTHENTICATED.equalsIgnoreCase(normalize(mode));
    }

    public static boolean isLocalUnauthenticated(String mode) {
        return UNAUTHENTICATED_LOCALHOST_ONLY.equalsIgnoreCase(normalize(mode));
    }
}
