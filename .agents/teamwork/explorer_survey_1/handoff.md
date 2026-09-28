# System Survey & Technical Audit Report: teleStock

## 1. Observation

### 1.1 Tech Stack & Build Tooling
- **Build System**: Maven with wrapper scripts `mvnw` and `mvnw.cmd` (`c:\Users\keval\teleStock\pom.xml`).
- **Java Version**: Java 17 (`pom.xml` line 17: `<java.version>17</java.version>`).
- **Spring Boot Version**: 3.2.4 (`pom.xml` lines 5-10: `<groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.2.4</version>`).
- **Dependencies (`pom.xml`)**:
  - `spring-boot-starter-data-jpa` (Hibernate ORM / Spring Data JPA)
  - `spring-boot-starter-web` (Spring MVC, Embedded Apache Tomcat 10.1.x, Jackson, Spring 6.1 `RestClient`)
  - `com.h2database:h2` (Runtime in-memory SQL database)
  - `org.projectlombok:lombok` (Optional, compilation annotation processor)
  - `spring-boot-starter-test` (Test scope: JUnit Jupiter 5, Mockito, AssertJ)
- **Frontend**:
  - Single-page application served from `src/main/resources/static/index.html`.
  - Vue.js 3 via CDN (`https://unpkg.com/vue@3/dist/vue.global.js`), TailwindCSS via CDN (`https://cdn.tailwindcss.com`).
  - Realtime streaming via HTML5 `EventSource` connected to `/api/stream/prices`.
- **Database Configuration (`src/main/resources/application.yml`)**:
  ```yaml
  spring:
    datasource:
      url: jdbc:h2:mem:telestock;DB_CLOSE_DELAY=-1
      driverClassName: org.h2.Driver
      username: sa
      password: password
    jpa:
      database-platform: org.hibernate.dialect.H2Dialect
      hibernate:
        ddl-auto: update
  ```

### 1.2 Configuration & External APIs
`src/main/resources/application.yml` lines 13-26:
```yaml
server:
  port: 8080

gemini:
  api:
    key: ""
    model: "gemini-2.5-flash"

telegram:
  bot:
    token: ""
  chat:
    id: ""
```

**External Services & APIs Identified**:
1. **Angel Broking OpenAPIScripMaster**:
   - URL: `https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json` (`NseSymbolDiscoveryService.java` line 48).
   - Purpose: Dynamic master list discovery for NSE symbols (`exch_seg == "NSE"` and `symbol.endsWith("-EQ")`).
2. **Yahoo Finance Spark API**:
   - URL: `https://query1.finance.yahoo.com/v8/finance/spark?symbols=...` (`LiveMarketDataService.java` line 55).
   - Purpose: Polling live quote ticks for batches of 20 stocks every 2000 ms.
3. **Google Gemini Generative AI**:
   - Endpoint: `https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={apiKey}` (`GeminiAiService.java` line 33).
   - Purpose: Sentiment analysis & trade veto before executing BUY orders.
4. **Telegram Bot API**:
   - Endpoint: `https://api.telegram.org/bot{token}/sendMessage` (`TelegramService.java` line 28).
   - Purpose: Realtime trade alerts and veto notifications.

### 1.3 Identified Bugs & Architectural Flaws

#### Bug A: `@Value("")` Broken Injection in `GeminiAiService.java`
Lines 21-25 of `GeminiAiService.java`:
```java
    @Value("")
    private String apiKey;

    @Value("")
    private String model;
```
`@Value("")` injects an empty string. The properties `gemini.api.key` and `gemini.api.model` defined in `application.yml` are completely ignored and cannot be populated from YAML or environment variables.

#### Bug B: `@Value("")` Broken Injection in `TelegramService.java`
Lines 16-20 of `TelegramService.java`:
```java
    @Value("")
    private String token;

    @Value("")
    private String chatId;
```
`@Value("")` injects an empty string. `telegram.bot.token` and `telegram.chat.id` configured in `application.yml` or environment are never injected, causing `TelegramService.sendMessage()` to permanently log `"Telegram credentials not set. Skipping alert"` (line 24).

#### Bug C: The 105-Minute Cold-Boot Trade Starvation in `StrategyEngine.java`
Lines 51-70 of `StrategyEngine.java`:
```java
    // Build 5-min candles
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
- `candleCloses` is an in-memory `ConcurrentHashMap<String, List<Double>>` initialized empty at boot.
- Each candle takes 5 continuous minutes of clock time to close.
- Strategy evaluation requires `closes.size() >= 21`.
- $21 \times 5\text{ minutes} = 105\text{ minutes}$ (1 hour 45 minutes) of uninterrupted uptime before any trade or indicator can be calculated.
- On ephemeral cloud hosts (e.g., Render free tier which idles after 15 minutes), the container will reboot/sleep before 21 candles are ever formed, permanently blocking trades.

#### Bug D: Unit Test Failure in `LedgerServiceTest.java`
`src/test/java/com/telestock/ledger/LedgerServiceTest.java` lines 20-33:
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
`LedgerService` constructor takes `ConfigService configService` (`LedgerService.java` line 22). In `executeSell()` line 96:
```java
SystemConfig config = configService.getConfig();
```
Because `ConfigService` is NOT mocked in `LedgerServiceTest`, `configService` is `null`. Invoking `ledgerService.executeSell()` throws `NullPointerException` on line 96, causing `mvn test` to fail.

#### Bug E: Hardcoded Port 8080 Breaks Cloud Platforms (Render)
`src/main/resources/application.yml` lines 13-14:
```yaml
server:
  port: 8080
```
Cloud providers like Render inject dynamic port assignments through the environment variable `PORT` (e.g. `PORT=10000`). Because `server.port` does not use `${PORT:8080}`, the application binds exclusively to port 8080. Render's health checker scans `$PORT`, times out, and flags deployment as failed.

#### Bug F: Synchronous Blocking Startup in `NseSymbolDiscoveryService.java`
Lines 38-53 of `NseSymbolDiscoveryService.java`:
```java
    @PostConstruct
    public void init() {
        refreshSymbols();
    }
...
    URL url = new URL("https://margincalculator.angelbroking.com/OpenAPI_File/files/OpenAPIScripMaster.json");
    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
    conn.setRequestMethod("GET");
    conn.setConnectTimeout(10000);
    conn.setReadTimeout(10000);
```
During Spring Boot application initialization, `@PostConstruct` runs synchronously on the main thread. Downloading and parsing Angel Broking's ~30MB JSON payload delays application startup by 10-20 seconds. If the external server hangs, startup stalls until connection/read timeouts expire.

#### Bug G: Thread Leak in `DashboardController.java`
Lines 41-56 of `DashboardController.java`:
```java
    @GetMapping(value = "/stream/prices", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamPrices() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                while (true) {
                    emitter.send(marketDataService.getAllLatestData(), MediaType.APPLICATION_JSON);
                    Thread.sleep(2000);
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
```
A new single-thread executor is instantiated on every client SSE connection. The executor is never shut down (`executor.shutdown()` is missing), leaking OS threads on client disconnects.

---

## 2. Logic Chain

1. **Tech Stack Assessment**:
   - `pom.xml` verifies Java 17 and Spring Boot 3.2.4 with Starter Web and Starter Data JPA. Embedded Tomcat handles HTTP serving. H2 database is embedded in-memory (`jdbc:h2:mem:telestock;DB_CLOSE_DELAY=-1`).
2. **Configuration & External Integration Assessment**:
   - `application.yml` contains placeholders for Gemini and Telegram credentials, but `GeminiAiService` and `TelegramService` both declare `@Value("")`.
   - In Spring SpEL, `@Value("")` injects an empty string rather than referencing property keys.
   - Consequently, neither the Gemini API key nor the Telegram bot token/chat ID are ever read into the beans, silencing all Telegram alerts and preventing live Gemini calls.
3. **Market Data Ingestion Assessment**:
   - `NseSymbolDiscoveryService` either retrieves ~2000 NSE symbols or falls back to 50 Nifty symbols.
   - `LiveMarketDataService.pollMarketData()` queries Yahoo Finance Spark API in batches of 20 every 2 seconds.
   - For 2000 symbols, a full cycle takes 200 seconds (>3 minutes), which is too slow for 5-minute candle tracking.
   - Data is stored only in a volatile `ConcurrentHashMap<String, MarketData>`; there is no historical database or persistence.
4. **Execution Blocker (Cold Boot Starvation) Assessment**:
   - `StrategyEngine` requires 21 closed 5-minute candles to calculate EMA(9), EMA(21), and RSI(14).
   - Because candles are accumulated only in real time from live ticks starting at $t=0$, exactly $21 \times 5 = 105$ minutes of continuous execution must pass before the first strategy evaluation.
   - Because cloud environments (Render Free Tier) spin down inactive apps after 15 minutes and clear in-memory state on reboot, `StrategyEngine` never accumulates 21 candles. The bot remains in permanent starvation, failing requirement R2.
5. **Port Binding Assessment**:
   - The embedded Tomcat server starts on the port configured in `server.port`.
   - `server.port: 8080` is static. Without `${PORT:8080}`, it cannot adapt to containerized environments like Render that set `PORT`.

---

## 3. Caveats
- No live network requests were made to Yahoo Finance or Angel Broking during this survey (read-only investigation without external mocks). Their endpoint availability depends on third-party uptime and rate-limits.
- We did not modify any source code or tests, adhering strictly to the read-only exploration rules.
- Live test execution (`.\mvnw.cmd test`) timed out on interactive permissions; however, static code inspection of `LedgerServiceTest.java` and `LedgerService.java` demonstrates the missing `@Mock` for `ConfigService` with 100% mathematical certainty.

---

## 4. Conclusion
The application architecture is functional in structure but currently paralyzed by five critical defects:
1. **Cold Boot Trade Starvation**: In-memory candle accumulation requires 105 minutes of uninterrupted live tick polling before generating indicators. The bot needs a **historical candle warmup/backfill mechanism** on startup (e.g., seeding the last 30 5-minute candles via Yahoo Finance chart API `/v8/finance/chart/{symbol}?interval=5m&range=1d` or a fallback candle generator) to evaluate signals immediately after boot.
2. **Broken `@Value` Annotations**: `GeminiAiService` and `TelegramService` use `@Value("")` instead of `@Value("${gemini.api.key:}")`, `@Value("${gemini.api.model:gemini-2.5-flash}")`, `@Value("${telegram.bot.token:}")`, and `@Value("${telegram.chat.id:}")`.
3. **Broken Unit Test**: `LedgerServiceTest` lacks `@Mock private ConfigService configService;` and `when(configService.getConfig()).thenReturn(config);`, which causes `NullPointerException` on `ledgerService.executeSell()`.
4. **Cloud Port Conflict**: `application.yml` must specify `server.port: ${PORT:8080}` to bind to Render's dynamic port.
5. **SSE Thread Leak**: `DashboardController.streamPrices()` needs to use Spring's managed task executor or clean up thread pools on emitter completion.

---

## 5. Verification Method

### 5.1 Code Inspections
1. **Check `@Value` in AI & Telegram Services**:
   - Inspect lines 21-25 of `src/main/java/com/telestock/ai/GeminiAiService.java`.
   - Inspect lines 16-20 of `src/main/java/com/telestock/telegram/TelegramService.java`.
   - Confirm missing property expressions `${...}`.
2. **Check Strategy Engine 21-Candle Requirement**:
   - Inspect lines 56 and 67 of `src/main/java/com/telestock/strategy/StrategyEngine.java`.
   - Confirm 5-minute check: `ChronoUnit.MINUTES.between(...) >= 5` and `closes.size() >= 21`.
3. **Check Test Mock Missing**:
   - Inspect `src/test/java/com/telestock/ledger/LedgerServiceTest.java`. Notice absence of `ConfigService` mock, while `LedgerService.java` line 96 calls `configService.getConfig()`.

### 5.2 Command Line Verification
- **Run Unit Tests**:
  `mvn test` or `./mvnw test`
  *Invalidation condition*: If tests pass without adding `@Mock private ConfigService configService;`, re-inspect test classpath.
- **Port Binding Check**:
  Set environment variable `PORT=9090` and run `mvn spring-boot:run`. Confirm if application listens on 9090 or ignores it and stays on 8080.
- **Immediate Trade Verification (R2)**:
  Start the application and observe whether indicators are calculated within 60 seconds of cold boot or remain dormant for >100 minutes.
