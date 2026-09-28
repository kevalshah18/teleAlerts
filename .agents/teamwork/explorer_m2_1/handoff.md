# Technical Investigation & Fix Architecture: Milestone 2 Indicator Math

**Agent**: Explorer M2-1  
**Target File**: `src/main/java/com/telestock/strategy/StrategyEngine.java`  
**Working Directory**: `c:\Users\keval\teleStock\.agents\teamwork\explorer_m2_1\`  
**Date**: 2026-09-27  

---

## 1. Observation

### 1.1 Existing Technical Indicator Implementations in `StrategyEngine.java`

Inspection of `src/main/java/com/telestock/strategy/StrategyEngine.java` reveals the implementation of `calculateEMA` and `calculateRSI` between lines 100 and 122:

```java
100:     private double calculateEMA(List<Double> prices, int period) {
101:         double multiplier = 2.0 / (period + 1);
102:         double ema = prices.get(prices.size() - period);
103:         for (int i = prices.size() - period + 1; i < prices.size(); i++) {
104:             ema = (prices.get(i) - ema) * multiplier + ema;
105:         }
106:         return ema;
107:     }
108:     
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

### 1.2 Call Sites and Execution Gate in `StrategyEngine.java`

Lines 57-73 of `src/main/java/com/telestock/strategy/StrategyEngine.java`:
```java
57:                 List<Double> closes = candleCloses.computeIfAbsent(symbol, k -> new ArrayList<>());
58:                 closes.add(candle.close);
59:                 if (closes.size() > 50) {
60:                     closes.remove(0); // keep last 50 candles
61:                 }
...
67:                 if (closes.size() >= 21) {
68:                     double ema9 = calculateEMA(closes, 9);
69:                     double ema21 = calculateEMA(closes, 21);
70:                     double rsi14 = calculateRSI(closes, 14);
71:                     
72:                     // Crossover buy logic
73:                     if (ema9 > ema21 && rsi14 >= 45 && rsi14 <= 65) {
```

### 1.3 Method Visibility & Testability
- Both `calculateEMA` (line 100) and `calculateRSI` (line 109) are marked `private`.
- As a result, unit tests in `src/test/java/com/telestock/strategy/` cannot directly invoke these methods without reflection, and external services (such as the Milestone 3 strategy telemetry endpoint `GET /api/strategy/status`) cannot query current indicator values.

---

## 2. Logic Chain

### 2.1 The Off-by-One Loop Accumulation Defect in `calculateRSI`

1. **Loop Bounds & Iteration Count**:
   - In `calculateRSI(List<Double> prices, int period)`:
     ```java
     for (int i = prices.size() - period; i < prices.size() - 1; i++)
     ```
   - Let $N = \text{prices.size()}$ and $\text{period} = 14$.
   - The initial loop index is $i_{\text{start}} = N - 14$.
   - The loop termination condition is $i < N - 1$, so the final index evaluated is $i_{\text{end}} = N - 2$.
   - The total number of loop iterations is:
     $$\text{iterations} = i_{\text{end}} - i_{\text{start}} + 1 = (N - 2) - (N - 14) + 1 = 14 - 2 + 1 = 13$$
   - Each iteration calculates one price change: $\text{diff} = \text{prices.get}(i+1) - \text{prices.get}(i)$.
   - Therefore, the loop accumulates exactly **13 price differences**.

2. **Mathematical Inconsistency in Normalization**:
   - At lines 117-118:
     ```java
     double avgGain = gains / period; // divided by 14
     double avgLoss = losses / period; // divided by 14
     ```
   - Summing 13 changes and dividing by 14 artificially depresses both `avgGain` and `avgLoss` by $\frac{1}{14} \approx 7.14\%$.

3. **Required Input Length for 14-Period RSI**:
   - By definition, a price difference requires two consecutive price points: $\Delta P_k = P_k - P_{k-1}$.
   - To obtain 14 distinct consecutive differences $(\Delta P_1, \Delta P_2, \dots, \Delta P_{14})$, a sequence of **15 prices** ($P_0, P_1, \dots, P_{14}$) is required:
     $$\text{Required Prices} = \text{period} + 1$$
   - To extract 14 differences from the most recent prices in a list of size $N$, the earliest price accessed must be at index $N - \text{period} - 1$, and the latest price accessed must be at index $N - 1$.
   - Setting the loop index to:
     ```java
     int start = prices.size() - period - 1;
     for (int i = start; i < prices.size() - 1; i++)
     ```
     yields:
     $$i_{\text{start}} = N - 14 - 1 = N - 15$$
     $$i_{\text{end}} = N - 2$$
     $$\text{iterations} = (N - 2) - (N - 15) + 1 = 14$$
   - Each iteration $i$ accesses $P_i$ and $P_{i+1}$, spanning indices from $N - 15$ to $N - 1$ (15 prices), correctly accumulating 14 differences.

4. **Index Out of Bounds on Insufficient History**:
   - If `prices.size() <= period` (e.g., $N = 14$ and $\text{period} = 14$):
     $$\text{start} = 14 - 14 - 1 = -1$$
   - In the absence of an upfront size guard, calling `prices.get(-1)` results in an unhandled `ArrayIndexOutOfBoundsException`.
   - Therefore, an explicit guard must return neutral $50.0$ if $\text{prices} == \text{null}$, $\text{period} \le 0$, or $\text{prices.size()} \le \text{period}$.

---

### 2.2 The Flat-Market Zero-Loss Flaw in `calculateRSI`

1. **Mechanism of Defect**:
   - Line 119 specifies:
     ```java
     if (avgLoss == 0) return 100;
     ```
   - In a completely motionless market (such as during pre-market, post-market, trading halts, or synthetic seeding with zero variance), all closing prices in `prices` are identical:
     $$P_0 = P_1 = \dots = P_N$$
   - For every iteration, $\text{diff} = P_{i+1} - P_i = 0.0$.
   - Consequently:
     $$\text{gains} = 0.0 \implies \text{avgGain} = 0.0$$
     $$\text{losses} = 0.0 \implies \text{avgLoss} = 0.0$$
   - Because `avgLoss == 0.0`, line 119 executes and returns **`100.0`**.

2. **Severe Strategy Consequence**:
   - An RSI of $100.0$ signifies maximal bullish momentum (100% upward price change with 0 pullbacks).
   - In reality, zero price movement represents absolute equilibrium / zero momentum, which must evaluate to the neutral midpoint of **`50.0`**.
   - Furthermore, in `StrategyEngine.java` line 73:
     ```java
     if (ema9 > ema21 && rsi14 >= 45 && rsi14 <= 65)
     ```
   - Because the flat market returns $100.0$, the condition `rsi14 <= 65` fails, preventing the strategy engine from operating appropriately on initial or flat market conditions.

3. **Exact Guard Logic**:
   - To distinguish flat price action from pure upward momentum:
     - Case A: $\text{avgGain} == 0.0 \land \text{avgLoss} == 0.0 \implies \text{return } 50.0$ (Neutral market).
     - Case B: $\text{avgGain} > 0.0 \land \text{avgLoss} == 0.0 \implies \text{return } 100.0$ (Pure monotonic bull trend).
     - Case C: $\text{avgGain} == 0.0 \land \text{avgLoss} > 0.0 \implies \text{return } 0.0$ (Pure monotonic bear trend; correctly computed via $RS = 0 / \text{avgLoss} = 0 \implies 100 - (100 / 1) = 0.0$).

---

### 2.3 Single-Point Seeding & History Truncation in `calculateEMA`

1. **History Truncation Defect**:
   - Lines 102-105:
     ```java
     double ema = prices.get(prices.size() - period);
     for (int i = prices.size() - period + 1; i < prices.size(); i++) {
         ema = (prices.get(i) - ema) * multiplier + ema;
     }
     ```
   - When the candle buffer holds 50 candles ($N = 50$) and `period = 9`:
     $$\text{prices.size()} - \text{period} = 50 - 9 = 41$$
   - The method initializes `ema` to `prices.get(41)` and loops only for $i = 42 \dots 49$ (8 iterations).
   - All 41 earlier candles (indices 0 through 40) are completely discarded. This discards **82% of the available price history**.

2. **Single-Point Seed Distortion**:
   - For an EMA with multiplier $\alpha = \frac{2}{9 + 1} = 0.2$:
     - The weighting of the initial seed price after 8 smoothing iterations is:
       $$W_{\text{seed}} = (1 - \alpha)^8 = (0.8)^8 \approx 0.1678 \quad (16.78\%)$$
     - That means $16.78\%$ of the calculated EMA value depends entirely on a single instantaneous price at index 41. If that candle was an outlier or wick, the entire indicator is contaminated.
   - For `period = 21`:
     - $\alpha = \frac{2}{22} \approx 0.090909$
     - Seed index: $50 - 21 = 29$.
     - Number of iterations: 20 iterations.
     - Seed retention weight: $(1 - \frac{2}{22})^{20} \approx 0.1486 \quad (14.86\%)$.
   - Furthermore, when $N = 21$:
     - `calculateEMA(prices, 21)` starts from index $21 - 21 = 0$.
     - But `calculateEMA(prices, 9)` starts from index $21 - 9 = 12$, completely ignoring prices $0 \dots 11$.
     - This causes an artificial baseline phase divergence between `ema9` and `ema21`.

3. **Standard Quantitative EMA Specification**:
   - The established industry standard (TA-Lib, TradingView, Investopedia) defines the initial EMA seed value as the **Simple Moving Average (SMA)** of the first `period` data points in the available historical series:
     $$\text{EMA}_{\text{seed}} = \frac{1}{\text{period}} \sum_{i=0}^{\text{period}-1} P_i$$
   - Subsequent price points from index $i = \text{period}$ to $N - 1$ are then smoothed using the standard recurrence relation:
     $$\text{EMA}_i = (P_i - \text{EMA}_{i-1}) \times \alpha + \text{EMA}_{i-1} \quad \text{where } \alpha = \frac{2}{\text{period} + 1}$$
   - When $N = 50$ and $\text{period} = 9$:
     - The initial SMA is formed over candles $0 \dots 8$.
     - Smoothing is applied across candles $9 \dots 49$ (41 iterations).
     - The influence of the seed decays to $(0.8)^{41} \approx 0.000096$ ($< 0.01\%$), ensuring the EMA represents the true continuous exponential moving average of the full available dataset.
   - Edge case handling:
     - If $N < \text{period}$, the method falls back to returning the SMA of all available prices ($\frac{1}{N} \sum_{i=0}^{N-1} P_i$), preventing `IndexOutOfBoundsException`.

---

## 3. Caveats

1. **Cutler's RSI vs. Wilder's Smoothed RSI**:
   - The implementation in `StrategyEngine.java` calculates **Cutler's RSI** (a simple moving average of gains and losses over a fixed rolling window of 14 differences) rather than Wilder's smoothed RSI (which uses an exponential moving average of gains and losses over infinite history).
   - This design is retained intentionally: Milestone 2 requirements explicitly specify collecting 14 differences from `period + 1` prices and dividing by `period`. Cutler's RSI is deterministic, converges immediately without requiring 200+ historical bars of warm-up decay, and is ideal for 5-minute candle evaluation with small buffers (50 candles).
2. **Candle Buffer Threshold in `evaluateSignals`**:
   - `StrategyEngine.java` line 67 gates indicator evaluation behind `if (closes.size() >= 21)`.
   - Because $21 \ge 15$ ($14 + 1$ for RSI) and $21 \ge 21$ ($21$ for EMA21), `closes.size() >= 21` guarantees sufficient data points for both indicators during normal engine execution.
   - However, making `calculateRSI` and `calculateEMA` robust against short inputs ($N < 15$ or $N < \text{period}$) is required to ensure that direct unit tests and early telemetry queries do not throw exceptions.
3. **Read-Only Scope**:
   - In accordance with the Explorer role instructions, no source or test files have been modified. The complete drop-in methods and unified patch below are provided for implementers (`worker_m1` / reviewer).

---

## 4. Conclusion & Proposed Code Design

### 4.1 Corrected Code Snippets for `StrategyEngine.java`

Replace lines 100-122 of `src/main/java/com/telestock/strategy/StrategyEngine.java` with the following:

```java
    public double calculateEMA(List<Double> prices, int period) {
        if (prices == null || prices.isEmpty() || period <= 0) {
            return 0.0;
        }
        if (prices.size() < period) {
            double sum = 0.0;
            for (Double p : prices) {
                sum += (p != null ? p : 0.0);
            }
            return sum / prices.size();
        }

        double multiplier = 2.0 / (period + 1);

        // Seed with Simple Moving Average (SMA) of the first 'period' elements
        double sum = 0.0;
        for (int i = 0; i < period; i++) {
            sum += prices.get(i);
        }
        double ema = sum / period;

        // Apply exponential smoothing over all subsequent prices
        for (int i = period; i < prices.size(); i++) {
            ema = (prices.get(i) - ema) * multiplier + ema;
        }

        return ema;
    }

    public double calculateRSI(List<Double> prices, int period) {
        if (prices == null || period <= 0 || prices.size() <= period) {
            return 50.0; // Insufficient history (< period + 1 prices), return neutral
        }

        int start = prices.size() - period - 1;
        double gains = 0.0;
        double losses = 0.0;

        for (int i = start; i < prices.size() - 1; i++) {
            double diff = prices.get(i + 1) - prices.get(i);
            if (diff > 0.0) {
                gains += diff;
            } else if (diff < 0.0) {
                losses += -diff;
            }
        }

        double avgGain = gains / period;
        double avgLoss = losses / period;

        // Flat market guard: no price movement is neutral 50.0, not 100.0
        if (avgGain == 0.0 && avgLoss == 0.0) {
            return 50.0;
        }
        if (avgLoss == 0.0) {
            return 100.0; // Pure upward movement with zero pullbacks
        }

        double rs = avgGain / avgLoss;
        return 100.0 - (100.0 / (1.0 + rs));
    }
```

### 4.2 Unified Diff Patch

```diff
--- a/src/main/java/com/telestock/strategy/StrategyEngine.java
+++ b/src/main/java/com/telestock/strategy/StrategyEngine.java
@@ -100,24 +100,43 @@ public class StrategyEngine {
-    private double calculateEMA(List<Double> prices, int period) {
-        double multiplier = 2.0 / (period + 1);
-        double ema = prices.get(prices.size() - period);
-        for (int i = prices.size() - period + 1; i < prices.size(); i++) {
-            ema = (prices.get(i) - ema) * multiplier + ema;
-        }
-        return ema;
-    }
-    
-    private double calculateRSI(List<Double> prices, int period) {
-        double gains = 0;
-        double losses = 0;
-        for (int i = prices.size() - period; i < prices.size() - 1; i++) {
-            double diff = prices.get(i+1) - prices.get(i);
-            if (diff > 0) gains += diff;
-            else losses -= diff;
-        }
-        double avgGain = gains / period;
-        double avgLoss = losses / period;
-        if (avgLoss == 0) return 100;
-        double rs = avgGain / avgLoss;
-        return 100 - (100 / (1 + rs));
-    }
+    public double calculateEMA(List<Double> prices, int period) {
+        if (prices == null || prices.isEmpty() || period <= 0) {
+            return 0.0;
+        }
+        if (prices.size() < period) {
+            double sum = 0.0;
+            for (Double p : prices) {
+                sum += (p != null ? p : 0.0);
+            }
+            return sum / prices.size();
+        }
+
+        double multiplier = 2.0 / (period + 1);
+
+        // Seed with Simple Moving Average (SMA) of the first 'period' elements
+        double sum = 0.0;
+        for (int i = 0; i < period; i++) {
+            sum += prices.get(i);
+        }
+        double ema = sum / period;
+
+        // Apply exponential smoothing over all subsequent prices
+        for (int i = period; i < prices.size(); i++) {
+            ema = (prices.get(i) - ema) * multiplier + ema;
+        }
+
+        return ema;
+    }
+
+    public double calculateRSI(List<Double> prices, int period) {
+        if (prices == null || period <= 0 || prices.size() <= period) {
+            return 50.0; // Insufficient history (< period + 1 prices), return neutral
+        }
+
+        int start = prices.size() - period - 1;
+        double gains = 0.0;
+        double losses = 0.0;
+
+        for (int i = start; i < prices.size() - 1; i++) {
+            double diff = prices.get(i + 1) - prices.get(i);
+            if (diff > 0.0) {
+                gains += diff;
+            } else if (diff < 0.0) {
+                losses += -diff;
+            }
+        }
+
+        double avgGain = gains / period;
+        double avgLoss = losses / period;
+
+        // Flat market guard: no price movement is neutral 50.0, not 100.0
+        if (avgGain == 0.0 && avgLoss == 0.0) {
+            return 50.0;
+        }
+        if (avgLoss == 0.0) {
+            return 100.0; // Pure upward movement with zero pullbacks
+        }
+
+        double rs = avgGain / avgLoss;
+        return 100.0 - (100.0 / (1.0 + rs));
+    }
```

---

## 5. Verification Method

### 5.1 Verification Test Matrix for `StrategyEngineTest.java`

The following test suite specification should be verified by Explorer M2-3 / Worker M2:

| Test Case | Input | Expected Output | Verification Rationale |
|:---|:---|:---|:---|
| **RSI Flat Market** | 15 prices all `100.0`, period 14 | `50.0` ($\pm 0.001$) | Verifies flat market returns neutral 50.0 instead of 100.0 |
| **RSI Pure Uptrend** | 15 prices: `100.0, 101.0, ..., 114.0`, period 14 | `100.0` ($\pm 0.001$) | Verifies pure gain yields 100.0 without division by zero |
| **RSI Pure Downtrend** | 15 prices: `114.0, 113.0, ..., 100.0`, period 14 | `0.0` ($\pm 0.001$) | Verifies pure loss yields 0.0 |
| **RSI Balanced Fluctuation** | 15 prices: alternating `100.0, 102.0, 100.0, ...`, period 14 | `50.0` ($\pm 0.001$) | Equal gains and losses ($14.0 / 14.0$) produce $RS = 1.0 \implies RSI = 50.0$ |
| **RSI Insufficient History** | 10 prices, period 14 | `50.0` ($\pm 0.001$) | Safely handles $N \le \text{period}$ without `IndexOutOfBoundsException` |
| **RSI Null / Empty / Period <= 0** | `null` or `List.of()` or `period = 0` | `50.0` ($\pm 0.001$) | Defensive boundary validation |
| **EMA Constant Series** | 25 prices all `150.0`, period 9 | `150.0` ($\pm 0.001$) | Seed = 150.0, every smoothing step preserves 150.0 |
| **EMA Seed Convergence** | 9 prices of `10.0` followed by 1 price of `20.0`, period 9 | `12.0` ($\pm 0.001$) | SMA seed = 10.0; $\alpha = 0.2$; $(20 - 10) \times 0.2 + 10 = 12.0$ |
| **EMA Full History Utilization** | 30 prices with gradual trend, period 9 and period 21 | Matches manual step-by-step recurrence | Proves history earlier than `N - period` influences output |
| **EMA Insufficient History** | 5 prices `10, 20, 30, 40, 50`, period 9 | `30.0` ($\pm 0.001$) | Returns SMA fallback $(150 / 5 = 30.0)$ without throwing exception |

### 5.2 Verification Commands

Once the changes are applied to `StrategyEngine.java` and tests are added to `StrategyEngineTest.java`:

```powershell
# Run the specific StrategyEngine unit test suite
./mvnw test -Dtest=StrategyEngineTest

# Run all project unit tests
./mvnw test
```

### 5.3 Invalidation Conditions
- If `calculateRSI` returns `100.0` when given a list of identical prices, the flat market flaw remains active.
- If `calculateRSI` throws `IndexOutOfBoundsException` when given a list of fewer than 15 elements, the input size guard is missing.
- If `calculateEMA` with 50 elements produces identical results whether elements 0 through 40 are zeroes or high prices, history truncation is still occurring.
