package com.telestock.ledger;

import com.telestock.config.ConfigService;
import com.telestock.model.Position;
import com.telestock.model.SystemConfig;
import com.telestock.model.TradeRecord;
import com.telestock.repository.PositionRepository;
import com.telestock.repository.TradeRecordRepository;
import com.telestock.telegram.TelegramService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private TradeRecordRepository tradeRecordRepository;

    @Mock
    private TelegramService telegramService;

    @Mock
    private ConfigService configService;

    @InjectMocks
    private LedgerService ledgerService;

    @Test
    void testExecuteSellCalculatesChargesCorrectly() {
        // Arrange
        Position position = new Position();
        position.setSymbol("TATASTEEL.NS");
        position.setQuantity(100);
        position.setEntryPrice(100.0);
        position.setEntryTime(LocalDateTime.now());
        position.setGeminiReasoning("Strong momentum");

        SystemConfig config = new SystemConfig();
        config.setAvailableCapital(10000.0);
        when(configService.getConfig()).thenReturn(config);

        double exitPrice = 104.0;

        // Act
        ledgerService.executeSell(position, exitPrice);

        // Assert
        ArgumentCaptor<TradeRecord> recordCaptor = ArgumentCaptor.forClass(TradeRecord.class);
        verify(tradeRecordRepository).save(recordCaptor.capture());
        TradeRecord record = recordCaptor.getValue();

        double buyValue = 10000.0;
        double sellValue = 10400.0;
        double expectedGrossPnl = 400.0;

        double expBrokerageBuy = Math.min(20.0, buyValue * 0.0003); // 3.0
        double expBrokerageSell = Math.min(20.0, sellValue * 0.0003); // 3.12
        double expTotalBrokerage = expBrokerageBuy + expBrokerageSell; // 6.12

        double expStt = (buyValue + sellValue) * 0.001; // 20400 * 0.001 = 20.4
        double expExchange = (buyValue + sellValue) * 0.0000345; // 20400 * 0.0000345 = 0.7038
        double expSebi = (buyValue + sellValue) * 0.000001; // 20400 * 0.000001 = 0.0204
        double expStamp = buyValue * 0.00015; // 10000 * 0.00015 = 1.5
        double expGst = (expTotalBrokerage + expExchange + expSebi) * 0.18; // (6.12 + 0.7038 + 0.0204) * 0.18 = 1.231956

        double expTotalCharges = expTotalBrokerage + expStt + expExchange + expSebi + expStamp + expGst;
        double expNetPnl = expectedGrossPnl - expTotalCharges;

        assertEquals(expectedGrossPnl, record.getGrossPnl(), 0.0001);
        assertEquals(expTotalBrokerage, record.getBrokerage(), 0.0001);
        assertEquals(expStt, record.getStt(), 0.0001);
        assertEquals(expExchange, record.getExchangeTurnoverCharge(), 0.0001);
        assertEquals(expSebi, record.getSebiCharges(), 0.0001);
        assertEquals(expStamp, record.getStampDuty(), 0.0001);
        assertEquals(expGst, record.getGst(), 0.0001);
        assertEquals(expNetPnl, record.getNetPnl(), 0.0001);

        // Verify capital restoration (buyValue + netPnl)
        double returnedCapital = buyValue + expNetPnl;
        assertEquals(10000.0 + returnedCapital, config.getAvailableCapital(), 0.0001);
        verify(configService).updateConfig(config);

        // Verify position cleanup
        verify(positionRepository).delete(position);

        // Verify notification
        verify(telegramService).sendMessage(anyString());
    }

    @Test
    void testExecuteSellLossCalculatesChargesAndRestoresCapital() {
        // Arrange
        Position position = new Position();
        position.setSymbol("TATASTEEL.NS");
        position.setQuantity(100);
        position.setEntryPrice(100.0);
        position.setEntryTime(LocalDateTime.now());
        position.setGeminiReasoning("Stop loss hit");

        SystemConfig config = new SystemConfig();
        config.setAvailableCapital(5000.0);
        when(configService.getConfig()).thenReturn(config);

        double exitPrice = 98.5; // -1.5% stop loss

        // Act
        ledgerService.executeSell(position, exitPrice);

        // Assert
        ArgumentCaptor<TradeRecord> recordCaptor = ArgumentCaptor.forClass(TradeRecord.class);
        verify(tradeRecordRepository).save(recordCaptor.capture());
        TradeRecord record = recordCaptor.getValue();

        double buyValue = 10000.0;
        double sellValue = 9850.0;
        double expectedGrossPnl = -150.0;

        double expBrokerageBuy = Math.min(20.0, buyValue * 0.0003); // 3.0
        double expBrokerageSell = Math.min(20.0, sellValue * 0.0003); // 2.955
        double expTotalBrokerage = expBrokerageBuy + expBrokerageSell; // 5.955

        double expStt = (buyValue + sellValue) * 0.001; // 19.85
        double expExchange = (buyValue + sellValue) * 0.0000345; // 0.684825
        double expSebi = (buyValue + sellValue) * 0.000001; // 0.01985
        double expStamp = buyValue * 0.00015; // 1.5
        double expGst = (expTotalBrokerage + expExchange + expSebi) * 0.18; // 1.1987415

        double expTotalCharges = expTotalBrokerage + expStt + expExchange + expSebi + expStamp + expGst;
        double expNetPnl = expectedGrossPnl - expTotalCharges; // -179.2084165

        assertEquals(expectedGrossPnl, record.getGrossPnl(), 0.0001);
        assertEquals(expNetPnl, record.getNetPnl(), 0.0001);

        // Capital returned = buyValue + netPnl = 10000 + (-179.2084165) = 9820.7915835
        double returnedCapital = buyValue + expNetPnl;
        assertEquals(5000.0 + returnedCapital, config.getAvailableCapital(), 0.0001);
        verify(configService).updateConfig(config);
        verify(positionRepository).delete(position);
        verify(telegramService).sendMessage(anyString());
    }

    @Test
    void testExecuteBuyWithSufficientCapital() {
        // Arrange
        SystemConfig config = new SystemConfig();
        config.setAvailableCapital(10000.0);
        when(configService.getConfig()).thenReturn(config);

        String symbol = "RELIANCE.NS";
        double price = 2500.0;
        int quantity = 2;
        String reasoning = "Bullish EMA crossover and RSI confirmation";

        // Act
        ledgerService.executeBuy(symbol, price, quantity, reasoning);

        // Assert
        // 1. Capital deduction
        double expectedBuyValue = price * quantity; // 5000.0
        assertEquals(10000.0 - expectedBuyValue, config.getAvailableCapital(), 0.0001);
        verify(configService).updateConfig(config);

        // 2. Position persisted
        ArgumentCaptor<Position> positionCaptor = ArgumentCaptor.forClass(Position.class);
        verify(positionRepository).save(positionCaptor.capture());
        Position savedPosition = positionCaptor.getValue();

        assertEquals(symbol, savedPosition.getSymbol());
        assertEquals(quantity, savedPosition.getQuantity());
        assertEquals(price, savedPosition.getEntryPrice(), 0.0001);
        assertEquals(price * 0.985, savedPosition.getStopLoss(), 0.0001); // -1.5% SL
        assertEquals(price * 1.03, savedPosition.getTarget(), 0.0001);   // +3.0% Target
        assertNotNull(savedPosition.getEntryTime());
        assertEquals(reasoning, savedPosition.getGeminiReasoning());

        // 3. Telegram notification sent
        ArgumentCaptor<String> msgCaptor = ArgumentCaptor.forClass(String.class);
        verify(telegramService).sendMessage(msgCaptor.capture());
        assertTrue(msgCaptor.getValue().contains("BUY Executed"));
        assertTrue(msgCaptor.getValue().contains(symbol));
    }

    @Test
    void testExecuteBuyWithInsufficientCapital() {
        // Arrange
        SystemConfig config = new SystemConfig();
        config.setAvailableCapital(1000.0);
        when(configService.getConfig()).thenReturn(config);

        String symbol = "TCS.NS";
        double price = 3500.0;
        int quantity = 1;
        String reasoning = "Bullish breakout";

        // Act
        ledgerService.executeBuy(symbol, price, quantity, reasoning);

        // Assert
        // Should not deduct capital
        assertEquals(1000.0, config.getAvailableCapital(), 0.0001);
        verify(configService, never()).updateConfig(any());

        // Should not save position
        verify(positionRepository, never()).save(any());

        // Should not send telegram alert
        verify(telegramService, never()).sendMessage(anyString());
    }

    @Test
    void testExecuteBuyWithExactCapital() {
        // Arrange
        SystemConfig config = new SystemConfig();
        config.setAvailableCapital(5000.0);
        when(configService.getConfig()).thenReturn(config);

        String symbol = "INFY.NS";
        double price = 1000.0;
        int quantity = 5;
        String reasoning = "Exact balance purchase";

        // Act
        ledgerService.executeBuy(symbol, price, quantity, reasoning);

        // Assert
        assertEquals(0.0, config.getAvailableCapital(), 0.0001);
        verify(configService).updateConfig(config);
        verify(positionRepository).save(any(Position.class));
        verify(telegramService).sendMessage(anyString());
    }
}
