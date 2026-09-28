# Comprehensive Survey Report: Trade Execution, Capital Management, Test Suite, and Cloud Readiness

**Agent**: Explorer 3  
**Working Directory**: `c:\Users\keval\teleStock\.agents\teamwork\explorer_survey_3\`  
**Date**: 2026-09-27  

---

## 1. Observation

### O1. Trade Execution & Broker Integration Architecture
- **File**: `c:\Users\keval\teleStock\src\main\java\com\telestock\ledger\LedgerService.java`
  - Lines 24-51 (`executeBuy`):
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
        
        Position position = new Position();
        position.setSymbol(symbol);
        position.setQuantity(quantity);
        position.setEntryPrice(price);
        position.setStopLoss(price * 0.985); // -1.5%
        position.setTarget(price * 1.03);  // +3.0%
        position.setEntryTime(LocalDateTime.now());
        position.setGeminiReasoning(geminiReasoning);
        
        positionRepository.save(position);
        ...
        telegramService.sendMessage(msg);
    }
    ```
  - Lines 53-104 (`executeSell`):
    Computes Indian statutory charges (`brokerageBuy`, `brokerageSell`, `stt` [0.1%], `exchangeCharge` [0.00345%], `sebiCharge` [0.0001%], `stampDuty` [0.015%], `gst` [18% on fees]), saves `TradeRecord` in `TradeRecordRepository`, deletes `Position` from `PositionRepository`, adds `buyValue + netPnl` back to `availableCapital`, and sends Telegram message.
  - **Absence of Broker Integration**: There are zero API calls, SDKs, or credentials for any real broker (e.g. Zerodha Kite, Upstox, Angel One, Groww, Interactive Brokers, Dhan).
  - **No Simulation Toggle**: The application has no `trading.mode` toggle (e.g. `SIMULATION` vs `LIVE`). It operates strictly as an in-memory paper trading simulation.
  - **Manual Test Endpoints**: `c:\Users\keval\teleStock\src\main\java\com\telestock\controller\TestController.java`:
    - Line 22: `@GetMapping("/buy")` forces buy of 1 share of `RELIANCE.NS` at 2500.0.
    - Line 28: `@GetMapping("/sell")` forces sell of the first open position at 2550.0.
    - Line 38: `@GetMapping("/cleanup")` deletes all records from `PositionRepository` and `TradeRecordRepository` without restoring capital.

### O2. Capital Management, Position Sizing, and Execution Blockers
- **File**: `c:\Users\keval\teleStock\src\main\java\com\telestock\model\SystemConfig.java`
  - Lines 9-14:
    ```java
    public class SystemConfig {
        @Id
        private Long id = 1L;
        private Boolean tradingEnabled = true;
        private Double availableCapital = 10000.0;
    }
    ```
  - Total starting capital is set to Rs. 10,000.0. No fields exist for `maxOpenTrades`, `riskPerTradePercentage`, `maxCapitalPerTrade`, or portfolio allocation limits.
- **File**: `c:\Users\keval\teleStock\src\main\java\com\telestock\strategy\StrategyEngine.java`
  - Line 81:
    ```java
    int qty = (int) (10000 / ltp); // Simulate 10000 capital per trade
    if (qty == 0) qty = 1;
    ledgerService.executeBuy(symbol, ltp, qty, decision.getReasoning());
    ```
  - Line 81 always sizes orders assuming Rs. 10,000 capital per trade.
  - If a trade is executed (e.g. 4 shares of Rs. 2,400 = Rs. 9,600), remaining `availableCapital` is Rs. 400.
  - For the next trade, `qty = (int)(10000 / ltp)`. If `ltp` = Rs. 500, `qty` = 20, requiring Rs. 10,000.
  - `LedgerService.java` line 28: `config.getAvailableCapital() < buyValue` (400 < 10000) evaluates to true, aborting the trade with `Insufficient capital`.
  - For stocks where `ltp` > 10,000 (e.g. MRF, Honeywell, Page Industries), `qty = (int)(10000 / ltp) = 0`, line 82 forces `qty = 1`, requiring `1 * ltp` (> 10,000). Because `availableCapital` <= 10,000, these stocks can NEVER execute.

### O3. Cold-Boot Indicator Starvation (105-Minute Window)
- **File**: `c:\Users\keval\teleStock\src\main\java\com\telestock\strategy\StrategyEngine.java`
  - Lines 52-67:
    ```java
    CurrentCandle candle = currentCandles.computeIfAbsent(symbol, k -> new CurrentCandle(ltp, LocalDateTime.now()));
    candle.update(ltp);
    
    // If 5 minutes have passed, close the candle
    if (ChronoUnit.MINUTES.between(candle.startTime, LocalDateTime.now()) >= 5) {
        List<Double> closes = candleCloses.computeIfAbsent(symbol, k -> new ArrayList<>());
        closes.add(candle.close);
        if (closes.size() > 50) {
            closes.remove(0); // keep last 50 candles
        }
        
        // Reset for next candle
        currentCandles.put(symbol, new CurrentCandle(ltp, LocalDateTime.now()));
        
        // Evaluate strategy on closed candles
        if (closes.size() >= 21) {
            double ema9 = calculateEMA(closes, 9);
            double ema21 = calculateEMA(closes, 21);
            double rsi14 = calculateRSI(closes, 14);
    ```
  - Candle duration is strictly 5 real minutes (`ChronoUnit.MINUTES.between(candle.startTime, now) >= 5`).
  - Strategy evaluation requires `closes.size() >= 21`.
  - To accumulate 21 closed 5-minute candles, the application must run continuously for `21 * 5 = 105 minutes` (1 hour 45 minutes).
  - `candleCloses` is stored in an in-memory `ConcurrentHashMap<String, List<Double>>`. It is never seeded from historical data or persisted.
  - Consequently, on every cold boot or restart, NO buy signal can be evaluated for at least 105 minutes.

### O4. Broken Test Suite & Build Infrastructure
- **Build Tool**: Project root contains `pom.xml`, `mvnw`, and `mvnw.cmd`. No Gradle wrapper exists.
- **Test Inventory**: Exactly 1 test file exists:
  `c:\Users\keval\teleStock\src\test\java\com\telestock\ledger\LedgerServiceTest.java`.
- **Defect in Test**:
  - `LedgerService.java` constructor requires:
    ```java
    @RequiredArgsConstructor
    public class LedgerService {
        private final PositionRepository positionRepository;
        private final TradeRecordRepository tradeRecordRepository;
        private final TelegramService telegramService;
        private final ConfigService configService;
    ```
  - In `LedgerServiceTest.java` lines 22-32:
    ```java
    @Mock
    private PositionRepository positionRepository;

    @Mock
    private TradeRecordRepository tradeRecordRepository;

    @Mock
    private TelegramService telegramService;

    @InjectMocks
    private LedgerService ledgerService;
    ```
  - `ConfigService` is NOT mocked.
  - In `ledgerService.executeSell(position, exitPrice)` (called at line 46), `LedgerService.java` line 96 executes:
    ```java
    SystemConfig config = configService.getConfig();
    ```
  - Because `configService` is `null`, running `testExecuteSellCalculatesChargesCorrectly()` triggers `java.lang.NullPointerException`.
- **Missing Tests**:
  - Zero Spring Boot context boot tests (`@SpringBootTest`).
  - Zero tests for `StrategyEngine` (EMA9, EMA21, RSI14, buy/sell conditions).
  - Zero tests for `LiveMarketDataService` or `NseSymbolDiscoveryService`.
  - Zero tests for `GeminiAiService` or `TelegramService`.
  - Zero tests for `ConfigService` or Controllers.

### O5. Missing Cloud Deployment Artifacts & Configuration Deficiencies
- **File System Inspection**:
  - `Dockerfile`: MISSING.
  - `docker-compose.yml`: MISSING.
  - `Procfile`: MISSING.
  - `render.yaml`: MISSING.
  - `.env.example`: MISSING.
  - CI/CD workflows: MISSING.
- **File**: `c:\Users\keval\teleStock\src\main\resources\application.yml`
  - Lines 1-6:
    ```yaml
    spring:
      datasource:
        url: jdbc:h2:mem:telestock;DB_CLOSE_DELAY=-1
        driverClassName: org.h2.Driver
        username: sa
        password: password
    ```
    In-memory H2 database wipes state on restart.
  - Lines 13-14:
    ```yaml
    server:
      port: 8080
    ```
    Hardcoded `8080` does not read `${PORT:8080}`. Cloud providers (Render, Heroku, Cloud Run) assign random dynamic ports via the `$PORT` environment variable.
  - Missing Actuator: `spring-boot-starter-actuator` is omitted from `pom.xml`, meaning `/actuator/health` is not provided.
  - No lightweight health endpoint exists (only `/api/config`, `/api/positions`, `/api/history`, and streaming `/api/stream/prices`).

### O6. Empty Property Annotation Bug (`@Value("")`)
- **File**: `c:\Users\keval\teleStock\src\main\java\com\telestock\ai\GeminiAiService.java`
  - Lines 21-25:
    ```java
    @Value("")
    private String apiKey;

    @Value("")
    private String model;
    ```
- **File**: `c:\Users\keval\teleStock\src\main\java\com\telestock\telegram\TelegramService.java`
  - Lines 16-20:
    ```java
    @Value("")
    private String token;

    @Value("")
    private String chatId;
    ```
- Spring treats `@Value("")` as a literal empty string `""`, ignoring `gemini.api.key`, `gemini.api.model`, `telegram.bot.token`, and `telegram.chat.id` in `application.yml` and environment variables.

---

## 2. Logic Chain

### L1. Trade Execution Model & Broker Integration
- *Premise 1*: `LedgerService.java` manages orders solely through `positionRepository` and `tradeRecordRepository` (O1).
- *Premise 2*: No broker dependencies, API clients, or authentication mechanisms exist anywhere in the code or dependencies (`pom.xml`) (O1).
- *Conclusion 1*: The application is purely a simulated/paper trading engine. No live broker integration exists. It tracks virtual PnL and statutory Indian charges internally in H2.

### L2. Capital Management & Order Sizing Flaws
- *Premise 1*: Starting capital is fixed at Rs. 10,000 (`SystemConfig.java`) (O2).
- *Premise 2*: Order sizing is calculated as `int qty = (int)(10000 / ltp)` in `StrategyEngine.java:81` regardless of actual `availableCapital` (O2).
- *Premise 3*: If a trade uses Rs. 9,000, `availableCapital` becomes Rs. 1,000. Any subsequent signal calculates `qty` targeting Rs. 10,000, which exceeds Rs. 1,000 and is immediately aborted by `LedgerService.java:28` (O1, O2).
- *Premise 4*: Any stock priced over Rs. 10,000 forces `qty = 1` costing > Rs. 10,000, which also exceeds `availableCapital` and is immediately aborted (O2).
- *Conclusion 2*: The order sizing logic is fundamentally flawed. It permits only one active trade at a time, completely rejects trades when remaining cash is under Rs. 10,000, and is incapable of executing trades for stocks priced above Rs. 10,000. Dynamic position sizing (e.g. allocating `availableCapital / availableSlots` or a fixed percentage of available capital) is required.

### L3. Cold Boot / Cloud Uptime Conflict
- *Premise 1*: StrategyEngine requires 21 closed 5-minute candles to calculate EMA21 and RSI14 (O3).
- *Premise 2*: 21 candles of 5 minutes require 105 minutes of uninterrupted uptime (O3).
- *Premise 3*: In ephemeral cloud containers (e.g. Render Free Tier), containers sleep after 15 minutes of inactivity, redeploy on git pushes, and restart periodically (O3, O5).
- *Premise 4*: Candle closes are kept in volatile memory and wiped on reboot (O3).
- *Conclusion 3*: On cold boot or container restart, the system is dead in the water for 105 minutes. In ephemeral cloud environments, the bot will likely never reach 105 minutes before being restarted, guaranteeing that zero trades are ever executed. A historical candle pre-fill / seed mechanism is mandatory for cold-boot readiness.

### L4. Broken Test Suite & Build Verification
- *Premise 1*: `LedgerServiceTest.java` is the only test in the project (O4).
- *Premise 2*: `LedgerService` requires `ConfigService` to credit capital back during `executeSell` (O1, O4).
- *Premise 3*: `LedgerServiceTest` does not mock `ConfigService` or configure its behavior (O4).
- *Conclusion 4*: Any test run (`./mvnw test`) executing `LedgerServiceTest` will fail with a `NullPointerException` on `configService.getConfig()`. The test suite is currently red out of the box.

### L5. Cloud Deployment Incompatibility
- *Premise 1*: Cloud providers (Render, Heroku) supply the listener port via the `PORT` environment variable (O5).
- *Premise 2*: `application.yml` hardcodes `server.port: 8080` without fallback to `${PORT:8080}` (O5).
- *Premise 3*: No `Dockerfile` exists to package the Java 17 application (O5).
- *Premise 4*: No lightweight `/health` endpoint exists for UptimeRobot to ping without invoking heavy database operations or opening long-lived SSE streams (`/api/stream/prices`) (O1, O5).
- *Premise 5*: Render Free Tier limits memory to 512 MB. Default JVM 17 ergonomics without container limits often consume > 512 MB, leading to Linux OOM killer termination (O5).
- *Conclusion 5*: Attempting to deploy the current project to Render or any cloud container will fail due to missing Docker packaging, port binding mismatches, absence of health check endpoints, and potential OOM crashes.

---

## 3. Caveats

1. **Angel One Public Scrip Master Availability**:
   `NseSymbolDiscoveryService` queries `https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json`. If this third-party URL changes schema, times out, or blocks cloud IP addresses, it falls back to a hardcoded 50-stock list (`fallbackNifty50`).
2. **Yahoo Finance Spark Endpoint Rate Limits**:
   `LiveMarketDataService` polls `https://query1.finance.yahoo.com/v8/finance/spark?symbols=...` every 2 seconds for batches of 20 stocks. While spark does not require crumbs, Yahoo Finance frequently rate-limits or blocks cloud datacenter IPs (such as Render/AWS/GCP ranges) with HTTP 429/403. A resilient fallback (e.g. chart API, mock feed in dev/test, or fallback quotes) must be maintained.
3. **Persisted vs In-Memory Ledger in Cloud**:
   While H2 in-memory is lightweight, persistent tracking on cloud restarts requires either H2 file persistence mounted to a Render persistent disk, an external PostgreSQL database, or an explicit disclaimer that paper trading resets on instance redeployment.

---

## 4. Conclusion

The `teleStock` application is structured as a Spring Boot 3.2.4 (Java 17) in-memory paper trading platform for Indian equities. It possesses five primary critical blockers that prevent it from executing trades and running reliably on the cloud:

1. **Cold Boot Indicator Starvation (105-minute dead zone)**:
   `StrategyEngine` requires 21 closed 5-minute candles (`21 * 5 = 105 mins`) accumulated strictly in real-time before evaluating indicators (EMA 9, EMA 21, RSI 14).
2. **Order Sizing Block**:
   `StrategyEngine` attempts to allocate Rs. 10,000 per trade against a total account capital of Rs. 10,000. After one trade, remaining capital is insufficient for subsequent trades. Stocks priced > Rs. 10,000 are permanently blocked.
3. **Broken Test Suite**:
   `LedgerServiceTest.java` fails with `NullPointerException` due to missing `ConfigService` mock.
4. **Configuration `@Value("")` Annotation Defects**:
   `GeminiAiService` and `TelegramService` use empty string literal annotations `@Value("")`, preventing injection of API keys and bot tokens from environment variables or `application.yml`.
5. **Absence of Cloud Deployment Architecture**:
   Zero Docker/Render artifacts exist; `server.port` does not bind to `${PORT:8080}`; no dedicated `/health` endpoint exists for UptimeRobot; and JVM memory limits are unconfigured for 512 MB cloud limits.

---

## 5. Verification Method

### V1. Verify Test Suite Failure
1. Inspect `src/test/java/com/telestock/ledger/LedgerServiceTest.java` lines 20-33. Note the absence of `@Mock private ConfigService configService`.
2. Inspect `src/main/java/com/telestock/ledger/LedgerService.java` line 96: `SystemConfig config = configService.getConfig()`.
3. Invalidation condition: If `configService` is mocked or `LedgerService.executeSell` does not invoke `configService`, the NPE would not occur.

### V2. Verify Order Sizing Blocker
1. Inspect `src/main/java/com/telestock/strategy/StrategyEngine.java` line 81:
   `int qty = (int) (10000 / ltp);`
2. Inspect `src/main/java/com/telestock/model/SystemConfig.java` line 13:
   `private Double availableCapital = 10000.0;`
3. Inspect `src/main/java/com/telestock/ledger/LedgerService.java` lines 28-31:
   `if (config.getAvailableCapital() < buyValue) return;`
4. Trace: Execute 1 trade for 5 shares of a stock at Rs. 1,800 (total = Rs. 9,000). Remaining capital = Rs. 1,000. Next trade for any stock at Rs. 100 calculates `qty = 10000 / 100 = 100` (cost = Rs. 10,000). Order is rejected because 1,000 < 10,000.
5. Invalidation condition: Sizing is calculated based on available capital or fixed slot allocation (`availableCapital / maxSlots`).

### V3. Verify Cold Boot 105-Minute Delay
1. Inspect `src/main/java/com/telestock/strategy/StrategyEngine.java` line 56:
   `if (ChronoUnit.MINUTES.between(candle.startTime, LocalDateTime.now()) >= 5)`
   and line 67:
   `if (closes.size() >= 21)`
2. Calculation: 21 closed candles * 5 minutes per candle = 105 minutes.
3. Invalidation condition: Preloading historical candle data on startup or evaluating indicators on shorter/bootstrapped data.

### V4. Verify Cloud Artifacts & Port Binding
1. Check repository root for `Dockerfile`, `render.yaml`, `Procfile`. None exist.
2. Inspect `src/main/resources/application.yml` line 14: `port: 8080` (lacks `${PORT:8080}`).
3. Inspect controllers: No dedicated `/health` mapping exists.
4. Invalidation condition: `Dockerfile` exists with multi-stage build, `application.yml` uses `${PORT:8080}`, and a `/health` endpoint is registered.

---

## 6. Actionable Blueprint for Implementation Track

| Component | Identified Defect | Prescribed Fix |
|---|---|---|
| **Port Binding** | `server.port: 8080` hardcoded | Update to `server.port: ${PORT:8080}` in `application.yml`. |
| **Health Check** | No `/health` endpoint | Add a lightweight `GET /health` endpoint returning `{"status":"UP"}` for Render and UptimeRobot. |
| **Cold Boot Starvation** | 105-minute wait for 21 5-min candles | Implement historical candle bootstrapping in `StrategyEngine` (fetch last 25 5-min candles from Yahoo Finance chart API `/v8/finance/chart/{symbol}?interval=5m&range=2d` or generate warmup candles on startup). |
| **Position Sizing** | Hardcoded `10000 / ltp` blocks orders | Compute `qty` dynamically: `int maxPositions = 5; double slotCapital = config.getAvailableCapital() / remainingSlots; int qty = (int)(slotCapital / ltp);`. Ensure `qty >= 1` only if `availableCapital >= ltp`. |
| **Config Injection** | `@Value("")` in Gemini and Telegram services | Replace with `@Value("${gemini.api.key:}")`, `@Value("${gemini.api.model:gemini-2.5-flash}")`, `@Value("${telegram.bot.token:}")`, `@Value("${telegram.chat.id:}")`. |
| **Unit Test Defect** | `LedgerServiceTest` missing `ConfigService` mock | Add `@Mock private ConfigService configService;` and stub `when(configService.getConfig()).thenReturn(new SystemConfig());`. |
| **Cloud Artifacts** | Missing deployment files | Create `Dockerfile` (multi-stage JRE 17 with memory flags `-Xmx384m -XX:+UseSerialGC`), `render.yaml`, `.env.example`, and an automated E2E verification test harness. |
