package com.netpulse.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "netpulse.admin")
public class AdminAuthProperties {

    /**
     * Single admin username. Required when security mode is AUTHENTICATED.
     */
    private String username = "";

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

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash == null ? "" : passwordHash.trim();
    }
}
