package com.telestock.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Application configuration defining core infrastructure beans.
 * Configures a multi-threaded TaskScheduler to prevent scheduled task starvation.
 */
@Configuration
@Slf4j
public class AppConfig {

    /**
     * Dedicated TaskScheduler with 4 worker threads for scheduled operations:
     * - Thread 1: LiveMarketDataService.pollMarketData (every 2s)
     * - Thread 2: StrategyEngine.evaluateStrategies (every 10s)
     * - Thread 3: DashboardController.broadcastPrices (every 2s)
     * - Thread 4: NseSymbolDiscoveryService.refreshSymbols / background tasks
     */
    @Bean(name = "taskScheduler")
    public ThreadPoolTaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("tele-scheduled-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(5);
        scheduler.setErrorHandler(throwable ->
            log.error("Unhandled exception in scheduled task: {}", throwable.getMessage(), throwable)
        );
        scheduler.initialize();
        return scheduler;
    }
}
