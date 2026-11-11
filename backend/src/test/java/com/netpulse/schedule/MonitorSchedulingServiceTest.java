package com.netpulse.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.netpulse.repository.MonitorScheduleRepository;
import com.netpulse.security.PublicUrlValidator;
import com.netpulse.sqs.MonitoringJob;
import com.netpulse.sqs.MonitoringJobPublisher;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sqs.model.SqsException;

@ExtendWith(MockitoExtension.class)
class MonitorSchedulingServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-10T21:00:00Z");

    @Mock
    private MonitorScheduleRepository scheduleRepository;

    @Mock
    private MonitoringJobPublisher jobPublisher;

    private MonitorSchedulingService service;
    private final PublicUrlValidator publicUrlValidator = new PublicUrlValidator();

    @BeforeEach
    void setUp() {
        service = new MonitorSchedulingService(
                scheduleRepository,
                jobPublisher,
                publicUrlValidator,
                Clock.fixed(NOW, ZoneOffset.UTC),
                50,
                60);
    }

    @Test
    void runOnce_skipsWhenSqsNotConfigured() {
        when(jobPublisher.isConfigured()).thenReturn(false);

        MonitorSchedulingService.TickResult result = service.runOnce();

        assertThat(result.wasSkipped()).isTrue();
        assertThat(result.errors()).containsExactly("sqs_not_configured");
        verify(scheduleRepository, never()).ensureSchedules(any());
        verify(scheduleRepository, never()).claimDueMonitors(any(), anyInt());
        verify(jobPublisher, never()).publish(any());
    }

    @Test
    void runOnce_claimsDueThenPublishesPendingWithSameJobId() {
        UUID monitorId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID jobId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        DueMonitorJob due = new DueMonitorJob(
                monitorId, jobId, "https://example.com", NOW, 60);

        when(jobPublisher.isConfigured()).thenReturn(true);
        when(scheduleRepository.ensureSchedules(NOW)).thenReturn(1);
        when(scheduleRepository.claimDueMonitors(NOW, 50)).thenReturn(List.of(due));
        when(scheduleRepository.leasePendingPublishes(eq(NOW), any(), eq(50))).thenReturn(List.of(due));
        when(jobPublisher.publish(any())).thenReturn("sqs-msg-1");
        when(scheduleRepository.markPublished(monitorId, jobId, NOW)).thenReturn(true);

        MonitorSchedulingService.TickResult result = service.runOnce();

        assertThat(result.ensured()).isEqualTo(1);
        assertThat(result.claimed()).isEqualTo(1);
        assertThat(result.leased()).isEqualTo(1);
        assertThat(result.published()).isEqualTo(1);
        assertThat(result.failed()).isZero();

        ArgumentCaptor<MonitoringJob> jobCaptor = ArgumentCaptor.forClass(MonitoringJob.class);
        verify(jobPublisher).publish(jobCaptor.capture());
        MonitoringJob published = jobCaptor.getValue();
        assertThat(published.getJobId()).isEqualTo(jobId);
        assertThat(published.getMonitorId()).isEqualTo(monitorId);
        assertThat(published.getUrl()).isEqualTo("https://example.com");
        assertThat(published.getScheduledAt()).isEqualTo(NOW);

        InOrder order = inOrder(scheduleRepository, jobPublisher);
        order.verify(scheduleRepository).ensureSchedules(NOW);
        order.verify(scheduleRepository).claimDueMonitors(NOW, 50);
        order.verify(scheduleRepository).leasePendingPublishes(eq(NOW), any(), eq(50));
        order.verify(jobPublisher).publish(any());
        order.verify(scheduleRepository).markPublished(monitorId, jobId, NOW);
    }

    @Test
    void runOnce_retainsPendingJobWhenSqsFails_soRetryUsesSameJobId() {
        UUID monitorId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID jobId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        DueMonitorJob due = new DueMonitorJob(
                monitorId, jobId, "https://httpbin.org/get", NOW, 30);

        when(jobPublisher.isConfigured()).thenReturn(true);
        when(scheduleRepository.ensureSchedules(NOW)).thenReturn(0);
        when(scheduleRepository.claimDueMonitors(NOW, 50)).thenReturn(List.of());
        when(scheduleRepository.leasePendingPublishes(eq(NOW), any(), eq(50))).thenReturn(List.of(due));
        // doThrow/doReturn avoid Mockito re-invoking publish() while re-stubbing after thenThrow.
        doThrow(SqsException.builder()
                .message("throttled")
                .awsErrorDetails(AwsErrorDetails.builder().errorMessage("throttled").build())
                .build()).when(jobPublisher).publish(any());

        MonitorSchedulingService.TickResult first = service.runOnce();

        assertThat(first.published()).isZero();
        assertThat(first.failed()).isEqualTo(1);
        verify(scheduleRepository, never()).markPublished(any(), any(), any());
        verify(scheduleRepository).releasePublishLease(monitorId, jobId, NOW);

        // Second tick: same pending job leased again and succeeds (duplicate prevention = same jobId).
        when(scheduleRepository.leasePendingPublishes(eq(NOW), any(), eq(50))).thenReturn(List.of(due));
        doReturn("sqs-msg-retry").when(jobPublisher).publish(any());
        when(scheduleRepository.markPublished(monitorId, jobId, NOW)).thenReturn(true);

        MonitorSchedulingService.TickResult second = service.runOnce();

        assertThat(second.published()).isEqualTo(1);
        ArgumentCaptor<MonitoringJob> jobCaptor = ArgumentCaptor.forClass(MonitoringJob.class);
        verify(jobPublisher, times(2)).publish(jobCaptor.capture());
        assertThat(jobCaptor.getAllValues())
                .extracting(MonitoringJob::getJobId)
                .containsExactly(jobId, jobId);
    }

    @Test
    void runOnce_doesNotPublishSameMonitorTwiceInOneTick_whenOnlyOneLeaseReturned() {
        // Repository-level SKIP LOCKED is what prevents multi-instance duplicates;
        // the service must only publish what was leased once.
        UUID monitorId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID jobId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        DueMonitorJob due = new DueMonitorJob(
                monitorId, jobId, "https://example.com/", NOW, 120);

        when(jobPublisher.isConfigured()).thenReturn(true);
        when(scheduleRepository.ensureSchedules(NOW)).thenReturn(0);
        when(scheduleRepository.claimDueMonitors(NOW, 50)).thenReturn(List.of(due));
        when(scheduleRepository.leasePendingPublishes(eq(NOW), any(), eq(50))).thenReturn(List.of(due));
        when(jobPublisher.publish(any())).thenReturn("msg");
        when(scheduleRepository.markPublished(monitorId, jobId, NOW)).thenReturn(true);

        service.runOnce();

        verify(jobPublisher, times(1)).publish(any());
        verify(scheduleRepository, times(1)).markPublished(monitorId, jobId, NOW);
    }

    @Test
    void runOnce_releasesLeaseOnCredentialFailure_soSameJobIdCanRetryImmediately() {
        // Regression: missing AWS credentials throw SdkClientException (not SqsException).
        // Previously the lease was left set, so later ticks logged leased=0 until expiry.
        UUID monitorId = UUID.fromString("99999999-9999-9999-9999-999999999999");
        UUID jobId = UUID.fromString("2d862e57-8927-4607-a22d-a1cca26ee87b");
        DueMonitorJob due = new DueMonitorJob(
                monitorId, jobId, "https://example.com", NOW, 60);

        when(jobPublisher.isConfigured()).thenReturn(true);
        when(scheduleRepository.ensureSchedules(NOW)).thenReturn(0);
        when(scheduleRepository.claimDueMonitors(NOW, 50)).thenReturn(List.of());
        when(scheduleRepository.leasePendingPublishes(eq(NOW), any(), eq(50))).thenReturn(List.of(due));
        doThrow(SdkClientException.builder()
                .message("Unable to load credentials from any of the providers in the chain")
                .build()).when(jobPublisher).publish(any());

        MonitorSchedulingService.TickResult first = service.runOnce();

        assertThat(first.leased()).isEqualTo(1);
        assertThat(first.failed()).isEqualTo(1);
        assertThat(first.published()).isZero();
        verify(scheduleRepository).releasePublishLease(monitorId, jobId, NOW);
        verify(scheduleRepository, never()).markPublished(any(), any(), any());
        verify(scheduleRepository, never()).abandonPending(any(), any(), any());

        // Immediate next tick can re-lease the same pending_job_id (lease released, not abandoned).
        when(scheduleRepository.leasePendingPublishes(eq(NOW), any(), eq(50))).thenReturn(List.of(due));
        doReturn("sqs-after-creds").when(jobPublisher).publish(any());
        when(scheduleRepository.markPublished(monitorId, jobId, NOW)).thenReturn(true);

        MonitorSchedulingService.TickResult second = service.runOnce();

        assertThat(second.published()).isEqualTo(1);
        ArgumentCaptor<MonitoringJob> jobCaptor = ArgumentCaptor.forClass(MonitoringJob.class);
        verify(jobPublisher, times(2)).publish(jobCaptor.capture());
        assertThat(jobCaptor.getAllValues())
                .extracting(MonitoringJob::getJobId)
                .containsExactly(jobId, jobId);
    }

    @Test
    void runOnce_recoversExpiredLeaseWithoutReclaimingNewJobId() {
        // After lease expiry the repository returns the same pending row; service must
        // publish that jobId (at-least-once) and must not require a new claimDue.
        UUID monitorId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID jobId = UUID.fromString("2d862e57-8927-4607-a22d-a1cca26ee87b");
        DueMonitorJob pending = new DueMonitorJob(
                monitorId, jobId, "https://httpbin.org/status/200", NOW, 60);

        when(jobPublisher.isConfigured()).thenReturn(true);
        when(scheduleRepository.ensureSchedules(NOW)).thenReturn(0);
        when(scheduleRepository.claimDueMonitors(NOW, 50)).thenReturn(List.of()); // next_run_at already advanced
        when(scheduleRepository.leasePendingPublishes(eq(NOW), any(), eq(50))).thenReturn(List.of(pending));
        when(jobPublisher.publish(any())).thenReturn("sqs-recovered");
        when(scheduleRepository.markPublished(monitorId, jobId, NOW)).thenReturn(true);

        MonitorSchedulingService.TickResult result = service.runOnce();

        assertThat(result.claimed()).isZero();
        assertThat(result.leased()).isEqualTo(1);
        assertThat(result.published()).isEqualTo(1);
        verify(jobPublisher).publish(argThat(job -> jobId.equals(job.getJobId())));
        verify(scheduleRepository, never()).releasePublishLease(any(), any(), any());
    }

    @Test
    void runOnce_abandonsUnapprovedPendingUrlWithoutPublishing() {
        UUID monitorId = UUID.fromString("77777777-7777-7777-7777-777777777777");
        UUID jobId = UUID.fromString("88888888-8888-8888-8888-888888888888");
        DueMonitorJob due = new DueMonitorJob(
                monitorId, jobId, "http://127.0.0.1/", NOW, 60);

        when(jobPublisher.isConfigured()).thenReturn(true);
        when(scheduleRepository.ensureSchedules(NOW)).thenReturn(0);
        when(scheduleRepository.claimDueMonitors(NOW, 50)).thenReturn(List.of());
        when(scheduleRepository.leasePendingPublishes(eq(NOW), any(), eq(50))).thenReturn(List.of(due));
        when(scheduleRepository.abandonPending(monitorId, jobId, NOW)).thenReturn(true);

        MonitorSchedulingService.TickResult result = service.runOnce();

        assertThat(result.skippedUnapproved()).isEqualTo(1);
        assertThat(result.published()).isZero();
        verify(jobPublisher, never()).publish(any());
        verify(scheduleRepository).abandonPending(monitorId, jobId, NOW);
        verify(scheduleRepository, never()).markPublished(any(), any(), any());
    }

    @Test
    void runOnce_publishesGoogleAllowlistedUrl_regressionWaitingMonitor() {
        // Production: e6c1c8c5-0d7e-4910-88e6-8bb6894e7434 stayed Waiting because
        // www.google.com was claimed then abandoned as unapproved.
        UUID monitorId = UUID.fromString("e6c1c8c5-0d7e-4910-88e6-8bb6894e7434");
        UUID jobId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        DueMonitorJob due = new DueMonitorJob(
                monitorId, jobId, "https://www.google.com", NOW, 60);

        when(jobPublisher.isConfigured()).thenReturn(true);
        when(scheduleRepository.ensureSchedules(NOW)).thenReturn(0);
        when(scheduleRepository.claimDueMonitors(NOW, 50)).thenReturn(List.of(due));
        when(scheduleRepository.leasePendingPublishes(eq(NOW), any(), eq(50))).thenReturn(List.of(due));
        when(jobPublisher.publish(any())).thenReturn("sqs-google");
        when(scheduleRepository.markPublished(monitorId, jobId, NOW)).thenReturn(true);

        MonitorSchedulingService.TickResult result = service.runOnce();

        assertThat(result.published()).isEqualTo(1);
        assertThat(result.skippedUnapproved()).isZero();
        verify(jobPublisher).publish(argThat(job ->
                monitorId.equals(job.getMonitorId())
                        && "https://www.google.com".equals(job.getUrl())));
        verify(scheduleRepository, never()).abandonPending(any(), any(), any());
    }
}
