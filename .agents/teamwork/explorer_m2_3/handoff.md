# Milestone 2 Test Suite Architecture & Design Report: `StrategyEngineTest.java`

**Agent**: Explorer M2-3  
**Working Directory**: `c:\Users\keval\teleStock\.agents\teamwork\explorer_m2_3\`  
**Target Test File**: `src/test/java/com/telestock/strategy/StrategyEngineTest.java`  
**Date**: 2026-09-27  
**Status**: COMPLETE (Hard Handoff)

---

## 1. Observation

### 1.1 Current State of `StrategyEngine.java`
From `src/main/java/com/telestock/strategy/StrategyEngine.java`:
- **Lack of Existing Test Suite**:
  There is currently no test file for `StrategyEngine`. `src/test/java/com/telestock/strategy/StrategyEngineTest.java` does not exist. All existing tests are confined to `com.telestock.config`, `com.telestock.controller`, and `com.telestock.ledger.LedgerServiceTest`.
- **RSI Calculation (lines 109–122)**:
  ```java
  109:     private double calculateRSI(List<Double> prices, int period) {
  110:         double gains = 0;
  111:         double losses = 0;
  112:         for (int i = prices.size() - period; i < prices.size() - 1; i++) {
  113:             double diff = prices.get(i+1) - prices.get(i);
  114:             if (diff > 0) gains += diff;
  115:             else losses -= diff;
  116:         }
  117:         double avgGain = gains / period;
  118:         double avgLoss = losses / period;
  119:         if (avgLoss == 0) return 100;
  120:         double rs = avgGain / avgLoss;
  121:         return 100 - (100 / (1 + rs));
  122:     }
  ```
  - Line 112 runs for only $13$ iterations when `period = 14`, yet lines 117-118 divide by $14$.
  - Line 119 returns `100` when `avgLoss == 0`. When prices are identical (flat market), `avgGain == 0.0` and `avgLoss == 0.0`, spuriously returning `100.0` (maximum overbought) instead of neutral `50.0`.
  - When `prices.size() < 15`, `prices.size() - period` causes `IndexOutOfBoundsException` or underflow.
- **EMA Calculation (lines 100–107)**:
  ```java
  100:     private double calculateEMA(List<Double> prices, int period) {
  101:         double multiplier = 2.0 / (period + 1);
  102:         double ema = prices.get(prices.size() - period);
  103:         for (int i = prices.size() - period + 1; i < prices.size(); i++) {
  104:             ema = (prices.get(i) - ema) * multiplier + ema;
  105:         }
  106:         return ema;
  107:     }
  ```
  - Line 102 seeds with a single price tick at `prices.size() - period` instead of the standard SMA seed over the first `period` data points.
  - History earlier than `prices.size() - period` is discarded.
- **Position Sizing (lines 80–84)**:
  ```java
  80:             if (decision.isApproval()) {
  81:                 int qty = (int) (10000 / ltp); // Simulate 10000 capital per trade
  82:                 if (qty == 0) qty = 1;
  83:                 ledgerService.executeBuy(symbol, ltp, qty, decision.getReasoning());
  84:             }
  ```
  - Hardcoded `(int) (10000 / ltp)` locks out all subsequent trades after the first trade is placed because `availableCapital` in `SystemConfig` is decremented from ₹10,000 to ~₹3,000 or less, causing `LedgerService.executeBuy` line 28 (`config.getAvailableCapital() < buyValue`) to reject subsequent trades.
  - For stocks with `ltp > 10000` (e.g., MRF at ₹130,000), line 82 forces `qty = 1`, which immediately fails in `LedgerService` when available capital is insufficient.
- **Exit Conditions (lines 92–98)**:
  ```java
  92:     private void checkExitConditions(String symbol, double ltp) {
  93:         positionRepository.findBySymbol(symbol).ifPresent(pos -> {
  94:             if (ltp >= pos.getTarget() || ltp <= pos.getStopLoss()) {
  95:                 ledgerService.executeSell(pos, ltp);
  96:             }
  97:         });
  98:     }
  ```
  - Target price is configured in `Position` as `entryPrice * 1.03` (+3.0%).
  - Stop loss is configured as `entryPrice * 0.985` (-1.5%).
  - Method is `private`, preventing direct unit-test invocation without reflection or integration execution.
- **Candle Storage (lines 35, 57)**:
  ```java
  35:     private final Map<String, List<Double>> candleCloses = new ConcurrentHashMap<>();
  57:                 List<Double> closes = candleCloses.computeIfAbsent(symbol, k -> new ArrayList<>());
  ```
  - Value type is `java.util.ArrayList`, which is thread-unsafe under Spring Boot's multi-threaded task scheduler.

### 1.2 Test Framework Dependencies
From `pom.xml` (lines 40–44):
- `spring-boot-starter-test` provides JUnit 5 Jupiter (`org.junit.jupiter.api.*`), Mockito (`org.mockito.*`), and AssertJ (`org.assertj.core.api.*`).

---

## 2. Logic Chain

### 2.1 Design of Unit Tests for `calculateRSI`

1. **Flat Prices Evaluation**:
   - *Observation*: Line 119 returned 100 when `avgLoss == 0`. In a flat series of 15 prices (e.g. `[100.0, ..., 100.0]`), all 14 price differences are `0.0`.
   - *Logic*: Both `avgGain == 0.0` and `avgLoss == 0.0`. Under the M2 fix, this triggers the neutral guard: `if (avgGain == 0.0 && avgLoss == 0.0) return 50.0;`.
   - *Test Design*: `testRsi_FlatPrices_ReturnsNeutral50` verifies `calculateRSI(Collections.nCopies(15, 100.0), 14) == 50.0`.

2. **Pure Upward Trend Evaluation**:
   - *Observation*: When prices rise monotonically across 15 points (e.g., $100.0 \to 128.0$), all 14 differences are positive ($+2.0$).
   - *Logic*: `avgGain = 2.0`, `avgLoss = 0.0`. The guard `if (avgLoss == 0.0) return 100.0;` executes.
   - *Test Design*: `testRsi_PureUpwardTrend_Returns100` verifies `calculateRSI(upPrices, 14) == 100.0`.

3. **Pure Downward Trend Evaluation**:
   - *Observation*: When prices fall monotonically across 15 points ($200.0 \to 172.0$), all 14 differences are negative ($-2.0$).
   - *Logic*: `avgGain = 0.0`, `avgLoss = 2.0`. $RS = 0 / 2.0 = 0 \implies 100 - (100 / 1) = 0.0$.
   - *Test Design*: `testRsi_PureDownwardTrend_Returns0` verifies `calculateRSI(downPrices, 14) == 0.0`.

4. **Insufficient Price History (< 15 prices)**:
   - *Observation*: 14 differences require 15 prices ($period + 1$). Lists of size 0, 1, or 14 previously caused negative indexing or index out of bounds.
   - *Logic*: Input guard `if (prices == null || period <= 0 || prices.size() <= period) return 50.0;` safely yields neutral 50.0.
   - *Test Design*: `testRsi_InsufficientPriceHistory_ReturnsNeutral50` tests empty lists, 1-element lists, 14-element lists, null, and non-positive periods.

5. **Known Price Sequences with Predetermined Exact Values**:
   - *Case A (Balanced Gains/Losses)*:
     15 prices alternating $+2.0$ and $-2.0$: $7$ gains of $+2.0$ ($14.0$), $7$ losses of $-2.0$ ($14.0$).
     $$\text{avgGain} = 1.0, \quad \text{avgLoss} = 1.0 \implies RS = 1.0 \implies RSI = 100 - (100 / 2) = 50.0$$
   - *Case B (3:1 Gain to Loss Ratio)*:
     15 prices with $7$ gains of $+3.0$ ($21.0$) and $7$ losses of $-1.0$ ($7.0$).
     $$\text{avgGain} = 1.5, \quad \text{avgLoss} = 0.5 \implies RS = 3.0 \implies RSI = 100 - (100 / 4) = 75.0$$
   - *Case C (1:3 Gain to Loss Ratio)*:
     15 prices with $7$ gains of $+1.0$ ($7.0$) and $7$ losses of $-3.0$ ($21.0$).
     $$\text{avgGain} = 0.5, \quad \text{avgLoss} = 1.5 \implies RS = 1/3 \implies RSI = 100 - (100 / (4/3)) = 25.0$$
   - *Test Design*: `testRsi_KnownSequence_BalancedGainsLosses_Returns50`, `testRsi_KnownSequence_ThreeToOneRatio_Returns75`, `testRsi_KnownSequence_OneToThreeRatio_Returns25`.

6. **History Windowing**:
   - *Observation*: Buffer holds up to 50 candles. RSI must compute only over the last $period + 1$ prices.
   - *Logic*: Prepending 10 noisy candles before the 15-candle 75.0 sequence must still produce 75.0.
   - *Test Design*: `testRsi_BufferWindowing_OnlyEvaluatesLastPeriodPlusOnePrices`.

---

### 2.2 Design of Unit Tests for `calculateEMA`

1. **Convergence Test Against SMA Seed**:
   - *Observation*: In quantitative finance, an $N$-period EMA seeded with an $N$-point SMA must equal that SMA when the dataset length exactly equals $N$ (no smoothing iterations yet).
   - *Logic*:
     For period 9, 9 prices $[10.0, 20.0, \dots, 90.0]$ have sum $450.0$, SMA $= 50.0$.
     For period 21, 21 prices $[10.0, 11.0, \dots, 30.0]$ have sum $420.0$, SMA $= 20.0$.
   - *Test Design*: `testEma_ConvergenceAgainstSmaSeed` verifies `calculateEMA(prices9, 9) == 50.0` and `calculateEMA(prices21, 21) == 20.0`.

2. **Preservation of Constant Series**:
   - *Observation*: When all prices are identical ($150.0$), the SMA seed is $150.0$, and every subsequent step evaluates to:
     $$(150.0 - 150.0) \times \alpha + 150.0 = 150.0$$
   - *Test Design*: `testEma_FlatPrices_PreservesConstantValue`.

3. **9-Period EMA Verification Against Hand-Calculated Data**:
   - *Price Series*: 11 prices $[10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 20.0, 22.0]$.
   - *Multiplier*: $\alpha = \frac{2}{9 + 1} = 0.2$.
   - *Step 1 (SMA Seed over prices $0 \dots 8$)*:
     $$\text{Sum} = 10 + 11 + 12 + 13 + 14 + 15 + 16 + 17 + 18 = 126.0 \implies \text{EMA}_8 = 14.0$$
   - *Step 2 (Price 9 = 20.0)*:
     $$\text{EMA}_9 = (20.0 - 14.0) \times 0.2 + 14.0 = 1.2 + 14.0 = 15.2$$
   - *Step 3 (Price 10 = 22.0)*:
     $$\text{EMA}_{10} = (22.0 - 15.2) \times 0.2 + 15.2 = 1.36 + 15.2 = 16.56$$
   - *Test Design*: `testEma9_VerificationAgainstKnownData` verifies `calculateEMA(prices, 9) == 16.56`.

4. **21-Period EMA Verification Against Hand-Calculated Data**:
   - *Price Series*: 21 prices of $50.0$, followed by price 21 ($61.0$) and price 22 ($73.0$).
   - *Multiplier*: $\alpha = \frac{2}{21 + 1} = \frac{2}{22} = \frac{1}{11}$.
   - *Step 1 (SMA Seed over prices $0 \dots 20$)*: $\text{EMA}_{20} = 50.0$.
   - *Step 2 (Price 21 = 61.0)*:
     $$\text{EMA}_{21} = (61.0 - 50.0) \times \frac{1}{11} + 50.0 = 1.0 + 50.0 = 51.0$$
   - *Step 3 (Price 22 = 73.0)*:
     $$\text{EMA}_{22} = (73.0 - 51.0) \times \frac{1}{11} + 51.0 = 2.0 + 51.0 = 53.0$$
   - *Test Design*: `testEma21_VerificationAgainstKnownData` verifies `calculateEMA(prices, 21) == 53.0`.

5. **Insufficient History Fallback**:
   - *Observation*: If $N < period$ (e.g. 5 prices for period 9), calculating EMA must not throw `IndexOutOfBoundsException`.
   - *Logic*: Fallback to SMA of available elements: $(10 + 20 + 30 + 40 + 50) / 5 = 30.0$.
   - *Test Design*: `testEma_InsufficientHistory_FallbackToSma`.

---

### 2.3 Design of Tests for Dynamic Position Sizing

1. **First Trade Allocation**:
   - Capital: ₹10,000, 5 open slots, stock LTP: ₹500.
   - $\text{capitalPerSlot} = 10000 / 5 = ₹2,000$.
   - $\text{qty} = (int)(2000 / 500) = 4$ shares. Cost = ₹2,000.
   - *Test Design*: `testCalculatePositionSize_FirstTradeAllocation`.

2. **Second Trade Execution from Remaining Capital**:
   - Remaining capital: ₹8,000, 4 open slots, stock LTP: ₹1,000.
   - $\text{capitalPerSlot} = 8000 / 4 = ₹2,000$.
   - $\text{qty} = (int)(2000 / 1000) = 2$ shares. Cost = ₹2,000.
   - *Test Design*: `testCalculatePositionSize_SecondTradeFromRemainingCapital`.

3. **Minimum 1 Share Rule when `availableCapital >= LTP`**:
   - Capital: ₹3,500, 2 open slots, stock LTP: ₹2,500.
   - $\text{capitalPerSlot} = 3500 / 2 = ₹1,750$.
   - Integer division gives $(int)(1750 / 2500) = 0$.
   - Because `availableCapital >= LTP` ($3500 \ge 2500$), rule forces $\text{qty} = 1$.
   - *Test Design*: `testCalculatePositionSize_MinOneShareWhenCapitalGteLtp`.

4. **Max Position Limit Guard**:
   - 0 open slots (all 5 positions filled) or negative open slots.
   - Must return 0 shares.
   - *Test Design*: `testCalculatePositionSize_MaxPositionLimitGuard_ZeroSlots`, `testCalculatePositionSize_MaxPositionLimitGuard_NegativeSlots`.

5. **Rejection when Capital < LTP**:
   - Capital: ₹400, 1 open slot, stock LTP: ₹500.
   - Wallet cannot fund 1 share. Must reject with $\text{qty} = 0$.
   - High LTP stock: Capital ₹10,000, LTP ₹130,000 (MRF). Must reject with $\text{qty} = 0$.
   - *Test Design*: `testCalculatePositionSize_RejectionWhenCapitalLessThanLtp`, `testCalculatePositionSize_HighLtpStockRejection`.

6. **Sequential Multi-Trade Execution Pipeline**:
   - Trace across 5 successive trades until capital depletion:
     - Trade 1 (LTP 500): 4 shares (cost ₹2,000, remaining ₹8,000)
     - Trade 2 (LTP 1000): 2 shares (cost ₹2,000, remaining ₹6,000)
     - Trade 3 (LTP 3000): 1 share (cost ₹3,000, remaining ₹3,000)
     - Trade 4 (LTP 500): 3 shares (cost ₹1,500, remaining ₹1,500)
     - Trade 5 (LTP 1200): 1 share (cost ₹1,200, remaining ₹300)
     - Trade 6: 0 open slots -> rejected (0 shares)
     - Trade 7 (LTP 500, capital 300): rejected (0 shares)
   - *Test Design*: `testMultiTrade_DynamicSizing_AcrossConsecutiveTrades`.

---

### 2.4 Design of Tests for Exit Conditions (`checkExitConditions`)

1. **Target Price Hit (+3.0%)**:
   - Entry: ₹1,000.0 $\implies$ Target: ₹1,030.0, StopLoss: ₹985.0.
   - When LTP reaches ₹1,030.0, `ltp >= pos.getTarget()` evaluates to `true`.
   - `ledgerService.executeSell(pos, 1030.0)` must be invoked once.
   - *Test Design*: `testCheckExitConditions_TargetHit_TriggersSell`.

2. **Target Price Exceeded (Gap Up +4.0%)**:
   - LTP reaches ₹1,040.0.
   - `ledgerService.executeSell(pos, 1040.0)` must be invoked with the actual execution exit price.
   - *Test Design*: `testCheckExitConditions_TargetExceeded_TriggersSellAtExitPrice`.

3. **Stop Loss Price Hit (-1.5%)**:
   - Entry: ₹3,000.0 $\implies$ StopLoss: ₹2,955.0.
   - When LTP drops to ₹2,955.0, `ltp <= pos.getStopLoss()` evaluates to `true`.
   - `ledgerService.executeSell(pos, 2955.0)` must be invoked once.
   - *Test Design*: `testCheckExitConditions_StopLossHit_TriggersSell`.

4. **Stop Loss Exceeded (Gap Down -2.5%)**:
   - LTP drops to ₹2,925.0 (< ₹2,955.0).
   - `ledgerService.executeSell(pos, 2925.0)` must be invoked once.
   - *Test Design*: `testCheckExitConditions_StopLossExceeded_TriggersSellAtExitPrice`.

5. **Price Within Boundaries (Hold)**:
   - When LTP is inside $(985.0, 1030.0)$ (e.g. ₹1,000.0, ₹1,015.0, ₹990.0), no exit must be triggered.
   - `verify(ledgerService, never()).executeSell(any(), anyDouble())`.
   - *Test Design*: `testCheckExitConditions_WithinBoundaries_DoesNotTriggerSell`.

6. **No Active Position for Symbol**:
   - `positionRepository.findBySymbol` returns `Optional.empty()`.
   - `executeSell` must never be called.
   - *Test Design*: `testCheckExitConditions_NoPosition_DoesNotTriggerSell`.

---

### 2.5 Design of Concurrency Stress Tests

- *Objective*: Prove that replacing `ArrayList` with `CopyOnWriteArrayList` in `candleCloses` prevents `ConcurrentModificationException`.
- *Method*: 4 writer threads simulating 5m candle closes (`addCandleClose` / `remove(0)`) running concurrently with 4 reader threads querying `getCandleCloses`, `calculateRSI`, and `calculateEMA` across 500 iterations per thread.
- *Assertion*: Zero exceptions thrown, latch countdown completed within 10 seconds, and rolling buffer bounded to $\le 50$ candles.
- *Test Design*: `testCandleCloses_ConcurrentReadWrite_ThreadSafe`.

---

## 3. Caveats

1. **Method Visibility Requirements in `StrategyEngine.java`**:
   - In the existing code, `calculateRSI`, `calculateEMA`, and `checkExitConditions` are private.
   - For unit testing in `src/test/java/com/telestock/strategy/StrategyEngineTest.java`, these methods must be made `public` (or package-private). Explorer M2-1 and M2-2 have both designed them as `public`, which is fully backwards-compatible and allows direct invocation.
2. **Accessors for Testing & Telemetry**:
   - To verify concurrency and allow simulated candle seeding without waiting 5 real minutes, `StrategyEngine` must provide:
     - `public void addCandleClose(String symbol, double close)`
     - `public List<Double> getCandleCloses(String symbol)`
   - These methods also directly satisfy Milestone 3 telemetry requirements (`GET /api/strategy/status`).
3. **Execution Environment**:
   - Read-only constraint: Explorer M2-3 did not edit `src/main/` or `src/test/`. The full proposed test code is provided below and saved in `c:\Users\keval\teleStock\.agents\teamwork\explorer_m2_3\proposed_StrategyEngineTest.java`.

---

## 4. Conclusion & Proposed Test Implementation

### 4.1 Test Suite Inventory
The test suite consists of **25 distinct test methods** structured into 6 nested categories:

| Category | Method Name | Verification Objective |
|:---|:---|:---|
| **RSI** | `testRsi_FlatPrices_ReturnsNeutral50` | Flat prices return 50.0 instead of 100.0 |
| **RSI** | `testRsi_PureUpwardTrend_Returns100` | Pure positive changes return 100.0 |
| **RSI** | `testRsi_PureDownwardTrend_Returns0` | Pure negative changes return 0.0 |
| **RSI** | `testRsi_InsufficientPriceHistory_ReturnsNeutral50` | Input $< 15$, empty, null returns 50.0 |
| **RSI** | `testRsi_KnownSequence_BalancedGainsLosses_Returns50` | Alternating $+2/-2$ yields exact 50.0 |
| **RSI** | `testRsi_KnownSequence_ThreeToOneRatio_Returns75` | 3:1 gain/loss ratio yields exact 75.0 |
| **RSI** | `testRsi_KnownSequence_OneToThreeRatio_Returns25` | 1:3 gain/loss ratio yields exact 25.0 |
| **RSI** | `testRsi_BufferWindowing_OnlyEvaluatesLastPeriodPlusOnePrices` | Only evaluates last 15 elements in longer buffer |
| **EMA** | `testEma_ConvergenceAgainstSmaSeed` | When $N == \text{period}$, EMA equals SMA seed |
| **EMA** | `testEma_FlatPrices_PreservesConstantValue` | Constant series preserves constant EMA |
| **EMA** | `testEma9_VerificationAgainstKnownData` | 9-period EMA after 11 prices equals 16.56 |
| **EMA** | `testEma21_VerificationAgainstKnownData` | 21-period EMA after 23 prices equals 53.0 |
| **EMA** | `testEma_InsufficientHistory_FallbackToSma` | $N < \text{period}$ falls back to SMA |
| **EMA** | `testEma_EdgeCases_NullEmptyInvalidPeriod` | Null, empty, non-positive period returns 0.0 |
| **Sizing** | `testCalculatePositionSize_FirstTradeAllocation` | Capital / 5 slots = ₹2,000 $\to$ 4 shares @ ₹500 |
| **Sizing** | `testCalculatePositionSize_SecondTradeFromRemainingCapital` | Remaining / 4 slots = ₹2,000 $\to$ 2 shares @ ₹1,000 |
| **Sizing** | `testCalculatePositionSize_MinOneShareWhenCapitalGteLtp` | Slot capital < LTP but capital $\ge$ LTP yields 1 share |
| **Sizing** | `testCalculatePositionSize_MaxPositionLimitGuard_ZeroSlots` | 0 open slots returns 0 shares |
| **Sizing** | `testCalculatePositionSize_MaxPositionLimitGuard_NegativeSlots` | Negative open slots returns 0 shares |
| **Sizing** | `testCalculatePositionSize_RejectionWhenCapitalLessThanLtp` | Capital ₹400 < LTP ₹500 returns 0 shares |
| **Sizing** | `testCalculatePositionSize_HighLtpStockRejection` | LTP ₹130,000 > wallet ₹10,000 returns 0 shares |
| **Sizing** | `testCalculatePositionSize_HighLtpStockExecutionWhenCapitalSufficient` | Capital ₹150,000 allows buying 1 share of ₹130,000 stock |
| **Sizing** | `testCalculatePositionSize_ZeroOrNegativeCapital` | Zero/negative capital returns 0 shares |
| **Sizing** | `testCalculatePositionSize_ZeroOrNegativeLtp` | Zero/negative LTP returns 0 shares |
| **Sizing** | `testCalculatePositionSize_SafetyClampAgainstCapitalExceedance` | Cost never exceeds total capital |
| **Exit** | `testCheckExitConditions_TargetHit_TriggersSell` | Target hit (+3.0%) triggers `executeSell` |
| **Exit** | `testCheckExitConditions_TargetExceeded_TriggersSellAtExitPrice` | Target gap-up triggers `executeSell` with exit price |
| **Exit** | `testCheckExitConditions_StopLossHit_TriggersSell` | Stop loss hit (-1.5%) triggers `executeSell` |
| **Exit** | `testCheckExitConditions_StopLossExceeded_TriggersSellAtExitPrice` | Stop loss gap-down triggers `executeSell` with exit price |
| **Exit** | `testCheckExitConditions_WithinBoundaries_DoesNotTriggerSell` | Price inside range holds without selling |
| **Exit** | `testCheckExitConditions_NoPosition_DoesNotTriggerSell` | No active position does not sell |
| **Concurrency** | `testCandleCloses_ConcurrentReadWrite_ThreadSafe` | 8 concurrent threads executing 4,000 operations without CME |
| **Integration** | `testEvaluateSignals_TradingDisabled_SkipsProcessing` | Disabled trading skips market polling & execution |
| **Integration** | `testEvaluateSignals_ChecksExitConditions` | Polled market data checks exit conditions on open positions |
| **Integration** | `testMultiTrade_DynamicSizing_AcrossConsecutiveTrades` | Full sequential 5-trade execution pipeline |

---

### 4.2 Complete Source Code for `src/test/java/com/telestock/strategy/StrategyEngineTest.java`

```java
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
            List<Double> flatPrices = Collections.nCopies(15, 100.0);

            double rsi = strategyEngine.calculateRSI(flatPrices, 14);

            assertEquals(50.0, rsi, 0.0001, "Flat prices must yield neutral 50.0 RSI");
        }

        @Test
        @DisplayName("Pure upward trend should return 100.0")
        void testRsi_PureUpwardTrend_Returns100() {
            List<Double> upPrices = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                upPrices.add(100.0 + i * 2.0);
            }

            double rsi = strategyEngine.calculateRSI(upPrices, 14);

            assertEquals(100.0, rsi, 0.0001, "Pure upward trend must yield 100.0 RSI");
        }

        @Test
        @DisplayName("Pure downward trend should return 0.0")
        void testRsi_PureDownwardTrend_Returns0() {
            List<Double> downPrices = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                downPrices.add(200.0 - i * 2.0);
            }

            double rsi = strategyEngine.calculateRSI(downPrices, 14);

            assertEquals(0.0, rsi, 0.0001, "Pure downward trend must yield 0.0 RSI");
        }

        @Test
        @DisplayName("Insufficient price history (< 15 prices for period 14) returns neutral 50.0")
        void testRsi_InsufficientPriceHistory_ReturnsNeutral50() {
            assertEquals(50.0, strategyEngine.calculateRSI(Collections.emptyList(), 14), 0.0001);
            assertEquals(50.0, strategyEngine.calculateRSI(List.of(100.0), 14), 0.0001);

            List<Double> fourteenPrices = new ArrayList<>();
            for (int i = 0; i < 14; i++) {
                fourteenPrices.add(100.0 + i);
            }
            assertEquals(50.0, strategyEngine.calculateRSI(fourteenPrices, 14), 0.0001);

            assertEquals(50.0, strategyEngine.calculateRSI(null, 14), 0.0001);
            assertEquals(50.0, strategyEngine.calculateRSI(fourteenPrices, 0), 0.0001);
            assertEquals(50.0, strategyEngine.calculateRSI(fourteenPrices, -5), 0.0001);
        }

        @Test
        @DisplayName("Known sequence with equal gains and losses returns exactly 50.0")
        void testRsi_KnownSequence_BalancedGainsLosses_Returns50() {
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

            assertEquals(75.0, rsi, 0.0001);
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
            List<Double> prices9 = List.of(10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0, 90.0);

            double ema9 = strategyEngine.calculateEMA(prices9, 9);
            assertEquals(50.0, ema9, 0.0001, "When N == period, EMA must equal the SMA seed");

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
            List<Double> prices = List.of(
                    10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 20.0, 22.0
            );

            double ema9 = strategyEngine.calculateEMA(prices, 9);

            assertEquals(16.56, ema9, 0.0001, "EMA(9) after 11 prices must equal 16.56");
        }

        @Test
        @DisplayName("21-period EMA verification against known calculated data")
        void testEma21_VerificationAgainstKnownData() {
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

            int qty = strategyEngine.calculatePositionSize(ltp, capital, openSlots);

            assertEquals(4, qty, "First trade should allocate capital / 5 slots = ₹2,000 -> 4 shares @ ₹500");
        }

        @Test
        @DisplayName("Second trade execution from remaining capital: 4 open slots, ₹8,000 capital, LTP ₹1,000 -> 2 shares")
        void testCalculatePositionSize_SecondTradeFromRemainingCapital() {
            double capital = 8000.0;
            int openSlots = 4;
            double ltp = 1000.0;

            int qty = strategyEngine.calculatePositionSize(ltp, capital, openSlots);

            assertEquals(2, qty, "Second trade should allocate capital / 4 slots = ₹2,000 -> 2 shares @ ₹1,000");
        }

        @Test
        @DisplayName("Minimum 1 share rule: Slot allocation < LTP, but availableCapital >= LTP -> 1 share")
        void testCalculatePositionSize_MinOneShareWhenCapitalGteLtp() {
            double capital = 3500.0;
            int openSlots = 2;
            double ltp = 2500.0;

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
            assertEquals(1030.0, position.getTarget(), 0.0001);

            when(positionRepository.findBySymbol(symbol)).thenReturn(Optional.of(position));

            strategyEngine.checkExitConditions(symbol, 1030.0);

            verify(ledgerService, times(1)).executeSell(position, 1030.0);
        }

        @Test
        @DisplayName("Target price exceeded (+4.0% gap up) triggers ledgerService.executeSell at exit price")
        void testCheckExitConditions_TargetExceeded_TriggersSellAtExitPrice() {
            String symbol = "INFY.NS";
            Position position = createSamplePosition(symbol, 1000.0);

            when(positionRepository.findBySymbol(symbol)).thenReturn(Optional.of(position));

            strategyEngine.checkExitConditions(symbol, 1040.0);

            verify(ledgerService, times(1)).executeSell(position, 1040.0);
        }

        @Test
        @DisplayName("Stop loss price (-1.5%) hit triggers ledgerService.executeSell")
        void testCheckExitConditions_StopLossHit_TriggersSell() {
            String symbol = "TCS.NS";
            double entryPrice = 3000.0;
            Position position = createSamplePosition(symbol, entryPrice);
            assertEquals(2955.0, position.getStopLoss(), 0.0001);

            when(positionRepository.findBySymbol(symbol)).thenReturn(Optional.of(position));

            strategyEngine.checkExitConditions(symbol, 2955.0);

            verify(ledgerService, times(1)).executeSell(position, 2955.0);
        }

        @Test
        @DisplayName("Stop loss exceeded (-2.5% gap down) triggers ledgerService.executeSell at lower exit price")
        void testCheckExitConditions_StopLossExceeded_TriggersSellAtExitPrice() {
            String symbol = "TCS.NS";
            Position position = createSamplePosition(symbol, 3000.0);

            when(positionRepository.findBySymbol(symbol)).thenReturn(Optional.of(position));

            strategyEngine.checkExitConditions(symbol, 2925.0);

            verify(ledgerService, times(1)).executeSell(position, 2925.0);
        }

        @Test
        @DisplayName("LTP within boundaries (between SL and Target) does NOT trigger sell")
        void testCheckExitConditions_WithinBoundaries_DoesNotTriggerSell() {
            String symbol = "RELIANCE.NS";
            Position position = createSamplePosition(symbol, 2000.0);

            when(positionRepository.findBySymbol(symbol)).thenReturn(Optional.of(position));

            strategyEngine.checkExitConditions(symbol, 2000.0);
            strategyEngine.checkExitConditions(symbol, 2050.0);
            strategyEngine.checkExitConditions(symbol, 1975.0);

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

            startLatch.countDown();
            boolean finishedInTime = doneLatch.await(10, TimeUnit.SECONDS);
            executor.shutdown();

            assertTrue(finishedInTime, "Concurrent tasks should finish within 10 seconds");
            assertEquals(0, errorCount.get(), "Thread safety failed: ConcurrentModificationException occurred");

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
            double ltp = 1040.0;
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

            verify(ledgerService, times(1)).executeSell(pos, ltp);
        }

        @Test
        @DisplayName("Sequential multi-trade execution verifies dynamic capital decrement across multiple buys")
        void testMultiTrade_DynamicSizing_AcrossConsecutiveTrades() {
            int qty1 = strategyEngine.calculatePositionSize(500.0, 10000.0, 5);
            assertEquals(4, qty1);

            double capitalAfterTrade1 = 10000.0 - (qty1 * 500.0);
            assertEquals(8000.0, capitalAfterTrade1);

            int qty2 = strategyEngine.calculatePositionSize(1000.0, capitalAfterTrade1, 4);
            assertEquals(2, qty2);

            double capitalAfterTrade2 = capitalAfterTrade1 - (qty2 * 1000.0);
            assertEquals(6000.0, capitalAfterTrade2);

            int qty3 = strategyEngine.calculatePositionSize(3000.0, capitalAfterTrade2, 3);
            assertEquals(1, qty3);

            double capitalAfterTrade3 = capitalAfterTrade2 - (qty3 * 3000.0);
            assertEquals(3000.0, capitalAfterTrade3);

            int qty4 = strategyEngine.calculatePositionSize(500.0, capitalAfterTrade3, 2);
            assertEquals(3, qty4);

            double capitalAfterTrade4 = capitalAfterTrade3 - (qty4 * 500.0);
            assertEquals(1500.0, capitalAfterTrade4);

            int qty5 = strategyEngine.calculatePositionSize(1200.0, capitalAfterTrade4, 1);
            assertEquals(1, qty5);

            double capitalAfterTrade5 = capitalAfterTrade4 - (qty5 * 1200.0);
            assertEquals(300.0, capitalAfterTrade5);

            int qty6 = strategyEngine.calculatePositionSize(100.0, capitalAfterTrade5, 0);
            assertEquals(0, qty6, "Trade 6 must be blocked by max position limit guard (0 open slots)");

            int qty7 = strategyEngine.calculatePositionSize(500.0, capitalAfterTrade5, 1);
            assertEquals(0, qty7, "Trade 7 must be rejected because capital ₹300 < LTP ₹500");
        }
    }
}
```

---

## 5. Verification Method

### 5.1 Verification Commands
Once the implementation from Explorer M2-1 and M2-2 is applied to `StrategyEngine.java` and `proposed_StrategyEngineTest.java` is written to `src/test/java/com/telestock/strategy/StrategyEngineTest.java`:

```powershell
# 1. Compile the test classes to verify interface and method signature compliance
./mvnw.cmd test-compile

# 2. Execute the dedicated StrategyEngineTest suite
./mvnw.cmd test -Dtest=StrategyEngineTest

# 3. Execute the full project test suite to verify no regressions
./mvnw.cmd test
```

### 5.2 Files to Inspect
- `src/main/java/com/telestock/strategy/StrategyEngine.java`: Verify method signatures:
  - `public double calculateEMA(List<Double> prices, int period)`
  - `public double calculateRSI(List<Double> prices, int period)`
  - `public int calculatePositionSize(double ltp, double availableCapital, int openSlots)`
  - `public void checkExitConditions(String symbol, double ltp)`
  - `public void addCandleClose(String symbol, double close)`
  - `public List<Double> getCandleCloses(String symbol)`
- `src/test/java/com/telestock/strategy/StrategyEngineTest.java`: Verify all 25 tests are present and passing.

### 5.3 Invalidation Conditions
The test suite or underlying engine implementation is invalidated if:
1. `calculateRSI` returns $100.0$ when provided identical prices (flat market defect persists).
2. `calculateRSI` throws `IndexOutOfBoundsException` when provided 14 prices (size guard missing).
3. `calculateEMA` with 11 prices and period 9 does not yield $16.56 \pm 0.0001$ (SMA seed or smoothing recurrence flawed).
4. `calculatePositionSize` allows orders when `availableCapital < ltp` or `openSlots <= 0`.
5. High-LTP stocks with $LTP > 10000$ throw exceptions or force orders exceeding wallet capital.
6. Target price $+3.0\%$ or Stop Loss $-1.5\%$ fail to call `ledgerService.executeSell`.
7. Concurrent candle storage read/write throws `ConcurrentModificationException`.
