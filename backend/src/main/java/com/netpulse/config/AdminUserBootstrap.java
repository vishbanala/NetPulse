package com.netpulse.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.netpulse.security.AdminAuthProperties;
import com.netpulse.security.SecurityModes;
import com.netpulse.repository.UserRepository;

import jakarta.annotation.PostConstruct;

/**
 * Ensures the legacy env-configured admin exists in {@code users} for AUTHENTICATED mode.
 */
@Component
public class AdminUserBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminUserBootstrap.class);

    private final String securityMode;
    private final AdminAuthProperties adminAuthProperties;
    private final UserRepository userRepository;

    public AdminUserBootstrap(org.springframework.core.env.Environment environment,
                              AdminAuthProperties adminAuthProperties,
                              UserRepository userRepository) {
        this.securityMode = SecurityModes.normalize(
                environment.getProperty("netpulse.security.mode", SecurityModes.UNAUTHENTICATED_LOCALHOST_ONLY));
        this.adminAuthProperties = adminAuthProperties;
        this.userRepository = userRepository;
    }

    @PostConstruct
    void bootstrap() {
        try {
            if (SecurityModes.isAuthenticated(securityMode)) {
                String email = adminAuthProperties.resolvedEmail();
                String hash = adminAuthProperties.getPasswordHash();
                if (email.isEmpty() || hash.isEmpty() || !hash.startsWith("$2")) {
                    return;
                }
                userRepository.upsertAdmin(email, hash);
                log.info("Bootstrap admin user ensured for email={}", email);
                return;
            }
            if (SecurityModes.isLocalUnauthenticated(securityMode)) {
                // Placeholder BCrypt hash — local mode never checks the password.
                userRepository.upsertAdmin(
                        LocalDevPrincipalFilter.LOCAL_DEV_EMAIL,
                        "$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG");
                log.info("Local dev user ensured for email={}", LocalDevPrincipalFilter.LOCAL_DEV_EMAIL);
            }
        } catch (Exception ex) {
            // Table may not exist until migration 007 is applied.
            log.warn("Could not bootstrap user (apply migration 007?): {}", ex.getMessage());
        }
    }
}
