package com.netpulse;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Full context load requires a real DB URL in the environment.
 * Scheduler stays disabled so this test never publishes SQS messages.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "netpulse.scheduler.enabled=false"
})
@EnabledIfEnvironmentVariable(named = "NETPULSE_DB_URL", matches = ".+")
class NetPulseApplicationTests {

    @Test
    void contextLoads() {
    }
}
