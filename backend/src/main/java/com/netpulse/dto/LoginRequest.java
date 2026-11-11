package com.netpulse.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Login accepts {@code email} (preferred) or legacy {@code username} (treated as email).
 */
public class LoginRequest {

    private String email;

    private String username;

    @NotBlank
    private String password;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String resolvedEmail() {
        if (email != null && !email.isBlank()) {
            return email.trim();
        }
        if (username != null && !username.isBlank()) {
            return username.trim();
        }
        return "";
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
