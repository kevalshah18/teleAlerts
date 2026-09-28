package com.telestock.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.junit.jupiter.api.Assertions.*;

class AppConfigTest {

    private final AppConfig appConfig = new AppConfig();

    @Test
    void testTaskSchedulerConfiguration() {
        ThreadPoolTaskScheduler scheduler = appConfig.taskScheduler();
        assertNotNull(scheduler);
        assertEquals(4, scheduler.getPoolSize());
        assertEquals("tele-scheduled-", scheduler.getThreadNamePrefix());
        scheduler.destroy();
    }
}
