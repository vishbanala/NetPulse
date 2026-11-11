package com.netpulse.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class MonitorStatsCalculatorTest {

    @Test
    void uptimePercentage_returnsNullWhenNoChecks() {
        assertThat(MonitorStatsCalculator.uptimePercentage(0, 0)).isNull();
        assertThat(MonitorStatsCalculator.uptimePercentage(-1, 0)).isNull();
    }

    @Test
    void uptimePercentage_computesRoundedPercentage() {
        assertThat(MonitorStatsCalculator.uptimePercentage(100, 100)).isEqualTo(100.0);
        assertThat(MonitorStatsCalculator.uptimePercentage(100, 0)).isEqualTo(0.0);
        assertThat(MonitorStatsCalculator.uptimePercentage(3, 2)).isEqualTo(66.67);
        assertThat(MonitorStatsCalculator.uptimePercentage(2, 1)).isEqualTo(50.0);
    }

    @Test
    void uptimePercentage_rejectsInconsistentSuccessfulCount() {
        assertThatThrownBy(() -> MonitorStatsCalculator.uptimePercentage(10, 11))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MonitorStatsCalculator.uptimePercentage(10, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
