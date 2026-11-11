package com.netpulse.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin {@code @Scheduled} wrapper. Only registered when the scheduler is enabled.
 */
@Component
@ConditionalOnProperty(name = "netpulse.scheduler.enabled", havingValue = "true")
public class MonitorSchedulingTicker {

    private static final Logger log = LoggerFactory.getLogger(MonitorSchedulingTicker.class);

    private final MonitorSchedulingService schedulingService;

    public MonitorSchedulingTicker(MonitorSchedulingService schedulingService) {
        this.schedulingService = schedulingService;
    }

    @Scheduled(fixedDelayString = "${netpulse.scheduler.fixed-delay-ms:10000}")
    public void tick() {
        try {
            schedulingService.runOnce();
        } catch (RuntimeException ex) {
            log.error("Scheduler tick failed: {}", ex.getMessage(), ex);
        }
    }
}
