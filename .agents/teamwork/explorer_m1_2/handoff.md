# Handoff Report: Milestone 1 - Unit Test Suite Fix (`LedgerServiceTest`)

## 1. Observation

### 1.1 Direct Source Observations

1. **`src/main/java/com/telestock/ledger/LedgerService.java`**:
   - Lines 18-23:
     ```java
     public class LedgerService {
         private final PositionRepository positionRepository;
         private final TradeRecordRepository tradeRecordRepository;
         private final TelegramService telegramService;
         private final ConfigService configService;
     ```
   - Lines 24-51 (`executeBuy`):
     ```java
     public void executeBuy(String symbol, double price, int quantity, String geminiReasoning) {
         SystemConfig config = configService.getConfig();
         double buyValue = price * quantity;
         if (config.getAvailableCapital() < buyValue) {
             log.warn("Insufficient capital to buy {} shares of {}. Needed: {}, Available: {}", quantity, symbol, buyValue, config.getAvailableCapital());
             return;
         }
         config.setAvailableCapital(config.getAvailableCapital() - buyValue);
         configService.updateConfig(config);
         ...
     ```
   - Lines 53-104 (`executeSell`):
     ```java
     public void executeSell(Position position, double exitPrice) {
         ...
         // Taxes & Charges Calculation
         double brokerageBuy = Math.min(20.0, buyValue * 0.0003);
         double brokerageSell = Math.min(20.0, sellValue * 0.0003);
         double totalBrokerage = brokerageBuy + brokerageSell;
         
         double stt = (buyValue + sellValue) * 0.001; // 0.1% on both sides for delivery
         double exchangeCharge = (buyValue + sellValue) * 0.0000345;
         double sebiCharge = (buyValue + sellValue) * 0.000001; // 10 per crore
         double stampDuty = buyValue * 0.00015;
         double gst = (totalBrokerage + exchangeCharge + sebiCharge) * 0.18;
         ...
         // Add capital back (buyValue invested + netPnl)
         SystemConfig config = configService.getConfig();
         double returnedCapital = buyValue + netPnl;
         config.setAvailableCapital(config.getAvailableCapital() + returnedCapital);
         configService.updateConfig(config);
     ```

2. **`src/test/java/com/telestock/ledger/LedgerServiceTest.java`**:
   - Lines 20-33:
     ```java
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
     ```
   - `ConfigService` is completely absent from the mock declarations.
   - Lines 35-78:
     `testExecuteSellCalculatesChargesCorrectly()` sets up `Position`, invokes `ledgerService.executeSell(position, exitPrice)`, and asserts on `TradeRecord` fields. It does not stub `configService.getConfig()`, nor does it assert capital restoration or verify `positionRepository.delete(position)` or `configService.updateConfig(config)`.
   - No test exists for `executeBuy`.

3. **`src/main/java/com/telestock/config/ConfigService.java`**:
   - Defines `getConfig()` returning `SystemConfig` and `updateConfig(SystemConfig newConfig)` returning `SystemConfig`.

4. **`src/main/java/com/telestock/model/SystemConfig.java`**:
   - Entity with fields:
     - `private Long id = 1L;`
     - `private Boolean tradingEnabled = true;`
     - `private Double availableCapital = 10000.0;`

---

## 2. Logic Chain

1. **Step 1: Instantiation of `LedgerService` by Mockito**
   - In `LedgerServiceTest.java:31-32`, `@InjectMocks private LedgerService ledgerService;` instructs Mockito to instantiate `LedgerService` using constructor injection.
   - Lombok's `@RequiredArgsConstructor` on `LedgerService` (lines 10, 18-23) generates a constructor with 4 parameters:
     `(PositionRepository, TradeRecordRepository, TelegramService, ConfigService)`.
   - Mockito discovers mocks for `PositionRepository`, `TradeRecordRepository`, and `TelegramService`. However, no `@Mock` exists for `ConfigService`.
   - Consequently, Mockito injects `null` for `configService`.

2. **Step 2: Execution and Point of Failure**
   - During `testExecuteSellCalculatesChargesCorrectly()` (line 46), `ledgerService.executeSell(position, exitPrice)` is invoked.
   - Lines 54-94 calculate charges, save the `TradeRecord`, and delete the position.
   - At line 96: `SystemConfig config = configService.getConfig();`
   - Since `configService` is `null`, dereferencing it throws `java.lang.NullPointerException: Cannot invoke "com.telestock.config.ConfigService.getConfig()" because "this.configService" is null`.

3. **Step 3: Secondary NPE if Unstubbed**
   - If `@Mock private ConfigService configService;` is added without stubbing, Mockito's default return for `configService.getConfig()` is `null`.
   - At line 98 of `LedgerService.java`: `config.setAvailableCapital(config.getAvailableCapital() + returnedCapital);`
   - Calling `config.getAvailableCapital()` on a `null` config throws `java.lang.NullPointerException: Cannot invoke "com.telestock.model.SystemConfig.getAvailableCapital()" because "config" is null`.
   - Therefore, both `@Mock private ConfigService configService;` and `when(configService.getConfig()).thenReturn(config);` with an initialized `SystemConfig` are strictly required.

4. **Step 4: Mathematical Verification of Statutory Charges**
   - For `quantity = 100`, `entryPrice = 100.0`, `exitPrice = 104.0`:
     - `buyValue = 100 * 100.0 = 10,000.0`
     - `sellValue = 100 * 104.0 = 10,400.0`
     - `grossPnl = 10,400.0 - 10,000.0 = 400.0`
     - `brokerageBuy = Math.min(20.0, 10000.0 * 0.0003) = 3.0`
     - `brokerageSell = Math.min(20.0, 10400.0 * 0.0003) = 3.12`
     - `totalBrokerage = 3.0 + 3.12 = 6.12`
     - `stt = (10000.0 + 10400.0) * 0.001 = 20.4`
     - `exchangeCharge = 20400.0 * 0.0000345 = 0.7038`
     - `sebiCharge = 20400.0 * 0.000001 = 0.0204`
     - `stampDuty = 10000.0 * 0.00015 = 1.5`
     - `gst = (6.12 + 0.7038 + 0.0204) * 0.18 = 6.8442 * 0.18 = 1.231956`
     - `totalCharges = 6.12 + 20.4 + 0.7038 + 0.0204 + 1.5 + 1.231956 = 29.976156`
     - `netPnl = 400.0 - 29.976156 = 370.023844`
   - All assertions in lines 70-77 test these exact numbers with delta `0.0001`. The math in both production code and test assertions is 100% mathematically correct and conforms strictly to Indian equity delivery statutory fee regulations.

5. **Step 5: Mathematical Verification of Capital Restoration**
   - Cash Outlay on Buy: `buyValue = 10,000.0`
   - Cash Proceeds on Sell: `sellValue - totalCharges = 10,400.0 - 29.976156 = 10,370.023844`
   - Production formula in `LedgerService.java:97`: `returnedCapital = buyValue + netPnl`
     `10,000.0 + 370.023844 = 10,370.023844`
   - Identity: `buyValue + netPnl == buyValue + (sellValue - buyValue - totalCharges) == sellValue - totalCharges`.
   - If initial capital in `SystemConfig` was `10,000.0`, post-sell capital is:
     `10,000.0 + 10,370.023844 = 20,370.023844`.
   - The capital conservation law holds unconditionally for profit and loss scenarios.

6. **Step 6: Gap Analysis in Test Coverage**
   - `executeBuy` has 0% unit test coverage:
     - Normal buy with capital deduction and position persistence
     - Insufficient capital rejection (guard clause)
     - Boundary condition where `buyValue == availableCapital`
   - `executeSell` does not test loss-making trades (stop-loss exit) or capital restoration assertions.

---

## 3. Caveats

1. **Intraday vs. Delivery STT**: The implementation in `LedgerService` calculates delivery STT (0.1% on buy and sell). If teleStock is ever modified for intraday trading (where STT is 0.025% on sell side only), this formula would need updating. For the current equity delivery scope, the implementation is accurate.
2. **Cap on Brokerage**: For orders where `orderValue * 0.0003 > 20.0` (order value > Rs 66,666.67), `Math.min(20.0, ...)` caps brokerage at Rs 20.0. The current test uses a Rs 10,000 order where the 0.03% rule applies (Rs 3.0 < Rs 20.0). A large order test is not strictly required by M1, but is noted for edge-case coverage.
3. **No Direct Code Modification**: As an explorer, no modifications to source or test files have been made; all recommendations are provided in this handoff for the implementer agent.

---

## 4. Conclusion

1. **Root Cause**: `LedgerServiceTest` fails with `NullPointerException` because `ConfigService` was not added as a `@Mock` dependency, resulting in Mockito injecting `null` into `LedgerService`, which throws on line 96 when `configService.getConfig()` is called.
2. **Fix Strategy**:
   - Add `@Mock private ConfigService configService;` to `LedgerServiceTest`.
   - In test methods, stub `when(configService.getConfig()).thenReturn(config);` with an initialized `SystemConfig`.
   - Add capital restoration assertions (`assertEquals(...)` on `config.getAvailableCapital()` and `verify(configService).updateConfig(config)`).
   - Add assertions for `positionRepository.delete(position)` and `telegramService.sendMessage(...)`.
3. **Test Suite Expansion**:
   - Add `testExecuteSellLossCalculatesChargesAndRestoresCapital()` for stop-loss scenario.
   - Add `testExecuteBuyWithSufficientCapital()` for normal buy flow, verifying capital deduction, stop-loss (-1.5%), target (+3.0%), entry time, and reasoning.
   - Add `testExecuteBuyWithInsufficientCapital()` for capital rejection guard clause.
   - Add `testExecuteBuyWithExactCapital()` for exact boundary condition.

### Proposed Code for `src/test/java/com/telestock/ledger/LedgerServiceTest.java`

```java
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
```

---

## 5. Verification Method

### 5.1 Verification Commands
The implementer can independently verify this solution with the following command:
```powershell
mvn test -Dtest=LedgerServiceTest
```
Expected output:
- Tests run: 5
- Failures: 0
- Errors: 0
- Skipped: 0
- BUILD SUCCESS

Full project test suite verification:
```powershell
mvn test
```

### 5.2 Files to Inspect
- `src/test/java/com/telestock/ledger/LedgerServiceTest.java`: Verify all 5 tests are compiled and pass.
- `src/main/java/com/telestock/ledger/LedgerService.java`: Verify no unexpected modifications to statutory fee math.

### 5.3 Invalidation Conditions
This analysis would be invalidated if:
1. `LedgerService` constructor signatures or dependencies change (e.g. removing `ConfigService`).
2. Indian statutory tax rates for delivery equity change (e.g., changes in Union Budget to STT or GST rates).
