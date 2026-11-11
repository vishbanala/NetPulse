package com.netpulse.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "netpulse.admin")
public class AdminAuthProperties {

    /**
     * Legacy admin login name. Prefer {@link #email}; if email is empty, username is treated as email.
     */
    private String username = "";

    /**
     * Bootstrap admin email (normalized). Required for AUTHENTICATED multi-user mode.
     */
    private String email = "";

    /**
     * BCrypt hash of the admin password (e.g. {@code $2a$10$...}). Never a plaintext password.
     */
    private String passwordHash = "";

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username == null ? "" : username.trim();
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email == null ? "" : email.trim();
    }

    /** Resolved bootstrap email: NETPULSE_ADMIN_EMAIL, else username if it looks like email, else empty. */
    public String resolvedEmail() {
        if (!email.isEmpty()) {
            return email.toLowerCase();
        }
        if (username.contains("@")) {
            return username.toLowerCase();
        }
        if (!username.isEmpty()) {
            return username.toLowerCase() + "@localhost";
        }
        return "";
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash == null ? "" : passwordHash.trim();
    }
}
