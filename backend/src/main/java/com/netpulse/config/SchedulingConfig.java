package com.netpulse.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is off unless {@code netpulse.scheduler.enabled=true}.
 * Keep disabled until Phase 7 is intentionally turned on in a given environment.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "netpulse.scheduler.enabled", havingValue = "true")
public class SchedulingConfig {
}
