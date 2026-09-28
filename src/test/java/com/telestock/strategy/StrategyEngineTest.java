package com.telestock.strategy;

import com.telestock.ai.GeminiAiService;
import com.telestock.config.ConfigService;
import com.telestock.feed.LiveMarketDataService;
import com.telestock.ledger.LedgerService;
import com.telestock.model.MarketData;
import com.telestock.model.Position;
import com.telestock.model.SystemConfig;
import com.telestock.repository.PositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StrategyEngineTest {

    @Mock
    private LiveMarketDataService dataService;

    @Mock
    private ConfigService configService;

    @Mock
    private GeminiAiService aiService;

    @Mock
    private LedgerService ledgerService;

    @Mock
    private PositionRepository positionRepository;

    @InjectMocks
    private StrategyEngine strategyEngine;

    private SystemConfig defaultConfig;

    @BeforeEach
    void setUp() {
        defaultConfig = new SystemConfig();
        defaultConfig.setTradingEnabled(true);
        defaultConfig.setAvailableCapital(10000.0);
    }

    // =========================================================================
    // 1. UNIT TESTS: calculateRSI
    // =========================================================================
    @Nested
    @DisplayName("calculateRSI Unit Tests")
    class CalculateRsiTests {

        @Test
        @DisplayName("Flat prices (all identical) should return neutral 50.0")
        void testRsi_FlatPrices_ReturnsNeutral50() {
            // 15 identical prices -> diffs are all 0.0 -> avgGain == 0.0 && avgLoss == 0.0
            List<Double> flatPrices = Collections.nCopies(15, 100.0);

            double rsi = strategyEngine.calculateRSI(flatPrices, 14);

            assertEquals(50.0, rsi, 0.0001, "Flat prices must yield neutral 50.0 RSI");
        }

        @Test
        @DisplayName("Pure upward trend should return 100.0")
        void testRsi_PureUpwardTrend_Returns100() {
            // 15 prices monotonically increasing -> 14 positive differences, 0 losses
            List<Double> upPrices = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                upPrices.add(100.0 + i * 2.0); // 100, 102, ..., 128
            }

            double rsi = strategyEngine.calculateRSI(upPrices, 14);

            assertEquals(100.0, rsi, 0.0001, "Pure upward trend must yield 100.0 RSI");
        }

        @Test
        @DisplayName("Pure downward trend should return 0.0")
        void testRsi_PureDownwardTrend_Returns0() {
            // 15 prices monotonically decreasing -> 14 negative differences, 0 gains
            List<Double> downPrices = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                downPrices.add(200.0 - i * 2.0); // 200, 198, ..., 172
            }

            double rsi = strategyEngine.calculateRSI(downPrices, 14);

            assertEquals(0.0, rsi, 0.0001, "Pure downward trend must yield 0.0 RSI");
        }

        @Test
        @DisplayName("Insufficient price history (< 15 prices for period 14) returns neutral 50.0")
        void testRsi_InsufficientPriceHistory_ReturnsNeutral50() {
            // Empty list
            assertEquals(50.0, strategyEngine.calculateRSI(Collections.emptyList(), 14), 0.0001);

            // Single price
            assertEquals(50.0, strategyEngine.calculateRSI(List.of(100.0), 14), 0.0001);

            // Exactly 14 prices (needs 15 for 14 differences)
            List<Double> fourteenPrices = new ArrayList<>();
            for (int i = 0; i < 14; i++) {
                fourteenPrices.add(100.0 + i);
            }
            assertEquals(50.0, strategyEngine.calculateRSI(fourteenPrices, 14), 0.0001,
                    "14 prices cannot form 14 differences; must return neutral 50.0 without throwing IndexOutOfBoundsException");

            // Null input or invalid period
            assertEquals(50.0, strategyEngine.calculateRSI(null, 14), 0.0001);
            assertEquals(50.0, strategyEngine.calculateRSI(fourteenPrices, 0), 0.0001);
            assertEquals(50.0, strategyEngine.calculateRSI(fourteenPrices, -5), 0.0001);
        }

        @Test
        @DisplayName("Known sequence with equal gains and losses returns exactly 50.0")
        void testRsi_KnownSequence_BalancedGainsLosses_Returns50() {
            // 15 prices: alternating +2 and -2 for 14 differences (7 gains of +2.0, 7 losses of -2.0)
            // avgGain = 14.0 / 14 = 1.0, avgLoss = 14.0 / 14 = 1.0, RS = 1.0 -> RSI = 50.0
            List<Double> prices = List.of(
                    100.0, 102.0, 100.0, 102.0, 100.0,
                    102.0, 100.0, 102.0, 100.0, 102.0,
                    100.0, 102.0, 100.0, 102.0, 100.0
            );
            assertEquals(15, prices.size());

            double rsi = strategyEngine.calculateRSI(prices, 14);

            assertEquals(50.0, rsi, 0.0001, "Balanced gain/loss sequence must produce 50.0 RSI");
        }

        @Test
        @DisplayName("Known sequence with 3:1 gain to loss ratio returns exactly 75.0")
        void testRsi_KnownSequence_ThreeToOneRatio_Returns75() {
            // 15 prices: 7 gains of +3.0 and 7 losses of -1.0
            // Sum gains = 21.0 -> avgGain = 1.5
            // Sum losses = 7.0 -> avgLoss = 0.5
            // RS = 1.5 / 0.5 = 3.0 -> RSI = 100 - (100 / (1 + 3)) = 75.0
            List<Double> prices = List.of(
                    100.0, 103.0, 102.0, 105.0, 104.0,
                    107.0, 106.0, 109.0, 108.0, 111.0,
                    110.0, 113.0, 112.0, 115.0, 114.0
            );
            assertEquals(15, prices.size());

            double rsi = strategyEngine.calculateRSI(prices, 14);

            assertEquals(75.0, rsi, 0.0001, "3:1 gain-to-loss sequence must produce 75.0 RSI");
        }

        @Test
        @DisplayName("Known sequence with 1:3 gain to loss ratio returns exactly 25.0")
        void testRsi_KnownSequence_OneToThreeRatio_Returns25() {
            // 15 prices: 7 gains of +1.0 and 7 losses of -3.0
            // Sum gains = 7.0 -> avgGain = 0.5
            // Sum losses = 21.0 -> avgLoss = 1.5
            // RS = 0.5 / 1.5 = 1/3 -> RSI = 100 - (100 / (1 + 1/3)) = 25.0
            List<Double> prices = List.of(
                    100.0, 101.0, 98.0, 99.0, 96.0,
                    97.0, 94.0, 95.0, 92.0, 93.0,
                    90.0, 91.0, 88.0, 89.0, 86.0
            );
            assertEquals(15, prices.size());

            double rsi = strategyEngine.calculateRSI(prices, 14);

            assertEquals(25.0, rsi, 0.0001, "1:3 gain-to-loss sequence must produce 25.0 RSI");
        }

        @Test
        @DisplayName("Buffer windowing: Evaluates only the last 15 prices when list contains > 15 elements")
        void testRsi_BufferWindowing_OnlyEvaluatesLastPeriodPlusOnePrices() {
            // Prepend 10 arbitrary prices before the 75.0 known sequence
            List<Double> longerPrices = new ArrayList<>(List.of(
                    999.0, 1.0, 500.0, 20.0, 800.0, 30.0, 700.0, 40.0, 600.0, 50.0
            ));
            longerPrices.addAll(List.of(
                    100.0, 103.0, 102.0, 105.0, 104.0,
                    107.0, 106.0, 109.0, 108.0, 111.0,
                    110.0, 113.0, 112.0, 115.0, 114.0
            ));
            assertEquals(25, longerPrices.size());

            double rsi = strategyEngine.calculateRSI(longerPrices, 14);

            assertEquals(75.0, rsi, 0.0001,
                    "RSI must only examine the last 15 prices (last 14 differences), ignoring earlier history");
        }
    }

    // =========================================================================
    // 2. UNIT TESTS: calculateEMA
    // =========================================================================
    @Nested
    @DisplayName("calculateEMA Unit Tests")
    class CalculateEmaTests {

        @Test
        @DisplayName("Convergence test: When price history length == period, EMA equals SMA seed")
        void testEma_ConvergenceAgainstSmaSeed() {
            // Period = 9, prices: 10, 20, 30, 40, 50, 60, 70, 80, 90 (9 elements)
            // Sum = 450.0, SMA = 50.0
            List<Double> prices9 = List.of(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0);

            double ema9 = strategyEngine.calculateEMA(prices9, 9);
            assertEquals(50.0, ema9, 0.0001, "When N == period, EMA must equal the SMA seed");

            // Period = 21, prices: 10 to 30 (21 elements)
            // Sum = 420.0, SMA = 20.0
            List<Double> prices21 = new ArrayList<>();
            for (int i = 10; i <= 30; i++) {
                prices21.add((double) i);
            }
            assertEquals(21, prices21.size());

            double ema21 = strategyEngine.calculateEMA(prices21, 21);
            assertEquals(20.0, ema21, 0.0001, "When N == 21, EMA21 must equal the 21-element SMA seed");
        }

        @Test
        @DisplayName("Flat prices series preserves constant EMA value across smoothing steps")
        void testEma_FlatPrices_PreservesConstantValue() {
            List<Double> flatPrices = Collections.nCopies(30, 150.0);

            assertEquals(150.0, strategyEngine.calculateEMA(flatPrices, 9), 0.0001);
            assertEquals(150.0, strategyEngine.calculateEMA(flatPrices, 21), 0.0001);
        }

        @Test
        @DisplayName("9-period EMA verification against known calculated data")
        void testEma9_VerificationAgainstKnownData() {
            // Period = 9, multiplier = 2.0 / (9 + 1) = 0.2
            // Elements 0..8: 10, 11, 12, 13, 14, 15, 16, 17, 18 -> sum = 126.0, SMA seed = 14.0
            // Element 9 (20.0): (20.0 - 14.0) * 0.2 + 14.0 = 1.2 + 14.0 = 15.2
            // Element 10 (22.0): (22.0 - 15.2) * 0.2 + 15.2 = 1.36 + 15.2 = 16.56
            List<Double> prices = List.of(
                    10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 20.0, 22.0
            );

            double ema9 = strategyEngine.calculateEMA(prices, 9);

            assertEquals(16.56, ema9, 0.0001, "EMA(9) after 11 prices must equal 16.56");
        }

        @Test
        @DisplayName("21-period EMA verification against known calculated data")
        void testEma21_VerificationAgainstKnownData() {
            // Period = 21, multiplier = 2.0 / (21 + 1) = 2.0 / 22.0 = 1.0 / 11.0
            // First 21 elements: all 50.0 -> SMA seed = 50.0
            // 22nd element (61.0): (61.0 - 50.0) * (1/11) + 50.0 = 1.0 + 50.0 = 51.0
            // 23rd element (73.0): (73.0 - 51.0) * (1/11) + 51.0 = 2.0 + 51.0 = 53.0
            List<Double> prices = new ArrayList<>(Collections.nCopies(21, 50.0));
            prices.add(61.0);
            prices.add(73.0);
            assertEquals(23, prices.size());

            double ema21 = strategyEngine.calculateEMA(prices, 21);

            assertEquals(53.0, ema21, 0.0001, "EMA(21) after 23 prices must equal 53.0");
        }

        @Test
        @DisplayName("Insufficient price history (< period) falls back to SMA of available prices")
        void testEma_InsufficientHistory_FallbackToSma() {
            // 5 elements for period 9: [10.0, 20.0, 30.0, 40.0, 50.0]
            // Sum = 150.0, SMA = 30.0
            List<Double> shortPrices = List.of(10.0, 20.0, 30.0, 40.0, 50.0);

            double ema = strategyEngine.calculateEMA(shortPrices, 9);

            assertEquals(30.0, ema, 0.0001, "Insufficient history should return SMA of available prices without exception");
        }

        @Test
        @DisplayName("Null, empty or non-positive period returns 0.0")
        void testEma_EdgeCases_NullEmptyInvalidPeriod() {
            assertEquals(0.0, strategyEngine.calculateEMA(null, 9), 0.0001);
            assertEquals(0.0, strategyEngine.calculateEMA(Collections.emptyList(), 9), 0.0001);
            assertEquals(0.0, strategyEngine.calculateEMA(List.of(100.0, 200.0), 0), 0.0001);
            assertEquals(0.0, strategyEngine.calculateEMA(List.of(100.0, 200.0), -1), 0.0001);
        }
    }

    // =========================================================================
    // 3. UNIT & LOGIC TESTS: Dynamic Position Sizing
    // =========================================================================
    @Nested
    @DisplayName("Dynamic Position Sizing Tests")
    class DynamicPositionSizingTests {

        @Test
        @DisplayName("First trade allocation: 5 open slots, ₹10,000 capital, LTP ₹500 -> 4 shares")
        void testCalculatePositionSize_FirstTradeAllocation() {
            double capital = 10000.0;
            int openSlots = 5;
            double ltp = 500.0;

            // capitalPerSlot = 10000 / 5 = 2000.0; qty = 2000 / 500 = 4
            int qty = strategyEngine.calculatePositionSize(ltp, capital, openSlots);

            assertEquals(4, qty, "First trade should allocate capital / 5 slots = ₹2,000 -> 4 shares @ ₹500");
        }

        @Test
        @DisplayName("Second trade execution from remaining capital: 4 open slots, ₹8,000 capital, LTP ₹1,000 -> 2 shares")
        void testCalculatePositionSize_SecondTradeFromRemainingCapital() {
            double capital = 8000.0;
            int openSlots = 4;
            double ltp = 1000.0;

            // capitalPerSlot = 8000 / 4 = 2000.0; qty = 2000 / 1000 = 2
            int qty = strategyEngine.calculatePositionSize(ltp, capital, openSlots);

            assertEquals(2, qty, "Second trade should allocate capital / 4 slots = ₹2,000 -> 2 shares @ ₹1,000");
        }

        @Test
        @DisplayName("Minimum 1 share rule: Slot allocation < LTP, but availableCapital >= LTP -> 1 share")
        void testCalculatePositionSize_MinOneShareWhenCapitalGteLtp() {
            double capital = 3500.0;
            int openSlots = 2;
            double ltp = 2500.0;

            // capitalPerSlot = 3500 / 2 = 1750.0; (int)(1750 / 2500) = 0
            // Since availableCapital (3500.0) >= ltp (2500.0), rule forces qty = 1
            int qty = strategyEngine.calculatePositionSize(ltp, capital, openSlots);

            assertEquals(1, qty, "When slot capital < LTP but total available capital >= LTP, qty must be 1 share");
        }

        @Test
        @DisplayName("Max position limit guard: 0 open slots returns 0 shares")
        void testCalculatePositionSize_MaxPositionLimitGuard_ZeroSlots() {
            double capital = 10000.0;
            int openSlots = 0;
            double ltp = 500.0;

            int qty = strategyEngine.calculatePositionSize(ltp, capital, openSlots);

            assertEquals(0, qty, "When no slots are open, position sizing must return 0");
        }

        @Test
        @DisplayName("Max position limit guard: Negative open slots returns 0 shares")
        void testCalculatePositionSize_MaxPositionLimitGuard_NegativeSlots() {
            int qty = strategyEngine.calculatePositionSize(500.0, 10000.0, -1);
            assertEquals(0, qty, "Negative open slots must return 0");
        }

        @Test
        @DisplayName("Rejection when availableCapital < LTP returns 0 shares")
        void testCalculatePositionSize_RejectionWhenCapitalLessThanLtp() {
            double capital = 400.0;
            int openSlots = 1;
            double ltp = 500.0;

            int qty = strategyEngine.calculatePositionSize(ltp, capital, openSlots);

            assertEquals(0, qty, "Capital ₹400 < LTP ₹500 cannot fund even 1 share; must reject order (qty = 0)");
        }

        @Test
        @DisplayName("High LTP stock rejection: LTP > availableCapital returns 0 shares")
        void testCalculatePositionSize_HighLtpStockRejection() {
            double capital = 10000.0;
            int openSlots = 5;
            double ltp = 130000.0; // MRF

            int qty = strategyEngine.calculatePositionSize(ltp, capital, openSlots);

            assertEquals(0, qty, "Expensive stock with LTP exceeding wallet must be rejected without error");
        }

        @Test
        @DisplayName("High LTP stock execution: Capital ₹150,000 allows purchasing 1 share of ₹130,000 stock")
        void testCalculatePositionSize_HighLtpStockExecutionWhenCapitalSufficient() {
            double capital = 150000.0;
            int openSlots = 5;
            double ltp = 130000.0;

            // capitalPerSlot = 30000 < 130000 -> integer division = 0
            // but availableCapital (150000) >= ltp (130000) -> qty = 1
            int qty = strategyEngine.calculatePositionSize(ltp, capital, openSlots);

            assertEquals(1, qty, "When wallet can afford stock, minimum 1 share allows execution");
        }

        @Test
        @DisplayName("Zero or negative capital guards return 0 shares")
        void testCalculatePositionSize_ZeroOrNegativeCapital() {
            assertEquals(0, strategyEngine.calculatePositionSize(100.0, 0.0, 5));
            assertEquals(0, strategyEngine.calculatePositionSize(100.0, -500.0, 5));
        }

        @Test
        @DisplayName("Zero or negative LTP guards return 0 shares")
        void testCalculatePositionSize_ZeroOrNegativeLtp() {
            assertEquals(0, strategyEngine.calculatePositionSize(0.0, 10000.0, 5));
            assertEquals(0, strategyEngine.calculatePositionSize(-50.0, 10000.0, 5));
        }

        @Test
        @DisplayName("Safety clamp prevents quantity from exceeding total available capital")
        void testCalculatePositionSize_SafetyClampAgainstCapitalExceedance() {
            double capital = 2100.0;
            int openSlots = 1;
            double ltp = 1000.0;

            // capitalPerSlot = 2100 / 1 = 2100; qty = 2100 / 1000 = 2; cost = 2000 <= 2100
            int qty = strategyEngine.calculatePositionSize(ltp, capital, openSlots);

            assertEquals(2, qty);
            assertTrue((qty * ltp) <= capital, "Order value must never exceed total available capital");
        }
    }

    // =========================================================================
    // 4. UNIT & INTERACTION TESTS: Exit Conditions (checkExitConditions)
    // =========================================================================
    @Nested
    @DisplayName("Exit Conditions Tests")
    class ExitConditionsTests {

        private Position createSamplePosition(String symbol, double entryPrice) {
            Position pos = new Position();
            pos.setSymbol(symbol);
            pos.setQuantity(10);
            pos.setEntryPrice(entryPrice);
            pos.setStopLoss(entryPrice * 0.985); // -1.5%
            pos.setTarget(entryPrice * 1.03);   // +3.0%
            pos.setEntryTime(LocalDateTime.now().minusHours(1));
            pos.setGeminiReasoning("Technical breakout");
            return pos;
        }

        @Test
        @DisplayName("Target price (+3.0%) hit triggers ledgerService.executeSell")
        void testCheckExitConditions_TargetHit_TriggersSell() {
            String symbol = "INFY.NS";
            double entryPrice = 1000.0;
            Position position = createSamplePosition(symbol, entryPrice);
            // target = 1030.0
            assertEquals(1030.0, position.getTarget(), 0.0001);

            when(positionRepository.findBySymbol(symbol)).thenReturn(Optional.of(position));

            // LTP hits exact target (1030.0)
            strategyEngine.checkExitConditions(symbol, 1030.0);

            verify(ledgerService, times(1)).executeSell(position, 1030.0);
        }

        @Test
        @DisplayName("Target price exceeded (+4.0% gap up) triggers ledgerService.executeSell at exit price")
        void testCheckExitConditions_TargetExceeded_TriggersSellAtExitPrice() {
            String symbol = "INFY.NS";
            Position position = createSamplePosition(symbol, 1000.0);

            when(positionRepository.findBySymbol(symbol)).thenReturn(Optional.of(position));

            // LTP gaps up to 1040.0
            strategyEngine.checkExitConditions(symbol, 1040.0);

            verify(ledgerService, times(1)).executeSell(position, 1040.0);
        }

        @Test
        @DisplayName("Stop loss price (-1.5%) hit triggers ledgerService.executeSell")
        void testCheckExitConditions_StopLossHit_TriggersSell() {
            String symbol = "TCS.NS";
            double entryPrice = 3000.0;
            Position position = createSamplePosition(symbol, entryPrice);
            // stopLoss = 3000 * 0.985 = 2955.0
            assertEquals(2955.0, position.getStopLoss(), 0.0001);

            when(positionRepository.findBySymbol(symbol)).thenReturn(Optional.of(position));

            // LTP hits exact stop loss (2955.0)
            strategyEngine.checkExitConditions(symbol, 2955.0);

            verify(ledgerService, times(1)).executeSell(position, 2955.0);
        }

        @Test
        @DisplayName("Stop loss exceeded (-2.5% gap down) triggers ledgerService.executeSell at lower exit price")
        void testCheckExitConditions_StopLossExceeded_TriggersSellAtExitPrice() {
            String symbol = "TCS.NS";
            Position position = createSamplePosition(symbol, 3000.0);

            when(positionRepository.findBySymbol(symbol)).thenReturn(Optional.of(position));

            // LTP drops to 2925.0 (< 2955.0)
            strategyEngine.checkExitConditions(symbol, 2925.0);

            verify(ledgerService, times(1)).executeSell(position, 2925.0);
        }

        @Test
        @DisplayName("LTP within boundaries (between SL and Target) does NOT trigger sell")
        void testCheckExitConditions_WithinBoundaries_DoesNotTriggerSell() {
            String symbol = "RELIANCE.NS";
            Position position = createSamplePosition(symbol, 2000.0);
            // SL = 1970.0, Target = 2060.0

            when(positionRepository.findBySymbol(symbol)).thenReturn(Optional.of(position));

            // Test various prices inside the holding range
            strategyEngine.checkExitConditions(symbol, 2000.0); // Flat entry price
            strategyEngine.checkExitConditions(symbol, 2050.0); // +2.5% profit (below target)
            strategyEngine.checkExitConditions(symbol, 1975.0); // -1.25% loss (above SL)

            verify(ledgerService, never()).executeSell(any(), anyDouble());
        }

        @Test
        @DisplayName("No active position for symbol does NOT trigger sell")
        void testCheckExitConditions_NoPosition_DoesNotTriggerSell() {
            String symbol = "HDFCBANK.NS";
            when(positionRepository.findBySymbol(symbol)).thenReturn(Optional.empty());

            strategyEngine.checkExitConditions(symbol, 1500.0);

            verify(ledgerService, never()).executeSell(any(), anyDouble());
        }
    }

    // =========================================================================
    // 5. CONCURRENCY & THREAD SAFETY TESTS
    // =========================================================================
    @Nested
    @DisplayName("Candle Storage Concurrency & Thread-Safety Tests")
    class ConcurrencyTests {

        @Test
        @DisplayName("Concurrent reads and writes on candle storage do not throw ConcurrentModificationException")
        void testCandleCloses_ConcurrentReadWrite_ThreadSafe() throws Exception {
            String symbol = "RELIANCE.NS";
            int numWriters = 4;
            int numReaders = 4;
            int iterationsPerThread = 500;

            ExecutorService executor = Executors.newFixedThreadPool(numWriters + numReaders);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(numWriters + numReaders);
            AtomicInteger errorCount = new AtomicInteger(0);

            // Writers simulating incoming 5m candle closes
            for (int w = 0; w < numWriters; w++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < iterationsPerThread; i++) {
                            strategyEngine.addCandleClose(symbol, 2000.0 + (i % 50));
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Readers simulating concurrent indicator calculations or telemetry queries
            for (int r = 0; r < numReaders; r++) {
                executor.submit(() -> {
                    try {
                        startLatch.await();
                        for (int i = 0; i < iterationsPerThread; i++) {
                            List<Double> closes = strategyEngine.getCandleCloses(symbol);
                            if (closes != null && closes.size() >= 15) {
                                strategyEngine.calculateRSI(closes, 14);
                            }
                            if (closes != null && closes.size() >= 21) {
                                strategyEngine.calculateEMA(closes, 9);
                                strategyEngine.calculateEMA(closes, 21);
                            }
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        doneLatch.countDown();
                    }
                });
            }

            // Trigger concurrent start
            startLatch.countDown();
            boolean finishedInTime = doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(finishedInTime, "Concurrent tasks should finish within 10 seconds");
            assertEquals(0, errorCount.get(), "Thread safety failed: ConcurrentModificationException or other error occurred");

            // Verify rolling window bound (max 50 candles retained)
            List<Double> finalCloses = strategyEngine.getCandleCloses(symbol);
            assertTrue(finalCloses.size() <= 50, "Candle closes buffer must be bounded to at most 50 elements");
        }
    }

    // =========================================================================
    // 6. INTEGRATION TESTS: Signal Evaluation & Multi-Trade Pipeline
    // =========================================================================
    @Nested
    @DisplayName("Signal Evaluation Flow Integration Tests")
    class SignalEvaluationIntegrationTests {

        @Test
        @DisplayName("evaluateSignals skips all processing when tradingEnabled is false")
        void testEvaluateSignals_TradingDisabled_SkipsProcessing() {
            defaultConfig.setTradingEnabled(false);
            when(configService.getConfig()).thenReturn(defaultConfig);

            strategyEngine.evaluateSignals();

            verify(dataService, never()).getAllLatestData();
            verify(ledgerService, never()).executeBuy(anyString(), anyDouble(), anyInt(), anyString());
            verify(ledgerService, never()).executeSell(any(), anyDouble());
        }

        @Test
        @DisplayName("evaluateSignals checks exit conditions for all symbols in polled market data")
        void testEvaluateSignals_ChecksExitConditions() {
            when(configService.getConfig()).thenReturn(defaultConfig);

            String symbol = "INFY.NS";
            double ltp = 1040.0; // Exceeds target
            Position pos = new Position();
            pos.setSymbol(symbol);
            pos.setEntryPrice(1000.0);
            pos.setTarget(1030.0);
            pos.setStopLoss(985.0);

            MarketData md = new MarketData();
            md.setSymbol(symbol);
            md.setLtp(ltp);

            when(dataService.getAllLatestData()).thenReturn(Map.of(symbol, md));
            when(positionRepository.findBySymbol(symbol)).thenReturn(Optional.of(pos));

            strategyEngine.evaluateSignals();

            // Exit condition must be checked and sell executed
            verify(ledgerService, times(1)).executeSell(pos, ltp);
        }

        @Test
        @DisplayName("Sequential multi-trade execution verifies dynamic capital decrement across multiple buys")
        void testMultiTrade_DynamicSizing_AcrossConsecutiveTrades() {
            // Trade 1: Starting capital ₹10,000, 0 positions (5 open slots), stock @ ₹500
            int qty1 = strategyEngine.calculatePositionSize(500.0, 10000.0, 5);
            assertEquals(4, qty1); // 4 * 500 = ₹2,000 cost

            // Simulate wallet deduction
            double capitalAfterTrade1 = 10000.0 - (qty1 * 500.0); // ₹8,000
            assertEquals(8000.0, capitalAfterTrade1);

            // Trade 2: Capital ₹8,000, 1 position (4 open slots), stock @ ₹1,000
            int qty2 = strategyEngine.calculatePositionSize(1000.0, capitalAfterTrade1, 4);
            assertEquals(2, qty2); // 2 * 1000 = ₹2,000 cost

            // Simulate wallet deduction
            double capitalAfterTrade2 = capitalAfterTrade1 - (qty2 * 1000.0); // ₹6,000
            assertEquals(6000.0, capitalAfterTrade2);

            // Trade 3: Capital ₹6,000, 2 positions (3 open slots), stock @ ₹3,000
            // capitalPerSlot = 6000 / 3 = 2000 < 3000 -> 0; but capital >= ltp -> qty = 1
            int qty3 = strategyEngine.calculatePositionSize(3000.0, capitalAfterTrade2, 3);
            assertEquals(1, qty3); // 1 * 3000 = ₹3,000 cost

            // Simulate wallet deduction
            double capitalAfterTrade3 = capitalAfterTrade2 - (qty3 * 3000.0); // ₹3,000
            assertEquals(3000.0, capitalAfterTrade3);

            // Trade 4: Capital ₹3,000, 3 positions (2 open slots), stock @ ₹500
            // capitalPerSlot = 3000 / 2 = 1500 -> qty = 1500 / 500 = 3
            int qty4 = strategyEngine.calculatePositionSize(500.0, capitalAfterTrade3, 2);
            assertEquals(3, qty4); // 3 * 500 = ₹1,500 cost

            // Simulate wallet deduction
            double capitalAfterTrade4 = capitalAfterTrade3 - (qty4 * 500.0); // ₹1,500
            assertEquals(1500.0, capitalAfterTrade4);

            // Trade 5: Capital ₹1,500, 4 positions (1 open slot), stock @ ₹1,200
            // capitalPerSlot = 1500 / 1 = 1500 -> qty = 1500 / 1200 = 1
            int qty5 = strategyEngine.calculatePositionSize(1200.0, capitalAfterTrade4, 1);
            assertEquals(1, qty5); // 1 * 1200 = ₹1,200 cost

            // Simulate wallet deduction
            double capitalAfterTrade5 = capitalAfterTrade4 - (qty5 * 1200.0); // ₹300
            assertEquals(300.0, capitalAfterTrade5);

            // Trade 6: 5 positions already active -> 0 open slots
            int qty6 = strategyEngine.calculatePositionSize(100.0, capitalAfterTrade5, 0);
            assertEquals(0, qty6, "Trade 6 must be blocked by max position limit guard (0 open slots)");

            // Trade 7: Stock with LTP ₹500 when wallet is ₹300 -> rejected
            int qty7 = strategyEngine.calculatePositionSize(500.0, capitalAfterTrade5, 1);
            assertEquals(0, qty7, "Trade 7 must be rejected because capital ₹300 < LTP ₹500");
        }
    }
}
