package com.netpulse.config;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.netpulse.security.SecurityModes;

import jakarta.annotation.PostConstruct;

/**
 * Fail closed when deployable security modes are missing required configuration.
 * Local {@code UNAUTHENTICATED_LOCALHOST_ONLY} remains permissive for development.
 */
@Component
public class ProductionConfigValidator {

    private final Environment environment;

    public ProductionConfigValidator(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void validate() {
        String mode = SecurityModes.normalize(
                environment.getProperty("netpulse.security.mode", SecurityModes.UNAUTHENTICATED_LOCALHOST_ONLY));

        if (SecurityModes.isLocalUnauthenticated(mode)) {
            return;
        }

        require("NETPULSE_DB_URL", firstNonBlank(
                environment.getProperty("NETPULSE_DB_URL"),
                environment.getProperty("spring.datasource.url")));
        require("NETPULSE_DB_USER", firstNonBlank(
                environment.getProperty("NETPULSE_DB_USER"),
                environment.getProperty("spring.datasource.username")));
        require("NETPULSE_DB_PASSWORD", firstNonBlank(
                environment.getProperty("NETPULSE_DB_PASSWORD"),
                environment.getProperty("spring.datasource.password")));

        boolean schedulerEnabled = Boolean.parseBoolean(
                environment.getProperty("netpulse.scheduler.enabled", "false"));
        boolean scheduleOnce = Boolean.parseBoolean(
                environment.getProperty("netpulse.schedule.once", "false"));
        // Continuous ticker or GitHub Actions one-shot enqueue both need SQS configured.
        if (schedulerEnabled || scheduleOnce) {
            require("AWS_REGION", firstNonBlank(
                    environment.getProperty("AWS_REGION"),
                    environment.getProperty("aws.region")));
            require("NETPULSE_SQS_QUEUE_URL", firstNonBlank(
                    environment.getProperty("NETPULSE_SQS_QUEUE_URL"),
                    environment.getProperty("netpulse.sqs.queue-url")));
        }
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return "";
    }

    private static void require(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for this deployment security mode");
        }
    }
}
