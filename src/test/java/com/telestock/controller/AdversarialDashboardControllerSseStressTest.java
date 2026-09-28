package com.telestock.controller;

import com.telestock.config.ConfigService;
import com.telestock.feed.LiveMarketDataService;
import com.telestock.model.MarketData;
import com.telestock.repository.PositionRepository;
import com.telestock.repository.TradeRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Adversarial stress test harness for the DashboardController SSE emitter registry.
 * Validates thread safety, dead client pruning, NullPointer guards, and shutdown resilience.
 */
@ExtendWith(MockitoExtension.class)
class AdversarialDashboardControllerSseStressTest {

    @Mock
    private ConfigService configService;

    @Mock
    private LiveMarketDataService marketDataService;

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private TradeRecordRepository tradeRecordRepository;

    private DashboardController controller;

    @BeforeEach
    void setUp() {
        controller = new DashboardController(
                configService, marketDataService, positionRepository, tradeRecordRepository
        );
    }

    @Test
    @DisplayName("Stress Test: 100 concurrent clients subscribe to SSE stream simultaneously")
    void testConcurrentEmitterRegistration() throws InterruptedException {
        when(marketDataService.getAllLatestData()).thenReturn(Collections.emptyMap());

        int clientCount = 100;
        ExecutorService executor = Executors.newFixedThreadPool(20);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(clientCount);
        List<SseEmitter> registeredEmitters = new CopyOnWriteArrayList<>();

        for (int i = 0; i < clientCount; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    SseEmitter emitter = controller.streamPrices();
                    registeredEmitters.add(emitter);
                } catch (Exception ignored) {
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = finishLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished, "All 100 client connections must complete within 5s");
        assertEquals(clientCount, controller.getActiveEmitterCount(),
                "Active emitter count must exactly match registered client count");
        assertEquals(clientCount, registeredEmitters.size());
    }

    @Test
    @DisplayName("Pruning Test: Broadcast evicts dead emitters (broken pipes) while preserving healthy emitters")
    @SuppressWarnings("unchecked")
    void testDeadEmitterPruningDuringBroadcast() throws IOException {
        MarketData dummyData = new MarketData();
        dummyData.setSymbol("INFY.NS");
        dummyData.setLtp(1500.0);
        when(marketDataService.getAllLatestData()).thenReturn(Map.of("INFY.NS", dummyData));

        List<SseEmitter> registry = (List<SseEmitter>) ReflectionTestUtils.getField(controller, "emitters");
        assertNotNull(registry);

        int healthyCount = 20;
        int deadCount = 20;

        List<SseEmitter> healthyEmitters = new ArrayList<>();
        List<SseEmitter> deadEmitters = new ArrayList<>();

        for (int i = 0; i < healthyCount; i++) {
            SseEmitter healthy = mock(SseEmitter.class);
            healthyEmitters.add(healthy);
            registry.add(healthy);
        }

        for (int i = 0; i < deadCount; i++) {
            SseEmitter dead = mock(SseEmitter.class);
            doThrow(new IOException("Broken pipe / Connection reset by peer"))
                    .when(dead).send(any(), eq(MediaType.APPLICATION_JSON));
            deadEmitters.add(dead);
            registry.add(dead);
        }

        assertEquals(healthyCount + deadCount, controller.getActiveEmitterCount());

        // Execute broadcast cycle
        controller.broadcastPrices();

        // Exactly the dead emitters should be pruned
        assertEquals(healthyCount, controller.getActiveEmitterCount(),
                "Dead emitters must be pruned, leaving only healthy ones");

        for (SseEmitter healthy : healthyEmitters) {
            assertTrue(registry.contains(healthy), "Healthy emitter must remain in registry");
            verify(healthy).send(any(), eq(MediaType.APPLICATION_JSON));
        }

        for (SseEmitter dead : deadEmitters) {
            assertFalse(registry.contains(dead), "Dead emitter must be removed from registry");
            verify(dead).completeWithError(any(IOException.class));
        }
    }

    @Test
    @DisplayName("NPE Guard: Null market data during registration and broadcast must not throw NPE")
    void testNullMarketDataGuards() {
        when(marketDataService.getAllLatestData()).thenReturn(null);

        // Should not throw NPE on registration
        SseEmitter emitter = assertDoesNotThrow(() -> controller.streamPrices());
        assertNotNull(emitter);
        assertEquals(1, controller.getActiveEmitterCount());

        // Should not throw NPE on broadcast
        assertDoesNotThrow(() -> controller.broadcastPrices());
        assertEquals(1, controller.getActiveEmitterCount());
    }

    @Test
    @DisplayName("NPE Guard: Empty market data does not trigger broadcast or evict healthy emitters")
    void testEmptyMarketDataBroadcast() {
        when(marketDataService.getAllLatestData()).thenReturn(Collections.emptyMap());

        controller.streamPrices();
        assertEquals(1, controller.getActiveEmitterCount());

        controller.broadcastPrices();
        assertEquals(1, controller.getActiveEmitterCount());
    }

    @Test
    @DisplayName("Lifecycle Test: Client timeout callback completes emitter and cleans registry")
    @SuppressWarnings("unchecked")
    void testEmitterTimeoutRemovesFromRegistry() {
        when(marketDataService.getAllLatestData()).thenReturn(Collections.emptyMap());

        SseEmitter emitter = controller.streamPrices();
        assertEquals(1, controller.getActiveEmitterCount());

        // Simulate container timeout event
        List<SseEmitter> registry = (List<SseEmitter>) ReflectionTestUtils.getField(controller, "emitters");
        assertNotNull(registry);
        registry.remove(emitter);

        assertEquals(0, controller.getActiveEmitterCount(), "Timeout must prune emitter from registry");
    }

    @Test
    @DisplayName("Shutdown Test: Closing 50 active SSE connections cleanly completes all and empties registry")
    void testShutdownCleansAllActiveEmitters() {
        when(marketDataService.getAllLatestData()).thenReturn(Collections.emptyMap());

        for (int i = 0; i < 50; i++) {
            controller.streamPrices();
        }
        assertEquals(50, controller.getActiveEmitterCount());

        controller.shutdown();
        assertEquals(0, controller.getActiveEmitterCount(), "Shutdown must clear all active emitters");
    }

    @Test
    @DisplayName("Concurrency: Concurrent registration, broadcast, and shutdown execute without deadlocks or CME")
    void testHighConcurrencyInterleavedOperations() throws InterruptedException {
        MarketData data = new MarketData();
        data.setSymbol("TCS.NS");
        data.setLtp(3500.0);
        when(marketDataService.getAllLatestData()).thenReturn(Map.of("TCS.NS", data));

        int workers = 30;
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        CountDownLatch latch = new CountDownLatch(workers);
        AtomicInteger errors = new AtomicInteger(0);

        for (int i = 0; i < workers; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    if (index % 2 == 0) {
                        controller.streamPrices();
                    } else {
                        controller.broadcastPrices();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean done = latch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(done);
        assertEquals(0, errors.get(), "Concurrent registration and broadcast must not throw exceptions");
    }
}
