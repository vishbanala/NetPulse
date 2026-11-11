package com.netpulse.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * AWS SQS client using the default credential provider chain.
 * Honors AWS_PROFILE / AWS_REGION and env credentials. Never put keys in source.
 * If the profile uses {@code aws login} (login_session), export temporary credentials
 * into the process env before startup so the Java SDK can authenticate.
 */
@Configuration
public class AwsSqsConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "netpulse.sqs.queue-url")
    public SqsClient sqsClient(@Value("${aws.region}") String region) {
        if (region == null || region.isBlank()) {
            throw new IllegalStateException(
                    "AWS_REGION must be set when NETPULSE_SQS_QUEUE_URL is configured");
        }
        return SqsClient.builder()
                .region(Region.of(region.trim()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
