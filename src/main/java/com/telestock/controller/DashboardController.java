package com.telestock.controller;

import com.telestock.config.ConfigService;
import com.telestock.feed.LiveMarketDataService;
import com.telestock.model.MarketData;
import com.telestock.model.Position;
import com.telestock.model.SystemConfig;
import com.telestock.model.TradeRecord;
import com.telestock.repository.PositionRepository;
import com.telestock.repository.TradeRecordRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DashboardController {
    private final ConfigService configService;
    private final LiveMarketDataService marketDataService;
    private final PositionRepository positionRepository;
    private final TradeRecordRepository tradeRecordRepository;

    @GetMapping("/config")
    public SystemConfig getConfig() {
        return configService.getConfig();
    }

    @PostMapping("/config")
    public SystemConfig updateConfig(@RequestBody SystemConfig config) {
        return configService.updateConfig(config);
    }
    
    @GetMapping(value = "/stream/prices", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPrices() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                while (true) {
                    emitter.send(marketDataService.getAllLatestData(), MediaType.APPLICATION_JSON);
                    Thread.sleep(2000);
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
    
    @GetMapping("/positions")
    public List<Position> getPositions() {
        return positionRepository.findAll();
    }
    
    @GetMapping("/history")
    public List<TradeRecord> getHistory() {
        return tradeRecordRepository.findAll();
    }
}
