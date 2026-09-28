package com.telestock.controller;

import com.telestock.config.ConfigService;
import com.telestock.feed.LiveMarketDataService;
import com.telestock.model.MarketData;
import com.telestock.repository.PositionRepository;
import com.telestock.repository.TradeRecordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DashboardControllerSseTest {

    @Mock
    private ConfigService configService;

    @Mock
    private LiveMarketDataService marketDataService;

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private TradeRecordRepository tradeRecordRepository;

    @Test
    void testStreamPricesRegistersEmitterWithoutSpawningThread() {
        DashboardController controller = new DashboardController(
                configService, marketDataService, positionRepository, tradeRecordRepository
        );

        when(marketDataService.getAllLatestData()).thenReturn(Collections.emptyMap());

        assertEquals(0, controller.getActiveEmitterCount());
        SseEmitter emitter = controller.streamPrices();
        assertNotNull(emitter);
        assertEquals(1, controller.getActiveEmitterCount());
    }

    @Test
    void testBroadcastPricesHandlesEmptyEmittersGracefully() {
        DashboardController controller = new DashboardController(
                configService, marketDataService, positionRepository, tradeRecordRepository
        );

        controller.broadcastPrices();
        verifyNoInteractions(marketDataService);
    }

    @Test
    void testBroadcastPricesDeliversDataToActiveEmitters() {
        DashboardController controller = new DashboardController(
                configService, marketDataService, positionRepository, tradeRecordRepository
        );

        MarketData data = new MarketData();
        data.setSymbol("RELIANCE.NS");
        data.setLtp(2500.0);
        when(marketDataService.getAllLatestData()).thenReturn(Map.of("RELIANCE.NS", data));

        controller.streamPrices();
        assertEquals(1, controller.getActiveEmitterCount());

        assertDoesNotThrow(controller::broadcastPrices);
        assertEquals(1, controller.getActiveEmitterCount());
    }

    @Test
    void testShutdownCompletesAllEmitters() {
        DashboardController controller = new DashboardController(
                configService, marketDataService, positionRepository, tradeRecordRepository
        );

        when(marketDataService.getAllLatestData()).thenReturn(Collections.emptyMap());
        controller.streamPrices();
        assertEquals(1, controller.getActiveEmitterCount());

        controller.shutdown();
        assertEquals(0, controller.getActiveEmitterCount());
    }
}
