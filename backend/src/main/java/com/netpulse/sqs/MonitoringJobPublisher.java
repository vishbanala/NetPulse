package com.netpulse.sqs;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import software.amazon.awssdk.services.sqs.model.SqsException;

@Service
public class MonitoringJobPublisher {

    private static final Logger log = LoggerFactory.getLogger(MonitoringJobPublisher.class);

    private final ObjectProvider<SqsClient> sqsClientProvider;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final String queueUrl;

    public MonitoringJobPublisher(ObjectProvider<SqsClient> sqsClientProvider,
                                  ObjectMapper objectMapper,
                                  Validator validator,
                                  @Value("${netpulse.sqs.queue-url:}") String queueUrl) {
        this.sqsClientProvider = sqsClientProvider;
        this.objectMapper = objectMapper;
        this.validator = validator;
        this.queueUrl = queueUrl == null ? "" : queueUrl.trim();
    }

    public boolean isConfigured() {
        return !queueUrl.isEmpty() && sqsClientProvider.getIfAvailable() != null;
    }

    /**
     * Publishes a validated monitoring job JSON message to SQS.
     *
     * @return SQS message id
     */
    public String publish(MonitoringJob job) {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "SQS is not configured. Set AWS_REGION and NETPULSE_SQS_QUEUE_URL, "
                            + "and ensure AWS credentials are available via the default provider chain.");
        }

        Set<ConstraintViolation<MonitoringJob>> violations = validator.validate(job);
        if (!violations.isEmpty()) {
            String details = violations.stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("invalid job");
            throw new IllegalArgumentException("Invalid monitoring job payload: " + details);
        }

        String body;
        try {
            body = objectMapper.writeValueAsString(job);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize monitoring job to JSON", ex);
        }

        SqsClient sqsClient = sqsClientProvider.getObject();
        try {
            SendMessageResponse response = sqsClient.sendMessage(SendMessageRequest.builder()
                    .queueUrl(queueUrl)
                    .messageBody(body)
                    .build());

            log.info("Published monitoring job jobId={} monitorId={} sqsMessageId={}",
                    job.getJobId(), job.getMonitorId(), response.messageId());
            return response.messageId();
        } catch (SqsException ex) {
            log.error("SQS publish failed for jobId={} monitorId={} awsError={}",
                    job.getJobId(),
                    job.getMonitorId(),
                    ex.awsErrorDetails() != null ? ex.awsErrorDetails().errorMessage() : ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            // Credential / network failures are often SdkClientException, not SqsException.
            log.error("SQS publish failed for jobId={} monitorId={} reason={}",
                    job.getJobId(), job.getMonitorId(), ex.getMessage());
            throw ex;
        }
    }
}
