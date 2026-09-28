package com.telestock.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class HealthControllerTest {

    private final HealthController healthController = new HealthController();

    @Test
    void testHealthEndpointReturnsUpAndTimestamp() {
        ResponseEntity<Map<String, String>> response = healthController.health();

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("UP", response.getBody().get("status"));
        
        String timestamp = response.getBody().get("timestamp");
        assertNotNull(timestamp);
        assertDoesNotThrow(() -> Instant.parse(timestamp));
    }
}
