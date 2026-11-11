package com.netpulse.config;

import org.springframework.context.annotation.Configuration;

/**
 * Local default remains {@code UNAUTHENTICATED_LOCALHOST_ONLY} via {@link SecurityConfig}.
 * Production uses {@code AUTHENTICATED} or {@code HEALTH_ONLY}.
 */
@Configuration
public class LocalDevSecurityConfig {
}
