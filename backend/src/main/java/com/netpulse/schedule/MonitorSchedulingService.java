package com.netpulse.schedule;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.netpulse.repository.MonitorScheduleRepository;
import com.netpulse.security.ApprovedMonitorUrls;
import com.netpulse.sqs.MonitoringJob;
import com.netpulse.sqs.MonitoringJobPublisher;

/**
 * Claims due monitors in PostgreSQL, then publishes SQS jobs.
 * Claim commits before publish so crashes leave a retryable {@code pending_job_id}.
 * Retries reuse the same job id (at-least-once SQS; worker dedupes on job_id).
 *
 * <p>Publish failures — including AWS credential {@code SdkClientException}s that are
 * not {@code SqsException} — must release the publish lease while keeping
 * {@code pending_job_id}, so the next tick can reacquire expired/free leases.
 */
@Service
public class MonitorSchedulingService {

    private static final Logger log = LoggerFactory.getLogger(MonitorSchedulingService.class);

    private final MonitorScheduleRepository scheduleRepository;
    private final MonitoringJobPublisher jobPublisher;
    private final Clock clock;
    private final int batchSize;
    private final Duration publishLease;

    public MonitorSchedulingService(MonitorScheduleRepository scheduleRepository,
                                    MonitoringJobPublisher jobPublisher,
                                    Clock clock,
                                    @Value("${netpulse.scheduler.batch-size:50}") int batchSize,
                                    @Value("${netpulse.scheduler.publish-lease-seconds:60}") long publishLeaseSeconds) {
        this.scheduleRepository = scheduleRepository;
        this.jobPublisher = jobPublisher;
        this.clock = clock;
        this.batchSize = Math.max(1, batchSize);
        this.publishLease = Duration.ofSeconds(Math.max(5, publishLeaseSeconds));
    }

    /**
     * One scheduler tick: ensure rows, claim due intervals, publish pending jobs.
     */
    public TickResult runOnce() {
        if (!jobPublisher.isConfigured()) {
            log.warn("Scheduler tick skipped: SQS is not configured");
            return TickResult.skipped("sqs_not_configured");
        }

        Instant now = clock.instant();
        int ensured = scheduleRepository.ensureSchedules(now);
        List<DueMonitorJob> claimed = scheduleRepository.claimDueMonitors(now, batchSize);
        Instant leaseUntil = now.plus(publishLease);
        List<DueMonitorJob> leased = scheduleRepository.leasePendingPublishes(now, leaseUntil, batchSize);

        int published = 0;
        int failed = 0;
        int skippedUnapproved = 0;
        List<String> errors = new ArrayList<>();

        for (DueMonitorJob due : leased) {
            if (!ApprovedMonitorUrls.isApproved(due.getUrl())) {
                scheduleRepository.abandonPending(due.getMonitorId(), due.getJobId(), clock.instant());
                skippedUnapproved++;
                log.warn("Abandoned pending job with unapproved URL monitorId={} jobId={}",
                        due.getMonitorId(), due.getJobId());
                continue;
            }

            MonitoringJob job = new MonitoringJob(
                    due.getJobId(),
                    due.getMonitorId(),
                    due.getUrl(),
                    due.getScheduledAt());

            try {
                jobPublisher.publish(job);
                if (scheduleRepository.markPublished(due.getMonitorId(), due.getJobId(), clock.instant())) {
                    published++;
                }
            } catch (RuntimeException ex) {
                // Broad catch on purpose: credential/provider failures are SdkClientException
                // (not SqsException). Leaving the lease set blocks retries until it expires,
                // and an uncaught throw aborts the rest of the tick.
                failed++;
                errors.add(due.getMonitorId() + ":" + ex.getClass().getSimpleName());
                scheduleRepository.releasePublishLease(due.getMonitorId(), due.getJobId(), clock.instant());
                log.error("SQS publish failed; pending job retained for retry monitorId={} jobId={} reason={}",
                        due.getMonitorId(), due.getJobId(), ex.getMessage());
            }
        }

        log.info(
                "Scheduler tick ensured={} claimed={} leased={} published={} failed={} skippedUnapproved={}",
                ensured, claimed.size(), leased.size(), published, failed, skippedUnapproved);

        return new TickResult(
                ensured, claimed.size(), leased.size(), published, failed, skippedUnapproved, List.copyOf(errors));
    }

    public record TickResult(
            int ensured,
            int claimed,
            int leased,
            int published,
            int failed,
            int skippedUnapproved,
            List<String> errors) {

        public static TickResult skipped(String reason) {
            return new TickResult(0, 0, 0, 0, 0, 0, List.of(reason));
        }

        public boolean wasSkipped() {
            return ensured == 0 && claimed == 0 && leased == 0 && published == 0
                    && failed == 0 && skippedUnapproved == 0 && errors.size() == 1;
        }
    }
}
