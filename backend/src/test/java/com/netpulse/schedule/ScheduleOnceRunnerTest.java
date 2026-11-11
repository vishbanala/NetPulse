package com.netpulse.schedule;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class ScheduleOnceRunnerTest {

    @Test
    void exitCodeZeroForSuccessfulOrIdleTick() {
        assertThat(ScheduleOnceRunner.exitCodeFor(
                new MonitorSchedulingService.TickResult(1, 0, 0, 0, 0, 0, List.of())))
                .isZero();
        assertThat(ScheduleOnceRunner.exitCodeFor(
                new MonitorSchedulingService.TickResult(0, 1, 1, 1, 0, 0, List.of())))
                .isZero();
    }

    @Test
    void exitCodeTwoWhenSqsNotConfigured() {
        assertThat(ScheduleOnceRunner.exitCodeFor(
                MonitorSchedulingService.TickResult.skipped("sqs_not_configured")))
                .isEqualTo(2);
    }

    @Test
    void exitCodeOneWhenPublishFailures() {
        assertThat(ScheduleOnceRunner.exitCodeFor(
                new MonitorSchedulingService.TickResult(0, 1, 1, 0, 2, 0, List.of("x:SqsException"))))
                .isEqualTo(1);
    }
}
