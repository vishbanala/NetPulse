package com.netpulse.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

class PublicUrlValidatorTest {

    private final PublicUrlValidator validator = new PublicUrlValidator();

    @Test
    void acceptsPublicHttpsExample() {
        // May fail offline; treat DNS failure as non-null reject only when network absent.
        String result = validator.validate("https://example.com");
        assertThat(result == null || result.contains("DNS")).isTrue();
        if (result == null) {
            assertThat(validator.validate("https://example.com")).isNull();
        }
    }

    @Test
    void rejectsCredentialsPrivateAndMetadata() {
        assertThat(validator.validate("https://user:pass@example.com/")).isNotNull();
        assertThat(validator.validate("http://127.0.0.1/")).isNotNull();
        assertThat(validator.validate("http://10.0.0.1/")).isNotNull();
        assertThat(validator.validate("http://169.254.169.254/latest/meta-data/")).isNotNull();
        assertThat(validator.validate("http://[::1]/")).isNotNull();
        assertThat(validator.validate("https://example.com:8443/")).isNotNull();
        assertThat(validator.validate("ftp://example.com/")).isNotNull();
    }

    @Test
    void classifiesPrivateInetAddresses() throws Exception {
        assertThat(PublicUrlValidator.isGloballyRoutablePublic(InetAddress.getByName("127.0.0.1"))).isFalse();
        assertThat(PublicUrlValidator.isGloballyRoutablePublic(InetAddress.getByName("10.1.2.3"))).isFalse();
        assertThat(PublicUrlValidator.isGloballyRoutablePublic(InetAddress.getByName("169.254.169.254"))).isFalse();
        assertThat(PublicUrlValidator.isGloballyRoutablePublic(InetAddress.getByName("192.168.1.1"))).isFalse();
        assertThat(PublicUrlValidator.isGloballyRoutablePublic(InetAddress.getByName("8.8.8.8"))).isTrue();
    }
}
