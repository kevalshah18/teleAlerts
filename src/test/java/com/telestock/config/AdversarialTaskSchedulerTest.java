package com.telestock.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial test harness for TaskScheduler bean in AppConfig.
 * Stress-tests error-handling resilience, multi-thread concurrency,
 * thread naming conventions, and shutdown timeout behavior.
 */
class AdversarialTaskSchedulerTest {

    private AppConfig appConfig;
    private ThreadPoolTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        appConfig = new AppConfig();
        scheduler = appConfig.taskScheduler();
    }

    @AfterEach
    void tearDown() {
        if (scheduler != null) {
            scheduler.destroy();
        }
    }

    @Test
    @DisplayName("TaskScheduler pool configuration: poolSize=4, prefix=tele-scheduled-")
    void testTaskSchedulerBaseProperties() {
        assertNotNull(scheduler);
        assertEquals(4, scheduler.getPoolSize(), "Scheduler must have pool size of 4");
        assertEquals("tele-scheduled-", scheduler.getThreadNamePrefix(), "Thread prefix must match tele-scheduled-");
    }

    @Test
    @DisplayName("Error Handler Resilience: Unhandled RuntimeException in scheduled task does not crash the scheduler")
    void testErrorHandlerCatchesExceptionAndPermitsSubsequentTasks() throws Exception {
        CountDownLatch failureLatch = new CountDownLatch(1);
        CountDownLatch recoveryLatch = new CountDownLatch(1);
        AtomicInteger executedCount = new AtomicInteger(0);

        // Schedule a failing task
        scheduler.execute(() -> {
            executedCount.incrementAndGet();
            failureLatch.countDown();
            throw new RuntimeException("Adversarial simulated failure in scheduled task!");
        });

        assertTrue(failureLatch.await(3, TimeUnit.SECONDS), "Failing task must execute");

        // Schedule a subsequent task to prove thread pool remains functional and unblocked
        scheduler.execute(() -> {
            executedCount.incrementAndGet();
            recoveryLatch.countDown();
        });

        assertTrue(recoveryLatch.await(3, TimeUnit.SECONDS),
                "Subsequent task must execute cleanly despite prior unhandled exception");
        assertEquals(2, executedCount.get(), "Both tasks must have been executed by the scheduler");
    }

    @Test
    @DisplayName("Concurrency: Scheduler executes 4 concurrent tasks simultaneously across 4 distinct worker threads")
    void testFourConcurrentTasksOnDistinctThreads() throws InterruptedException {
        int taskCount = 4;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch runningLatch = new CountDownLatch(taskCount);
        CountDownLatch completeLatch = new CountDownLatch(taskCount);
        Set<String> threadNames = new ConcurrentSkipListSet<>();

        for (int i = 0; i < taskCount; i++) {
            scheduler.execute(() -> {
                threadNames.add(Thread.currentThread().getName());
                runningLatch.countDown();
                try {
                    startLatch.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    completeLatch.countDown();
                }
            });
        }

        // All 4 tasks must reach the running state concurrently
        boolean allStarted = runningLatch.await(5, TimeUnit.SECONDS);
        assertTrue(allStarted, "All 4 tasks must run simultaneously without thread starvation");
        assertEquals(4, threadNames.size(), "4 distinct worker threads must have been utilized");

        for (String name : threadNames) {
            assertTrue(name.startsWith("tele-scheduled-"),
                    "Thread name '" + name + "' must begin with prefix 'tele-scheduled-'");
        }

        // Release tasks
        startLatch.countDown();
        boolean allFinished = completeLatch.await(5, TimeUnit.SECONDS);
        assertTrue(allFinished, "All 4 tasks must complete cleanly");
    }

    @Test
    @DisplayName("Shutdown Timeout: Graceful shutdown waits for active tasks to complete within timeout")
    void testGracefulShutdownAwaitsTasks() throws InterruptedException {
        CountDownLatch taskStarted = new CountDownLatch(1);
        AtomicBoolean taskCompleted = new AtomicBoolean(false);

        scheduler.execute(() -> {
            taskStarted.countDown();
            try {
                Thread.sleep(300);
                taskCompleted.set(true);
            } catch (InterruptedException ignored) {
            }
        });

        assertTrue(taskStarted.await(2, TimeUnit.SECONDS));

        Instant beforeShutdown = Instant.now();
        scheduler.destroy();
        scheduler = null;
        Instant afterShutdown = Instant.now();

        assertTrue(taskCompleted.get(), "Task must have finished cleanly during graceful shutdown window");
        assertTrue(Duration.between(beforeShutdown, afterShutdown).toMillis() < 5500,
                "Shutdown must not exceed configured await termination seconds (5s)");
    }
}
