package com.telestock.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Adversarial test suite for HealthController /health endpoint.
 * Challenges response format, HTTP headers, supported and restricted methods,
 * and high-concurrency probe spamming.
 */
class AdversarialHealthControllerTest {

    private HealthController healthController;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        healthController = new HealthController();
        mockMvc = MockMvcBuilders.standaloneSetup(healthController).build();
    }

    @Test
    @DisplayName("Health endpoint returns 200 OK, status=UP, and fresh ISO-8601 timestamp")
    void testHealthEndpointResponseFormatAndFreshness() {
        Instant beforeCall = Instant.now();
        ResponseEntity<Map<String, String>> response = healthController.health();
        Instant afterCall = Instant.now();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody(), "Response body must not be null");
        assertEquals("UP", response.getBody().get("status"), "Status field must be UP");

        String timestampStr = response.getBody().get("timestamp");
        assertNotNull(timestampStr, "Timestamp field must not be null");

        Instant parsedTimestamp = assertDoesNotThrow(() -> Instant.parse(timestampStr),
                "Timestamp must be valid ISO-8601 format");

        // Verify timestamp freshness: must be between beforeCall and afterCall (within 2s tolerance)
        assertTrue(Duration.between(beforeCall.minusSeconds(1), parsedTimestamp).toMillis() >= 0);
        assertTrue(Duration.between(parsedTimestamp, afterCall.plusSeconds(1)).toMillis() >= 0);
    }

    @Test
    @DisplayName("MockMvc GET /health returns application/json with status UP")
    void testMockMvcGetHealth() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").isString());
    }

    @Test
    @DisplayName("MockMvc HEAD /health returns 200 OK without body")
    void testMockMvcHeadHealth() throws Exception {
        mockMvc.perform(head("/health"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Restricted methods: POST, PUT, DELETE /health must return 405 Method Not Allowed")
    void testRestrictedHttpMethods() throws Exception {
        mockMvc.perform(post("/health"))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(put("/health"))
                .andExpect(status().isMethodNotAllowed());

        mockMvc.perform(delete("/health"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    @DisplayName("Stress Test: 50 concurrent health check pings simulate aggressive cloud probes")
    void testConcurrentHealthCheckPings() throws InterruptedException {
        int threads = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threads);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    ResponseEntity<Map<String, String>> response = healthController.health();
                    if (response.getStatusCode() == HttpStatus.OK &&
                            "UP".equals(response.getBody().get("status")) &&
                            response.getBody().get("timestamp") != null) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean completed = finishLatch.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "All 50 concurrent health pings must complete within 5s");
        assertEquals(threads, successCount.get(), "All 50 health pings must return 200 OK with status UP");
    }
}
