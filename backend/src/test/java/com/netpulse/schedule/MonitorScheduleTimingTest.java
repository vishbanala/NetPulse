package com.netpulse.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.netpulse.repository.MonitorScheduleRepository;

class MonitorScheduleTimingTest {

    @Test
    void nextRunAt_addsIntervalSecondsToClaimedInstant() {
        Instant claimedAt = Instant.parse("2026-11-10T21:00:00Z");

        assertThat(MonitorScheduleTiming.nextRunAt(claimedAt, 60))
                .isEqualTo(Instant.parse("2026-11-10T21:01:00Z"));
        assertThat(MonitorScheduleTiming.nextRunAt(claimedAt, 10))
                .isEqualTo(Instant.parse("2026-11-10T21:00:10Z"));
        assertThat(MonitorScheduleTiming.nextRunAt(claimedAt, 86400))
                .isEqualTo(Instant.parse("2026-11-11T21:00:00Z"));
    }

    @Test
    void nextRunAt_rejectsInvalidInputs() {
        Instant claimedAt = Instant.parse("2026-11-10T21:00:00Z");

        assertThatThrownBy(() -> MonitorScheduleTiming.nextRunAt(null, 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MonitorScheduleTiming.nextRunAt(claimedAt, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void claimDueSql_castsBaseTimestampBeforeIntervalArithmetic() {
        // Regression for:
        // ERROR: column "next_run_at" is of type timestamp with time zone
        // but expression is of type interval
        assertThat(MonitorScheduleRepository.CLAIM_DUE_SQL)
                .contains("next_run_at = CAST(? AS TIMESTAMPTZ)")
                .contains("+ (m.interval_seconds * INTERVAL '1 second')")
                .contains("FOR UPDATE OF s SKIP LOCKED")
                .doesNotContain("next_run_at = ? +");
    }

    @Test
    void leasePendingSql_reacquiresExpiredLeasesWithoutNextRunAtGate() {
        // Pending retries must remain selectable after publish_lease_until expires.
        // Filtering on next_run_at would strand jobs until the following interval.
        assertThat(MonitorScheduleRepository.LEASE_PENDING_SQL)
                .contains("s.pending_job_id IS NOT NULL")
                .contains("publish_lease_until IS NULL")
                .contains("publish_lease_until <=")
                .contains("FOR UPDATE OF s SKIP LOCKED")
                .doesNotContain("next_run_at")
                .doesNotContain("pending_job_id IS NULL");
    }
}
