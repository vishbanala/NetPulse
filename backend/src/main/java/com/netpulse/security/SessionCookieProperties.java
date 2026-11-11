package com.netpulse.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "netpulse.session")
public class SessionCookieProperties {

    /**
     * Cookie SameSite for session + CSRF cookies: Lax (same-site) or None (cross-site; requires Secure).
     */
    private String cookieSameSite = "Lax";

    /**
     * Force Secure flag on cookies (required for SameSite=None and for HTTPS production).
     */
    private boolean cookieSecure = false;

    public String getCookieSameSite() {
        return cookieSameSite;
    }

    public void setCookieSameSite(String cookieSameSite) {
        this.cookieSameSite = cookieSameSite == null || cookieSameSite.isBlank() ? "Lax" : cookieSameSite.trim();
    }

    public boolean isCookieSecure() {
        return cookieSecure;
    }

    public void setCookieSecure(boolean cookieSecure) {
        this.cookieSecure = cookieSecure;
    }
}
