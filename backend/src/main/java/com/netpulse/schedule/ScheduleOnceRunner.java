package com.netpulse.schedule;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * One-shot enqueue for free-tier / GitHub Actions: claim due monitors, publish to SQS, exit.
 * Enable with {@code NETPULSE_SCHEDULE_ONCE=true} and prefer
 * {@code SPRING_MAIN_WEB_APPLICATION_TYPE=none} so no HTTP server starts.
 * Keep {@code NETPULSE_SCHEDULER_ENABLED=false} to avoid a continuous ticker.
 */
@Component
@Order(0)
@ConditionalOnProperty(name = "netpulse.schedule.once", havingValue = "true")
public class ScheduleOnceRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScheduleOnceRunner.class);

    private final MonitorSchedulingService schedulingService;
    private final ConfigurableApplicationContext context;

    public ScheduleOnceRunner(MonitorSchedulingService schedulingService,
                              ConfigurableApplicationContext context) {
        this.schedulingService = schedulingService;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        MonitorSchedulingService.TickResult result = schedulingService.runOnce();
        int code = exitCodeFor(result);
        log.info(
                "Schedule-once finished exitCode={} ensured={} claimed={} leased={} published={} failed={} skippedUnapproved={}",
                code,
                result.ensured(),
                result.claimed(),
                result.leased(),
                result.published(),
                result.failed(),
                result.skippedUnapproved());
        System.exit(SpringApplication.exit(context, () -> code));
    }

    /**
     * 0 = tick completed (including idle / no due monitors).
     * 2 = misconfiguration (e.g. SQS not configured).
     * 1 = one or more publish failures.
     */
    static int exitCodeFor(MonitorSchedulingService.TickResult result) {
        if (result.wasSkipped() && result.errors().contains("sqs_not_configured")) {
            return 2;
        }
        if (result.failed() > 0) {
            return 1;
        }
        return 0;
    }
}
