package com.tradingbot.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@SpringBootTest
public class TaskSchedulingConfigTest {

    @Autowired private ThreadPoolTaskScheduler taskScheduler;

    @Test
    void testTaskSchedulerPoolSize() {
        assertNotNull(taskScheduler);
        assertEquals(5, taskScheduler.getPoolSize());
        assertEquals("trading-task-", taskScheduler.getThreadNamePrefix());
    }
}
