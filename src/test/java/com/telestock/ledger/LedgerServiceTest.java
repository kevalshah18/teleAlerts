package com.telestock.ledger;

import com.telestock.model.Position;
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
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LedgerServiceTest {

    @Mock
    private PositionRepository positionRepository;

    @Mock
    private TradeRecordRepository tradeRecordRepository;

    @Mock
    private TelegramService telegramService;

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
    }
}
