package com.netpulse.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Regression: production monitor e6c1c8c5-0d7e-4910-88e6-8bb6894e7434
 * (https://www.google.com) was abandoned as unapproved while example.com published,
 * leaving the dashboard in "Waiting" with zero checks.
 */
class ApprovedMonitorUrlsTest {

    @Test
    void approvesGoogleAndExamplePublicTargets() {
        assertThat(ApprovedMonitorUrls.isApproved("https://www.google.com")).isTrue();
        assertThat(ApprovedMonitorUrls.isApproved("https://www.google.com/")).isTrue();
        assertThat(ApprovedMonitorUrls.isApproved("HTTPS://WWW.GOOGLE.COM")).isTrue();
        assertThat(ApprovedMonitorUrls.isApproved("https://google.com")).isTrue();
        assertThat(ApprovedMonitorUrls.isApproved("https://example.com")).isTrue();
        assertThat(ApprovedMonitorUrls.isApproved("https://httpbin.org/get")).isTrue();
    }

    @Test
    void rejectsUnlistedPublicAndUnsafeTargets() {
        assertThat(ApprovedMonitorUrls.isApproved("https://www.wikipedia.org")).isFalse();
        assertThat(ApprovedMonitorUrls.isApproved("https://evil.example/phish")).isFalse();
        assertThat(ApprovedMonitorUrls.isApproved("http://127.0.0.1/")).isFalse();
        assertThat(ApprovedMonitorUrls.isApproved("http://169.254.169.254/latest/meta-data/")).isFalse();
        assertThat(ApprovedMonitorUrls.isApproved(null)).isFalse();
        assertThat(ApprovedMonitorUrls.isApproved("")).isFalse();
    }
}
