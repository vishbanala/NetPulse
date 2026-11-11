package com.netpulse.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

class CreateMonitorRequestAllowlistTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void acceptsAllowlistedGoogleUrl() {
        CreateMonitorRequest req = request("https://www.google.com");
        assertThat(validator.validate(req)).isEmpty();
    }

    @Test
    void rejectsUnallowlistedPublicUrlAtCreateTime() {
        CreateMonitorRequest req = request("https://www.wikipedia.org");
        Set<ConstraintViolation<CreateMonitorRequest>> violations = validator.validate(req);
        assertThat(violations)
                .anyMatch(v -> v.getMessage().contains("approved monitor allowlist"));
    }

    private static CreateMonitorRequest request(String url) {
        CreateMonitorRequest req = new CreateMonitorRequest();
        req.setName("Probe");
        req.setUrl(url);
        req.setIntervalSeconds(60);
        req.setEnabled(true);
        return req;
    }
}
