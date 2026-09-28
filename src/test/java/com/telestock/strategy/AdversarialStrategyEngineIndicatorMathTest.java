package com.telestock.strategy;

import com.telestock.ai.GeminiAiService;
import com.telestock.config.ConfigService;
import com.telestock.feed.LiveMarketDataService;
import com.telestock.ledger.LedgerService;
import com.telestock.repository.PositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Adversarial Challenger 1 Test Suite for Milestone 2 Indicator Math in StrategyEngine.java.
 *
 * Covers:
 * 1. RSI Edge Cases: identical flat series, alternating oscillations, sudden single spike/drop,
 *    and exact boundary lengths (1, 14, 15, 16, 50, 1000).
 * 2. EMA Edge Cases: period 1, period > size (SMA fallback), identical series, zero values, extreme outliers.
 * 3. Robustness & Adversarial Inputs: NaN, Infinity, IndexOutOfBoundsException, and null handling.
 */
@ExtendWith(MockitoExtension.class)
public class AdversarialStrategyEngineIndicatorMathTest {

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

    // =========================================================================
    // 1. RSI ADVERSARIAL CHALLENGES
    // =========================================================================
    @Nested
    @DisplayName("RSI Adversarial Edge Cases")
    class RsiAdversarialTests {

        @Test
        @DisplayName("Identical flat series: 15, 50, and 1000 flat prices return neutral 50.0")
        void testRsi_IdenticalFlatSeries_Returns50() {
            // Flat 15 prices @ 100.0
            List<Double> flat15 = Collections.nCopies(15, 100.0);
            assertEquals(50.0, strategyEngine.calculateRSI(flat15, 14), 0.0001,
                    "15 flat prices must trigger flat market guard and return 50.0");

            // Flat 50 prices @ 250.0
            List<Double> flat50 = Collections.nCopies(50, 250.0);
            assertEquals(50.0, strategyEngine.calculateRSI(flat50, 14), 0.0001,
                    "50 flat prices must return 50.0");

            // Flat 1000 prices @ 1500.0
            List<Double> flat1000 = Collections.nCopies(1000, 1500.0);
            assertEquals(50.0, strategyEngine.calculateRSI(flat1000, 14), 0.0001,
                    "1000 flat prices must return 50.0");

            // Flat zero prices
            List<Double> flatZero = Collections.nCopies(15, 0.0);
            assertEquals(50.0, strategyEngine.calculateRSI(flatZero, 14), 0.0001,
                    "Flat zero series must return 50.0");

            // Flat negative prices
            List<Double> flatNeg = Collections.nCopies(15, -45.0);
            assertEquals(50.0, strategyEngine.calculateRSI(flatNeg, 14), 0.0001,
                    "Flat negative series must return 50.0");
        }

        @Test
        @DisplayName("Alternating oscillations: symmetric oscillations return exactly 50.0")
        void testRsi_AlternatingOscillations_Symmetric() {
            // 15 elements: 7 gains of +10 and 7 losses of -10
            List<Double> osc15 = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                osc15.add((i % 2 == 0) ? 100.0 : 110.0);
            }
            assertEquals(15, osc15.size());
            assertEquals(50.0, strategyEngine.calculateRSI(osc15, 14), 0.0001,
                    "Symmetric oscillation of +10/-10 must yield exactly 50.0 RSI");

            // 50 elements: oscillating sequence
            List<Double> osc50 = new ArrayList<>();
            for (int i = 0; i < 50; i++) {
                osc50.add((i % 2 == 0) ? 100.0 : 120.0);
            }
            assertEquals(50.0, strategyEngine.calculateRSI(osc50, 14), 0.0001,
                    "50-element symmetric oscillation must yield 50.0 RSI");

            // 1000 elements: oscillating sequence
            List<Double> osc1000 = new ArrayList<>();
            for (int i = 0; i < 1000; i++) {
                osc1000.add((i % 2 == 0) ? 500.0 : 510.0);
            }
            assertEquals(50.0, strategyEngine.calculateRSI(osc1000, 14), 0.0001,
                    "1000-element symmetric oscillation must yield 50.0 RSI");
        }

        @Test
        @DisplayName("Alternating oscillations: asymmetric gains produce correct RSI")
        void testRsi_AlternatingOscillations_Asymmetric() {
            // +5 on even step, -2 on odd step
            // 7 gains of +5.0 = 35.0 (avgGain = 35 / 14 = 2.5)
            // 7 losses of 2.0 = 14.0 (avgLoss = 14 / 14 = 1.0)
            // RS = 2.5 / 1.0 = 2.5
            // RSI = 100 - (100 / (1 + 2.5)) = 100 - (100 / 3.5) = 100 - 28.5714 = 71.4286
            List<Double> prices = new ArrayList<>();
            double curr = 100.0;
            prices.add(curr);
            for (int i = 0; i < 7; i++) {
                curr += 5.0;
                prices.add(curr);
                curr -= 2.0;
                prices.add(curr);
            }
            assertEquals(15, prices.size());

            double expectedRsi = 100.0 - (100.0 / (1.0 + 2.5));
            assertEquals(expectedRsi, strategyEngine.calculateRSI(prices, 14), 0.0001);
        }

        @Test
        @DisplayName("Sudden single spike at the end produces 100.0 (zero loss)")
        void testRsi_SuddenSpikeAtEnd_Returns100() {
            // 14 flat prices @ 100.0, then 15th spikes to 200.0
            List<Double> prices = new ArrayList<>(Collections.nCopies(14, 100.0));
            prices.add(200.0);
            assertEquals(15, prices.size());

            double rsi = strategyEngine.calculateRSI(prices, 14);
            assertEquals(100.0, rsi, 0.0001,
                    "Sudden single upward spike with no downward movements must return 100.0");
        }

        @Test
        @DisplayName("Sudden single drop at the end produces 0.0 (zero gain)")
        void testRsi_SuddenDropAtEnd_Returns0() {
            // 14 flat prices @ 100.0, then 15th drops to 50.0
            List<Double> prices = new ArrayList<>(Collections.nCopies(14, 100.0));
            prices.add(50.0);
            assertEquals(15, prices.size());

            double rsi = strategyEngine.calculateRSI(prices, 14);
            assertEquals(0.0, rsi, 0.0001,
                    "Sudden single downward drop with no upward movements must return 0.0");
        }

        @Test
        @DisplayName("Sudden transitory spike in the middle returning to base returns 50.0")
        void testRsi_SuddenTransitorySpikeInMiddle_Returns50() {
            // 15 prices: flat 100, spikes to 300 at index 7, drops back to 100 at index 8
            // Diffs: +200 at step 7, -200 at step 8, all others 0.0
            // Gains = 200, Losses = 200 -> RS = 1.0 -> RSI = 50.0
            List<Double> prices = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                if (i == 7) {
                    prices.add(300.0);
                } else {
                    prices.add(100.0);
                }
            }
            assertEquals(15, prices.size());

            double rsi = strategyEngine.calculateRSI(prices, 14);
            assertEquals(50.0, rsi, 0.0001,
                    "Transitory spike (+200, -200) inside window has balanced gains/losses, yielding 50.0");
        }

        @Test
        @DisplayName("Sudden transitory drop in the middle returning to base returns 50.0")
        void testRsi_SuddenTransitoryDropInMiddle_Returns50() {
            // Diffs: -50 at step 7, +50 at step 8, all others 0.0
            List<Double> prices = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                if (i == 7) {
                    prices.add(50.0);
                } else {
                    prices.add(100.0);
                }
            }
            assertEquals(15, prices.size());

            double rsi = strategyEngine.calculateRSI(prices, 14);
            assertEquals(50.0, rsi, 0.0001,
                    "Transitory drop (-50, +50) inside window has balanced gains/losses, yielding 50.0");
        }

        @Test
        @DisplayName("Exact length boundary tests: length 1, 14, 15, 16, 50, 1000")
        void testRsi_ExactLengthBoundaries() {
            // Length 1: insufficient history (< 15) -> 50.0
            assertEquals(50.0, strategyEngine.calculateRSI(List.of(100.0), 14), 0.0001);

            // Length 14: insufficient history (needs 15 prices for 14 differences) -> 50.0
            List<Double> len14 = new ArrayList<>();
            for (int i = 0; i < 14; i++) len14.add(100.0 + i);
            assertEquals(50.0, strategyEngine.calculateRSI(len14, 14), 0.0001,
                    "14 elements cannot form 14 differences; must return 50.0 without IOOBE");

            // Length 15: exactly 15 prices (minimum for 14 differences)
            List<Double> len15 = new ArrayList<>();
            for (int i = 0; i < 15; i++) len15.add(100.0 + i);
            assertEquals(100.0, strategyEngine.calculateRSI(len15, 14), 0.0001,
                    "15 monotonically increasing prices must compute exact 100.0");

            // Length 16: exactly 16 prices (windowing should ignore element 0)
            List<Double> len16 = new ArrayList<>();
            len16.add(9999.0); // Element 0 (should be excluded)
            for (int i = 0; i < 15; i++) len16.add(100.0 + i);
            assertEquals(16, len16.size());
            assertEquals(100.0, strategyEngine.calculateRSI(len16, 14), 0.0001,
                    "16 prices must evaluate only the last 15 elements, ignoring element 0");

            // Length 50: standard candle buffer
            List<Double> len50 = new ArrayList<>(Collections.nCopies(35, 50.0));
            for (int i = 0; i < 15; i++) len50.add(100.0 + i);
            assertEquals(50, len50.size());
            assertEquals(100.0, strategyEngine.calculateRSI(len50, 14), 0.0001,
                    "50-candle buffer must evaluate last 15 elements correctly without IOOBE");

            // Length 1000: large buffer
            List<Double> len1000 = new ArrayList<>(Collections.nCopies(985, 20.0));
            for (int i = 0; i < 15; i++) len1000.add(100.0 + i);
            assertEquals(1000, len1000.size());
            assertEquals(100.0, strategyEngine.calculateRSI(len1000, 14), 0.0001,
                    "1000-candle buffer must evaluate last 15 elements correctly without IOOBE");
        }

        @Test
        @DisplayName("RSI handles invalid periods gracefully")
        void testRsi_InvalidPeriods() {
            List<Double> prices = Collections.nCopies(20, 100.0);
            assertEquals(50.0, strategyEngine.calculateRSI(prices, 0), 0.0001);
            assertEquals(50.0, strategyEngine.calculateRSI(prices, -1), 0.0001);
            assertEquals(50.0, strategyEngine.calculateRSI(prices, -100), 0.0001);
            assertEquals(50.0, strategyEngine.calculateRSI(null, 14), 0.0001);
            assertEquals(50.0, strategyEngine.calculateRSI(Collections.emptyList(), 14), 0.0001);
        }
    }

    // =========================================================================
    // 2. EMA ADVERSARIAL CHALLENGES
    // =========================================================================
    @Nested
    @DisplayName("EMA Adversarial Edge Cases")
    class EmaAdversarialTests {

        @Test
        @DisplayName("Period 1: Multiplier is 1.0, tracking latest price exactly")
        void testEma_PeriodOne_TracksLatestPrice() {
            // Size 1
            assertEquals(100.0, strategyEngine.calculateEMA(List.of(100.0), 1), 0.0001);

            // Size 5: 10, 20, 30, 40, 50 -> for period 1, multiplier = 2/(1+1) = 1.0
            // Every step ema = (p - ema) * 1.0 + ema = p. Final ema must equal last price (50.0).
            List<Double> prices5 = List.of(10.0, 20.0, 30.0, 40.0, 50.0);
            assertEquals(50.0, strategyEngine.calculateEMA(prices5, 1), 0.0001,
                    "EMA(1) has alpha = 1.0, so it must strictly equal the most recent price");

            // Size 10 with random fluctuations: must equal last element
            List<Double> osc = List.of(100.0, 250.0, 80.0, 300.0, 120.0, 450.0, 70.0, 800.0, 150.0, 999.0);
            assertEquals(999.0, strategyEngine.calculateEMA(osc, 1), 0.0001,
                    "EMA(1) must equal the latest price regardless of prior values");
        }

        @Test
        @DisplayName("Period > size: falls back cleanly to SMA of all available prices without IOOBE")
        void testEma_PeriodGreaterThanSize_FallbackToSma() {
            // Size 1, Period 9: SMA of [100.0] = 100.0
            assertEquals(100.0, strategyEngine.calculateEMA(List.of(100.0), 9), 0.0001);

            // Size 5, Period 9: [10, 20, 30, 40, 50] -> sum = 150.0 -> SMA = 30.0
            List<Double> prices5 = List.of(10.0, 20.0, 30.0, 40.0, 50.0);
            assertEquals(30.0, strategyEngine.calculateEMA(prices5, 9), 0.0001);

            // Size 8, Period 9: [10, 10, 10, 10, 20, 20, 20, 20] -> sum = 120.0 -> SMA = 15.0
            List<Double> prices8 = List.of(10.0, 10.0, 10.0, 10.0, 20.0, 20.0, 20.0, 20.0);
            assertEquals(15.0, strategyEngine.calculateEMA(prices8, 9), 0.0001);

            // Size 20, Period 21: sum of 1..20 = 210 -> SMA = 10.5
            List<Double> prices20 = new ArrayList<>();
            for (int i = 1; i <= 20; i++) prices20.add((double) i);
            assertEquals(10.5, strategyEngine.calculateEMA(prices20, 21), 0.0001,
                    "When size < period, must return exact SMA of all available elements");
        }

        @Test
        @DisplayName("Identical series preserves constant value across all steps")
        void testEma_IdenticalSeries_PreservesConstant() {
            List<Double> identical30 = Collections.nCopies(30, 150.0);
            assertEquals(150.0, strategyEngine.calculateEMA(identical30, 9), 0.0001);
            assertEquals(150.0, strategyEngine.calculateEMA(identical30, 21), 0.0001);

            List<Double> identical50 = Collections.nCopies(50, 2450.75);
            assertEquals(2450.75, strategyEngine.calculateEMA(identical50, 9), 0.0001);
            assertEquals(2450.75, strategyEngine.calculateEMA(identical50, 21), 0.0001);
        }

        @Test
        @DisplayName("Zero values: handles zero series and drops to zero properly")
        void testEma_ZeroValues() {
            // All zeros
            List<Double> allZeros = Collections.nCopies(30, 0.0);
            assertEquals(0.0, strategyEngine.calculateEMA(allZeros, 9), 0.0001);
            assertEquals(0.0, strategyEngine.calculateEMA(allZeros, 21), 0.0001);

            // Drop from 100 to 0.0 at element 9 (period 9):
            // SMA seed for first 9 elements (all 100.0) = 100.0
            // Element 9 (0.0): (0.0 - 100.0) * 0.2 + 100.0 = -20.0 + 100.0 = 80.0
            List<Double> dropToZero = new ArrayList<>(Collections.nCopies(9, 100.0));
            dropToZero.add(0.0);
            assertEquals(80.0, strategyEngine.calculateEMA(dropToZero, 9), 0.0001);
        }

        @Test
        @DisplayName("Extreme outliers: large numbers, extreme spikes, and negative values")
        void testEma_ExtremeOutliers() {
            // Large prices (₹100,000 like MRF)
            List<Double> mrfPrices = Collections.nCopies(30, 135000.0);
            assertEquals(135000.0, strategyEngine.calculateEMA(mrfPrices, 9), 0.0001);

            // Massive single spike: first 9 elements 100.0, 10th element 10,000,100.0
            // SMA seed = 100.0; multiplier = 0.2
            // ema = (10000100.0 - 100.0) * 0.2 + 100.0 = 2000000.0 + 100.0 = 2000100.0
            List<Double> spikePrices = new ArrayList<>(Collections.nCopies(9, 100.0));
            spikePrices.add(10000100.0);
            assertEquals(2000100.0, strategyEngine.calculateEMA(spikePrices, 9), 0.0001);

            // Negative values (e.g. commodity contracts or negative spreads)
            List<Double> negPrices = List.of(-10.0, -20.0, -30.0);
            // Size 3 < period 9 -> SMA fallback: (-10 + -20 + -30) / 3 = -20.0
            assertEquals(-20.0, strategyEngine.calculateEMA(negPrices, 9), 0.0001);

            // Micro values (e.g. 0.0001)
            List<Double> microPrices = Collections.nCopies(20, 0.0005);
            assertEquals(0.0005, strategyEngine.calculateEMA(microPrices, 9), 0.000001);
        }

        @Test
        @DisplayName("Invalid period or empty/null prices return 0.0 safely")
        void testEma_InvalidPeriodOrEmpty() {
            assertEquals(0.0, strategyEngine.calculateEMA(null, 9), 0.0001);
            assertEquals(0.0, strategyEngine.calculateEMA(Collections.emptyList(), 9), 0.0001);
            assertEquals(0.0, strategyEngine.calculateEMA(List.of(100.0, 200.0), 0), 0.0001);
            assertEquals(0.0, strategyEngine.calculateEMA(List.of(100.0, 200.0), -1), 0.0001);
        }
    }

    // =========================================================================
    // 3. ADVERSARIAL ROBUSTNESS: NaN, INFINITY & NULL CHECKS
    // =========================================================================
    @Nested
    @DisplayName("Robustness: NaN, Infinity, and Boundary Hardening")
    class RobustnessTests {

        @Test
        @DisplayName("RSI never produces NaN or Infinity on strictly valid finite inputs")
        void testRsi_NeverProducesNanOrInfinity_OnFiniteInputs() {
            // Rapid exponential growth
            List<Double> expGrowth = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                expGrowth.add(Math.pow(2.0, i));
            }
            double rsiGrowth = strategyEngine.calculateRSI(expGrowth, 14);
            assertFalse(Double.isNaN(rsiGrowth), "RSI must not be NaN on exponential growth");
            assertFalse(Double.isInfinite(rsiGrowth), "RSI must not be Infinite on exponential growth");
            assertEquals(100.0, rsiGrowth, 0.0001);

            // Rapid exponential decay
            List<Double> expDecay = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                expDecay.add(Math.pow(0.5, i));
            }
            double rsiDecay = strategyEngine.calculateRSI(expDecay, 14);
            assertFalse(Double.isNaN(rsiDecay), "RSI must not be NaN on exponential decay");
            assertFalse(Double.isInfinite(rsiDecay), "RSI must not be Infinite on exponential decay");
            assertEquals(0.0, rsiDecay, 0.0001);
        }

        @Test
        @DisplayName("EMA never produces NaN or Infinity on strictly valid finite inputs")
        void testEma_NeverProducesNanOrInfinity_OnFiniteInputs() {
            List<Double> expGrowth = new ArrayList<>();
            for (int i = 0; i < 25; i++) {
                expGrowth.add(Math.pow(1.5, i));
            }
            double ema9 = strategyEngine.calculateEMA(expGrowth, 9);
            assertFalse(Double.isNaN(ema9), "EMA9 must not be NaN on exponential growth");
            assertFalse(Double.isInfinite(ema9), "EMA9 must not be Infinite on exponential growth");

            double ema21 = strategyEngine.calculateEMA(expGrowth, 21);
            assertFalse(Double.isNaN(ema21), "EMA21 must not be NaN on exponential growth");
            assertFalse(Double.isInfinite(ema21), "EMA21 must not be Infinite on exponential growth");
        }

        @Test
        @DisplayName("EMA with size < period gracefully handles null items via null-coalescing guard")
        void testEma_SizeLessThanPeriod_HandlesNullElements() {
            // When size < period, line 135: sum += (p != null ? p : 0.0)
            List<Double> listWithNulls = new ArrayList<>();
            listWithNulls.add(10.0);
            listWithNulls.add(null);
            listWithNulls.add(20.0);

            // sum = 10.0 + 0.0 + 20.0 = 30.0; size = 3; SMA = 10.0
            double ema = strategyEngine.calculateEMA(listWithNulls, 9);
            assertEquals(10.0, ema, 0.0001,
                    "Null items in short list (< period) must be treated as 0.0 without throwing NPE");
        }

        @Test
        @DisplayName("RSI with all NaN values triggers flat guard and returns 50.0")
        void testRsi_AllNanValues_TriggersFlatGuard() {
            // In Java, (NaN > 0.0) is false and (NaN < 0.0) is false.
            // Hence gains == 0.0 and losses == 0.0, which triggers flat guard: return 50.0!
            List<Double> nanList = Collections.nCopies(15, Double.NaN);
            double rsi = strategyEngine.calculateRSI(nanList, 14);
            assertEquals(50.0, rsi, 0.0001,
                    "All NaN list results in zero gains and zero losses, falling back to 50.0");
        }
    }
}
