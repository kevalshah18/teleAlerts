package com.telestock.controller;

import com.telestock.config.ConfigService;
import com.telestock.feed.LiveMarketDataService;
import com.telestock.model.MarketData;
import com.telestock.model.Position;
import com.telestock.model.SystemConfig;
import com.telestock.model.TradeRecord;
import com.telestock.repository.PositionRepository;
import com.telestock.repository.TradeRecordRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class DashboardController {
    private final ConfigService configService;
    private final LiveMarketDataService marketDataService;
    private final PositionRepository positionRepository;
    private final TradeRecordRepository tradeRecordRepository;

    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    @GetMapping("/config")
    public SystemConfig getConfig() {
        return configService.getConfig();
    }

    @PostMapping("/config")
    public SystemConfig updateConfig(@RequestBody SystemConfig config) {
        return configService.updateConfig(config);
    }
    
    /**
     * Subscribes a client to real-time price updates via Server-Sent Events (SSE).
     * Non-blocking registration into a centralized emitter registry.
     */
    @GetMapping(value = "/stream/prices", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPrices() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);

        emitter.onCompletion(() -> {
            log.debug("SSE client completed connection");
            emitters.remove(emitter);
        });
        emitter.onTimeout(() -> {
            log.debug("SSE client timed out");
            emitters.remove(emitter);
            emitter.complete();
        });
        emitter.onError(e -> {
            log.debug("SSE client connection error: {}", e.getMessage());
            emitters.remove(emitter);
        });

        // Send initial market data snapshot immediately on connection
        try {
            Map<String, MarketData> latestData = marketDataService.getAllLatestData();
            if (latestData != null && !latestData.isEmpty()) {
                emitter.send(latestData, MediaType.APPLICATION_JSON);
            }
        } catch (Exception e) {
            log.warn("Failed to deliver initial SSE snapshot to client: {}", e.getMessage());
            emitters.remove(emitter);
            emitter.completeWithError(e);
        }

        return emitter;
    }

    /**
     * Broadcasts latest price data to all connected SSE clients every 2 seconds.
     * Runs on the shared 'tele-scheduled-' task scheduler.
     * Automatically evicts dead or disconnected client emitters.
     */
    @Scheduled(fixedRate = 2000)
    public void broadcastPrices() {
        if (emitters.isEmpty()) {
            return;
        }

        Map<String, MarketData> latestData = marketDataService.getAllLatestData();
        if (latestData == null || latestData.isEmpty()) {
            return;
        }

        List<SseEmitter> deadEmitters = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(latestData, MediaType.APPLICATION_JSON);
            } catch (Exception e) {
                deadEmitters.add(emitter);
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignored) {
                }
            }
        }

        if (!deadEmitters.isEmpty()) {
            emitters.removeAll(deadEmitters);
            log.debug("Removed {} disconnected SSE emitters; active: {}", deadEmitters.size(), emitters.size());
        }
    }

    /**
     * Completes all active SSE connections on application shutdown.
     */
    @PreDestroy
    public void shutdown() {
        log.info("Closing {} active SSE connections on shutdown", emitters.size());
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (Exception ignored) {
            }
        }
        emitters.clear();
    }
    
    @GetMapping("/positions")
    public List<Position> getPositions() {
        return positionRepository.findAll();
    }
    
    @GetMapping("/history")
    public List<TradeRecord> getHistory() {
        return tradeRecordRepository.findAll();
    }

    public int getActiveEmitterCount() {
        return emitters.size();
    }
}
