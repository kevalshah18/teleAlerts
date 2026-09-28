# Milestone 2 Investigation & Design Report: Dynamic Position Sizing & Concurrency in `StrategyEngine.java`

**Agent**: Explorer M2-2  
**Date**: 2026-09-27  
**Working Directory**: `c:\Users\keval\teleStock\.agents\teamwork\explorer_m2_2\`  
**Target File**: `src/main/java/com/telestock/strategy/StrategyEngine.java`  

---

## 1. Observation

### 1.1 Position Sizing Code in `StrategyEngine.java`
From `src/main/java/com/telestock/strategy/StrategyEngine.java` (lines 72–88):
```java
// Crossover buy logic
if (ema9 > ema21 && rsi14 >= 45 && rsi14 <= 65) {
    if (positionRepository.findBySymbol(symbol).isEmpty()) {
        log.info("Candidate BUY Signal for {}", symbol);
        CandidateSignal signal = new CandidateSignal(symbol, ltp, "BUY", rsi14, LocalDateTime.now());
        
        // AI Veto
        GeminiAiService.GeminiDecision decision = aiService.evaluateSignal(symbol, ltp, "BUY");
        if (decision.isApproval()) {
            int qty = (int) (10000 / ltp); // Simulate 10000 capital per trade
            if (qty == 0) qty = 1;
            ledgerService.executeBuy(symbol, ltp, qty, decision.getReasoning());
        }
    }
}
```

### 1.2 Capital Verification in `LedgerService.java`
From `src/main/java/com/telestock/ledger/LedgerService.java` (lines 24–36):
```java
public void executeBuy(String symbol, double price, int quantity, String geminiReasoning) {
    SystemConfig config = configService.getConfig();
    double buyValue = price * quantity;
    
    if (config.getAvailableCapital() < buyValue) {
        log.warn("Insufficient capital to buy {} shares of {}. Needed: {}, Available: {}", quantity, symbol, buyValue, config.getAvailableCapital());
        return;
    }
    
    // Deduct capital
    config.setAvailableCapital(config.getAvailableCapital() - buyValue);
    configService.updateConfig(config);
...
```

### 1.3 Default Capital in `SystemConfig.java`
From `src/main/java/com/telestock/model/SystemConfig.java` (lines 9–14):
```java
public class SystemConfig {
    @Id
    private Long id = 1L;
    private Boolean tradingEnabled = true;
    private Double availableCapital = 10000.0;
}
```

### 1.4 Candle Closes Storage in `StrategyEngine.java`
From `src/main/java/com/telestock/strategy/StrategyEngine.java`:
- Line 19:
  ```java
  import java.util.ArrayList;
  ```
- Lines 34–35:
  ```java
  // Store 5-min candle close prices
  private final Map<String, List<Double>> candleCloses = new ConcurrentHashMap<>();
  ```
- Lines 56–61:
  ```java
  if (ChronoUnit.MINUTES.between(candle.startTime, LocalDateTime.now()) >= 5) {
      List<Double> closes = candleCloses.computeIfAbsent(symbol, k -> new ArrayList<>());
      closes.add(candle.close);
      if (closes.size() > 50) {
          closes.remove(0); // keep last 50 candles
      }
  ```

### 1.5 Contract Requirements from `PROJECT.md`
- Line 29:
  `| 6 | Dynamic Position Sizing & Capital Fix | Eliminate hardcoded 10000 / ltp order sizing lock; implement dynamic slot sizing (availableCapital / openSlots) allowing multi-trade execution. | M2 |`
- Line 30:
  `| 7 | Thread-Safe Candle Storage | Migrate candleCloses in StrategyEngine to use thread-safe CopyOnWriteArrayList to prevent ConcurrentModificationException. | M2 |`
- Lines 72–75:
  `- SystemConfig.availableCapital: dynamically decremented on buy, incremented on sell.`
  `- Position sizing: qty = (int)(availableCapital / openSlots / ltp). If qty == 0 && availableCapital >= ltp, qty = 1.`

---

## 2. Logic Chain

### 2.1 Why `(int)(10000 / ltp)` Locks Out All Subsequent Trades
1. **Initial Portfolio Allocation**:
   - `SystemConfig.availableCapital` starts at ₹10,000.0.
   - When the first buy signal occurs (e.g., TCS at ₹3,500), `StrategyEngine` computes `qty = (int)(10000 / 3500) = 2`.
   - `LedgerService.executeBuy` deducts `2 * 3500 = ₹7,000.0`.
   - Remaining available capital in `SystemConfig` becomes `₹10,000 - ₹7,000 = ₹3,000.0`.
2. **Subsequent Signal Lockout**:
   - When a second candidate buy signal arrives (e.g., INFY at ₹1,500):
   - Line 81 in `StrategyEngine` does NOT query available capital. It computes `qty = (int)(10000 / 1500) = 6` shares.
   - The required buy value is `6 * 1500 = ₹9,000.0`.
   - `LedgerService.executeBuy` checks `config.getAvailableCapital() < buyValue` (`₹3,000.0 < ₹9,000.0`).
   - The trade is rejected with: `Insufficient capital to buy 6 shares of INFY. Needed: 9000.0, Available: 3000.0`.
3. **Systemic Lockout**:
   - Because `(int)(10000 / ltp)` unconditionally requests ~₹10,000 of capital for every stock, and the wallet only starts with ₹10,000, **after the first trade is placed, no second trade can ever execute** until the first trade is exited.
   - Even if ₹3,000 was available (which could comfortably purchase 2 shares of INFY), the bot requests ₹9,000 and fails.
   - The bot is thus artificially constricted to a single concurrent trade, violating the multi-trade requirement.

### 2.2 Why Stocks with LTP > 10,000 are Completely Blocked & Cause Errors
1. **Integer Division Truncation**:
   - For any stock with LTP > ₹10,000 (e.g., MRF at ₹130,000, BOSCHLTD at ₹30,000, PAGEIND at ₹36,000):
   - `(int)(10000 / ltp)` evaluates to `0`.
2. **Blind Override to 1**:
   - Line 82 executes `if (qty == 0) qty = 1;`, unconditionally overriding `qty` to `1`.
3. **Execution Failure in `LedgerService`**:
   - `LedgerService.executeBuy` is called with `price = 130000.0, quantity = 1`.
   - `buyValue = 130000.0`.
   - `config.getAvailableCapital() < buyValue` (`10000.0 < 130000.0`) fails.
   - `LedgerService` logs an `Insufficient capital` warning and rejects the order.
4. **Configuration Decoupling Defect**:
   - Even if the user configures `availableCapital = 500,000.0`, `StrategyEngine` still divides by the hardcoded constant `10000`, failing to size positions dynamically according to actual capital.
5. **Zero/Negative Capital Protection Failure**:
   - When available capital is ₹5,000 and LTP is ₹12,000, forcing `qty = 1` blindly issues an order that is doomed to fail, wasting AI API calls and polluting logs.

### 2.3 Mathematical Design of Dynamic Position Sizing
To support up to 5 concurrent positions, prevent zero-quantity orders, and guard against negative capital:

1. **Maximum Concurrent Positions Constant**:
   ```java
   public static final int MAX_CONCURRENT_POSITIONS = 5;
   ```
2. **Slot Availability Check**:
   - Current open positions: `long currentPositions = positionRepository.count();`
   - If `currentPositions >= MAX_CONCURRENT_POSITIONS`:
     - Skip evaluation / candidate buy.
     - Log: `Max concurrent positions (5) reached. Skipping candidate BUY for {symbol}`.
   - Open slots remaining:
     ```java
     int openSlots = (int) (MAX_CONCURRENT_POSITIONS - currentPositions);
     ```
     Since `currentPositions < 5`, `openSlots` is strictly between `1` and `5`.
3. **Pre-AI Capital Guard**:
   - Fetch available capital:
     ```java
     SystemConfig config = configService.getConfig();
     double availableCapital = (config != null && config.getAvailableCapital() != null) ? config.getAvailableCapital() : 0.0;
     ```
   - If `availableCapital <= 0.0` or `availableCapital < ltp`:
     - Skip candidate buy immediately (avoids expensive Gemini AI API calls for orders that cannot be funded).
4. **Dynamic Position Sizing Method**:
   Extract into an isolated, testable method:
   ```java
   public int calculatePositionSize(double ltp, double availableCapital, int openSlots) {
       if (openSlots <= 0 || availableCapital <= 0.0 || ltp <= 0.0 || availableCapital < ltp) {
           return 0;
       }
       double capitalPerSlot = availableCapital / openSlots;
       int qty = (int) (capitalPerSlot / ltp);
       if (qty == 0 && availableCapital >= ltp) {
           qty = 1;
       }
       // Safety clamp against negative capital
       if ((qty * ltp) > availableCapital) {
           qty = (int) (availableCapital / ltp);
       }
       return qty;
   }
   ```
5. **Post-AI Concurrency Re-Check**:
   - Because `aiService.evaluateSignal` makes an outbound HTTP call (which takes several hundred milliseconds), other symbols or scheduler threads could potentially take positions or consume capital in the meantime.
   - Re-check `positionRepository.count()`, refresh `availableCapital`, re-calculate `openSlots`, and calculate `qty = calculatePositionSize(ltp, availableCapital, openSlots)`.
   - If `qty > 0`, call `ledgerService.executeBuy(symbol, ltp, qty, decision.getReasoning())`.
   - If `qty <= 0`, abort order cleanly without calling `LedgerService`.

### 2.4 Mathematical Trace Across 5 Concurrent Slots (Initial Capital ₹10,000)
- **Trade 1** (`TCS`, LTP ₹1,000):
  - `openSlots = 5`, `availableCapital = ₹10,000`.
  - `capitalPerSlot = 10000 / 5 = ₹2,000`.
  - `qty = (int)(2000 / 1000) = 2`. Cost = ₹2,000. Capital left = ₹8,000. Positions = 1.
- **Trade 2** (`INFY`, LTP ₹1,500):
  - `openSlots = 4`, `availableCapital = ₹8,000`.
  - `capitalPerSlot = 8000 / 4 = ₹2,000`.
  - `qty = (int)(2000 / 1500) = 1`. Cost = ₹1,500. Capital left = ₹6,500. Positions = 2.
- **Trade 3** (`RELIANCE`, LTP ₹3,000):
  - `openSlots = 3`, `availableCapital = ₹6,500`.
  - `capitalPerSlot = 6500 / 3 = ₹2,166.67`.
  - `qty = (int)(2166.67 / 3000) = 0`.
  - Check: `qty == 0 && availableCapital >= ltp` (`6500 >= 3000` is TRUE) -> `qty = 1`.
  - Cost = ₹3,000. Capital left = ₹3,500. Positions = 3.
- **Trade 4** (`WIPRO`, LTP ₹500):
  - `openSlots = 2`, `availableCapital = ₹3,500`.
  - `capitalPerSlot = 3500 / 2 = ₹1,750`.
  - `qty = (int)(1750 / 500) = 3`. Cost = ₹1,500. Capital left = ₹2,000. Positions = 4.
- **Trade 5** (`HDFCBANK`, LTP ₹1,600):
  - `openSlots = 1`, `availableCapital = ₹2,000`.
  - `capitalPerSlot = 2000 / 1 = ₹2,000`.
  - `qty = (int)(2000 / 1600) = 1`. Cost = ₹1,600. Capital left = ₹400. Positions = 5.
- **Trade 6** (Any stock):
  - `currentPositions = 5 >= MAX_CONCURRENT_POSITIONS`. Blocked! Max positions reached.
- **Trade 7** (High LTP stock, e.g., MRF at ₹130,000 when wallet is ₹400):
  - `availableCapital < ltp` (`400 < 130000`). Blocked! Returns 0. No negative capital.
- **Trade 8** (High LTP stock, e.g., MRF at ₹130,000 when wallet is ₹150,000):
  - `openSlots = 5`, `capitalPerSlot = 30000`.
  - `qty = 0`. Since `availableCapital >= ltp` (`150000 >= 130000`), `qty = 1`.
  - Cost = ₹130,000. Trade executes cleanly.

### 2.5 Candle Storage Concurrency & `CopyOnWriteArrayList`
1. **The Vulnerability**:
   - `candleCloses` is defined as `Map<String, List<Double>> candleCloses = new ConcurrentHashMap<>()`.
   - `ConcurrentHashMap` ensures thread-safe map bucket operations, but the values are `java.util.ArrayList<Double>`.
   - `ArrayList` is **not thread-safe**.
   - With Spring's 4-thread `ThreadPoolTaskScheduler` (configured in M1), multiple background threads can trigger strategy evaluations, indicator calculations, or candle updates concurrently.
   - In Milestone 3, telemetry endpoints (`GET /api/strategy/status`) and test warm-up triggers (`POST /api/test/warmup`) will inspect and seed `candleCloses` from Web container threads (`http-nio-8080-exec-*`) while `evaluateSignals()` is actively modifying the list.
   - Whenever one thread iterates over or reads `closes` while another thread executes `closes.add()` or `closes.remove(0)`, `ArrayList` throws `ConcurrentModificationException`.
   - Moreover, `closes.remove(0)` invokes `System.arraycopy` under the hood. An unsynchronized read during an array copy can read shifted or null values, resulting in `IndexOutOfBoundsException` or corrupted indicator calculations.
2. **The Remedy**:
   - Replace `new ArrayList<>()` with `new CopyOnWriteArrayList<>()`.
   - In `CopyOnWriteArrayList`:
     - Mutations (`add`, `remove`) create a fresh copy of the internal array under an internal lock.
     - Reads (`get(i)`, `size()`, iterators) operate lock-free on an immutable snapshot of the array at the moment of read.
     - Never throws `ConcurrentModificationException`.
     - Never experiences dirty reads during index shifting.
   - Because candles close only once every 5 minutes per symbol, write throughput is tiny (at most ~0.16 writes/sec across 50 symbols). The array size is bounded to 50 doubles (400 bytes). Copying 50 elements takes single-digit nanoseconds.
   - `CopyOnWriteArrayList` is the optimal concurrent data structure for this read-heavy, low-frequency write workload.

---

## 3. Caveats

1. **Separation from Milestone 2-1 (Indicator Math)**:
   - Explorer M2-1 is modifying `calculateRSI` and `calculateEMA` (lines 100–122 in `StrategyEngine.java`).
   - The changes for M2-2 modify imports, line 35/57 (`CopyOnWriteArrayList`), lines 73–88 (dynamic position sizing and max position limits), and add the helper method `calculatePositionSize`.
   - The line ranges and methods are completely orthogonal. There is zero logical conflict between M2-1 and M2-2.
2. **Access Methods for M3 Telemetry**:
   - Providing a public accessor `getCandleCloses(String symbol)` or `getAllCandleCloses()` simplifies future Milestone 3 telemetry inspection and unit testing without exposing raw mutability.
3. **No Direct Code Modifications**:
   - In strict compliance with the explorer role, no source code or test files were modified directly. All designs and diffs are presented below for the worker agent.

---

## 4. Conclusion & Proposed Code Implementation

### 4.1 Exact Code Diff for `src/main/java/com/telestock/strategy/StrategyEngine.java`

```diff
--- a/src/main/java/com/telestock/strategy/StrategyEngine.java
+++ b/src/main/java/com/telestock/strategy/StrategyEngine.java
@@ -16,10 +16,11 @@
 import java.time.LocalDateTime;
 import java.time.temporal.ChronoUnit;
-import java.util.ArrayList;
+import java.util.Collections;
 import java.util.List;
 import java.util.Map;
 import java.util.concurrent.ConcurrentHashMap;
+import java.util.concurrent.CopyOnWriteArrayList;
 
 @Service
 @RequiredArgsConstructor
@@ -31,6 +32,8 @@
     private final GeminiAiService aiService;
     private final LedgerService ledgerService;
     private final PositionRepository positionRepository;
+    
+    public static final int MAX_CONCURRENT_POSITIONS = 5;
     
     // Store 5-min candle close prices
     private final Map<String, List<Double>> candleCloses = new ConcurrentHashMap<>();
@@ -54,10 +57,10 @@
             
             // If 5 minutes have passed, close the candle
             if (ChronoUnit.MINUTES.between(candle.startTime, LocalDateTime.now()) >= 5) {
-                List<Double> closes = candleCloses.computeIfAbsent(symbol, k -> new ArrayList<>());
+                List<Double> closes = candleCloses.computeIfAbsent(symbol, k -> new CopyOnWriteArrayList<>());
                 closes.add(candle.close);
-                if (closes.size() > 50) {
+                while (closes.size() > 50) {
                     closes.remove(0); // keep last 50 candles
                 }
                 
@@ -72,16 +75,41 @@
                     
                     // Crossover buy logic
                     if (ema9 > ema21 && rsi14 >= 45 && rsi14 <= 65) {
                         if (positionRepository.findBySymbol(symbol).isEmpty()) {
+                            long currentPositions = positionRepository.count();
+                            if (currentPositions >= MAX_CONCURRENT_POSITIONS) {
+                                log.info("Max concurrent positions ({}) reached. Skipping candidate BUY for {}", MAX_CONCURRENT_POSITIONS, symbol);
+                                continue;
+                            }
+                            
+                            SystemConfig config = configService.getConfig();
+                            double availableCapital = (config != null && config.getAvailableCapital() != null) ? config.getAvailableCapital() : 0.0;
+                            if (availableCapital < ltp) {
+                                log.info("Available capital Rs {:.2f} is less than LTP Rs {:.2f} for {}. Skipping candidate BUY.", availableCapital, ltp, symbol);
+                                continue;
+                            }
+                            
                             log.info("Candidate BUY Signal for {}", symbol);
                             CandidateSignal signal = new CandidateSignal(symbol, ltp, "BUY", rsi14, LocalDateTime.now());
                             
                             // AI Veto
                             GeminiAiService.GeminiDecision decision = aiService.evaluateSignal(symbol, ltp, "BUY");
                             if (decision.isApproval()) {
-                                int qty = (int) (10000 / ltp); // Simulate 10000 capital per trade
-                                if (qty == 0) qty = 1;
-                                ledgerService.executeBuy(symbol, ltp, qty, decision.getReasoning());
+                                currentPositions = positionRepository.count();
+                                if (currentPositions >= MAX_CONCURRENT_POSITIONS) {
+                                    log.warn("Max concurrent positions reached during AI veto evaluation for {}. Order aborted.", symbol);
+                                    continue;
+                                }
+                                int openSlots = (int) (MAX_CONCURRENT_POSITIONS - currentPositions);
+                                config = configService.getConfig();
+                                availableCapital = (config != null && config.getAvailableCapital() != null) ? config.getAvailableCapital() : 0.0;
+                                
+                                int qty = calculatePositionSize(ltp, availableCapital, openSlots);
+                                if (qty > 0) {
+                                    ledgerService.executeBuy(symbol, ltp, qty, decision.getReasoning());
+                                } else {
+                                    log.warn("Calculated position size is 0 for {}. Order aborted.", symbol);
+                                }
                             }
                         }
                     }
@@ -95,6 +123,28 @@
             }
         });
     }
+    
+    public int calculatePositionSize(double ltp, double availableCapital, int openSlots) {
+        if (openSlots <= 0 || availableCapital <= 0.0 || ltp <= 0.0 || availableCapital < ltp) {
+            return 0;
+        }
+        double capitalPerSlot = availableCapital / openSlots;
+        int qty = (int) (capitalPerSlot / ltp);
+        if (qty == 0 && availableCapital >= ltp) {
+            qty = 1;
+        }
+        if ((qty * ltp) > availableCapital) {
+            qty = (int) (availableCapital / ltp);
+        }
+        return qty;
+    }
+    
+    public List<Double> getCandleCloses(String symbol) {
+        return candleCloses.getOrDefault(symbol, Collections.emptyList());
+    }
+    
+    public Map<String, List<Double>> getAllCandleCloses() {
+        return Collections.unmodifiableMap(candleCloses);
+    }
```

---

## 5. Verification Method

To independently verify the implementation:

### 5.1 Unit Tests for Dynamic Position Sizing
In `src/test/java/com/telestock/strategy/StrategyEngineTest.java`:
1. **Normal First Trade Allocation**:
   - `calculatePositionSize(1000.0, 10000.0, 5)` returns `2` (allocated ₹2,000, 2 shares).
2. **Subsequent Trade from Remaining Capital**:
   - `calculatePositionSize(1500.0, 8000.0, 4)` returns `1` (allocated ₹2,000, 1 share).
3. **Minimum 1 Share Rule when Capital >= LTP**:
   - `calculatePositionSize(3000.0, 6500.0, 3)` returns `1` (capitalPerSlot = ₹2,166.67 < ₹3,000, but available capital ₹6,500 >= ₹3,000, so returns 1).
4. **Max Position Limit Guard**:
   - `calculatePositionSize(500.0, 10000.0, 0)` returns `0`.
   - `calculatePositionSize(500.0, 10000.0, -1)` returns `0`.
5. **Rejection when Capital < LTP**:
   - `calculatePositionSize(1500.0, 1000.0, 2)` returns `0`.
   - `calculatePositionSize(130000.0, 10000.0, 5)` returns `0` (high LTP stock rejection).
6. **High LTP Stock with Sufficient Capital**:
   - `calculatePositionSize(130000.0, 150000.0, 5)` returns `1`.
7. **Negative / Zero Capital Guard**:
   - `calculatePositionSize(500.0, 0.0, 5)` returns `0`.
   - `calculatePositionSize(500.0, -500.0, 5)` returns `0`.

### 5.2 Concurrency Stress Verification
1. **Thread Safety Verification**:
   - Spawn 10 concurrent threads: 5 writer threads simulating 5m candle closes (`closes.add(...)` and `closes.remove(0)`) and 5 reader threads calculating EMA/RSI.
   - Assert that no `ConcurrentModificationException` or `ArrayIndexOutOfBoundsException` occurs over 10,000 operations.

### 5.3 Maven Build & Test Execution
Execute the test command:
```bash
./mvnw test -Dtest=StrategyEngineTest
```
Or for full suite:
```bash
./mvnw test
```

### 5.4 Invalidation Conditions
The fix design would be invalidated if:
1. `openSlots` could ever be negative or zero in the division denominator (prevented by `if (openSlots <= 0) return 0;`).
2. An order with `qty = 0` was forwarded to `ledgerService.executeBuy` (prevented by `if (qty > 0)`).
3. `(qty * ltp)` exceeded `availableCapital` (prevented by safety clamp `if ((qty * ltp) > availableCapital) qty = (int)(availableCapital / ltp)`).
4. Concurrent read/write on `candleCloses` threw `ConcurrentModificationException` (eliminated by `CopyOnWriteArrayList`).
