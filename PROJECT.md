# Project: teleStock Trading Bot Audit, Cold-Boot Fixes & Cloud Readiness

## Architecture
`teleStock` is a Spring Boot 3.2.4 application (Java 17, Maven) providing autonomous paper-trading for Indian equities (NSE).
- **Presentation**: Single-Page Application (HTML5, Vue.js 3, TailwindCSS) with SSE price streaming (`/api/stream/prices`).
- **Data Ingestion**:
  - `NseSymbolDiscoveryService`: Discovers active NSE symbols from Angel Broking Master Scrip with Nifty 50 fallback.
  - `LiveMarketDataService`: Polls Yahoo Finance Spark API (`v8/finance/spark`) in batches of 20 symbols every 2 seconds.
- **Strategy & Analysis**:
  - `StrategyEngine`: Aggregates 5-minute OHLCV candles, computes technical indicators (EMA9, EMA21, RSI14), and triggers crossover trade signals.
  - `GeminiAiService`: Pre-trade sentiment analysis and veto via Google Gemini Generative AI (`v1beta/models`).
- **Ledger & Execution**:
  - `LedgerService`: In-memory paper-trading trade execution engine tracking positions, stop-loss (-1.5%), profit target (+3.0%), and Indian statutory charges (brokerage, STT, exchange turnover, SEBI charges, stamp duty, GST).
  - `PositionRepository` & `TradeRecordRepository`: Spring Data JPA entities backed by H2 in-memory DB (`jdbc:h2:mem:telestock;DB_CLOSE_DELAY=-1`).
  - `TelegramService`: Broadcasts trade entries, exits, and AI veto decisions.
- **Cloud & Deployment**:
  - Port binding via `${PORT:8080}` for dynamic cloud container ports (Render/Heroku).
  - Health check endpoint `/health` for UptimeRobot / Render keep-alive.
  - Docker multi-stage build container with memory tuning (`-Xmx384m -XX:+UseSerialGC`) for Render Free Tier (512MB RAM).

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Config & Property Injection Fix | Fix `@Value("")` in `GeminiAiService` and `TelegramService` to correctly inject `${gemini.api.key:}` and `${telegram.bot.token:}`. | M1 | Survey (Explorer 1, 3) |
| 2 | Unit Test Suite Fix | Add missing `ConfigService` mock to `LedgerServiceTest` to eliminate `NullPointerException` on `executeSell`. | M1 | Survey (Explorer 1, 3) |
| 3 | Concurrency & Stability Hardening | Fix SSE thread leak in `DashboardController`, configure `ThreadPoolTaskScheduler` bean (4 threads) to prevent scheduler starvation. | M1 | Survey (Explorer 1, 2) |
| 4 | Cloud Port Binding & Health Check | Update `application.yml` with `server.port: ${PORT:8080}` and implement `/health` endpoint for keep-alive. | M1 | Survey (Explorer 1, 3) |
| 5 | Technical Indicator Math Fixes | Fix off-by-one loop in `calculateRSI` (13 vs 14 differences), fix flat market RSI (return 50.0 instead of 100.0), fix EMA history truncation. | M2 | Survey (Explorer 2) |
| 6 | Dynamic Position Sizing & Capital Fix | Eliminate hardcoded `10000 / ltp` order sizing lock; implement dynamic slot sizing (`availableCapital / openSlots`) allowing multi-trade execution. | M2 | Survey (Explorer 2, 3) |
| 7 | Thread-Safe Candle Storage | Migrate `candleCloses` in `StrategyEngine` to use thread-safe `CopyOnWriteArrayList` to prevent `ConcurrentModificationException`. | M2 | Survey (Explorer 2) |
| 8 | Historical 5m Candle Backfill & Warm-up | Implement cold-boot backfill via Yahoo Finance chart API `/v8/finance/chart/{symbol}?interval=5m&range=2d` on startup. | M3 | Survey (Explorer 1, 2, 3) |
| 9 | Synthetic / Micro-Walk Fallback Warm-up | Provide offline/after-hours fallback candle seeder so indicators can compute immediately even when market is closed or offline. | M3 | Survey (Explorer 2) |
| 10 | Strategy Telemetry & Warmup Trigger | Implement `GET /api/strategy/status` and `POST /api/test/warmup` endpoints for automated testing and status inspection. | M3 | Survey (Explorer 2, 3) |
| 11 | Containerization & Cloud Deployment | Create production `Dockerfile`, `render.yaml`, `.env.example`, and step-by-step Render + UptimeRobot guide. | M4 | Survey (Explorer 1, 3) |
| 12 | Opaque-Box E2E Test Suite | Design and build comprehensive 4-tier E2E test suite verifying port 8080 boot, immediate indicator calculation, and simulated trade execution. | E2E-Track | ORIGINAL_REQUEST |
| 13 | Final Milestone Verification | Pass 100% of E2E test suite (Tiers 1-4) and adversarial coverage hardening (Tier 5). | M5 (Final) | Project Pattern |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| M1 | Core Config, Test Suite & Platform Hardening | Fix `@Value` properties, fix `LedgerServiceTest`, add `ThreadPoolTaskScheduler`, fix SSE thread leak, add `/health`, bind `${PORT:8080}` | none | DONE |
| M2 | Indicator Math, Sizing & Concurrency | Fix RSI loop and zero-loss handling, fix EMA smoothing, implement dynamic position sizing, migrate candle lists to `CopyOnWriteArrayList` | M1 | PLANNED |
| M3 | Cold-Boot Backfill, Warm-up & Telemetry | Implement Yahoo chart 5m historical backfill, synthetic fallback seeder, `GET /api/strategy/status`, `POST /api/test/warmup` | M2 | PLANNED |
| M4 | Cloud Deployment Architecture & Container | Multi-stage `Dockerfile`, `render.yaml`, `.env.example`, Render + UptimeRobot deployment documentation | M1 | PLANNED |
| E2E | E2E Testing Track | Build comprehensive opaque-box test runner & Tiers 1-4 test suite per `ORIGINAL_REQUEST.md` | M1 | PLANNED |
| M5 | Final Milestone: 100% E2E Pass & Hardening | Phase 1: 100% E2E test pass (port 8080 boot, cold-boot indicator calculation, simulated trade); Phase 2: Adversarial hardening | M3, M4, E2E | PLANNED |

## Interface Contracts
### Config & Cloud Environment
- Property `server.port`: `${PORT:8080}` (integer). Default 8080.
- `GET /health` -> `{"status":"UP","timestamp":"..."}` (HTTP 200).
- Property `gemini.api.key`: `${gemini.api.key:}` (string).
- Property `gemini.api.model`: `${gemini.api.model:gemini-2.5-flash}` (string).
- Property `telegram.bot.token`: `${telegram.bot.token:}` (string).
- Property `telegram.chat.id`: `${telegram.chat.id:}` (string).

### Strategy Engine & Telemetry
- `GET /api/strategy/status` ->
  ```json
  {
    "tradingEnabled": true,
    "symbolsTracked": 50,
    "symbolsReady": 50,
    "coldBootWarmedUp": true,
    "indicators": {
      "RELIANCE.NS": { "candleCount": 30, "ema9": 2450.5, "ema21": 2440.2, "rsi14": 54.3, "ready": true }
    }
  }
  ```
- `POST /api/test/warmup` -> Triggers immediate historical or synthetic candle seeding and returns warmup summary.

### Ledger & Position Sizing
- `SystemConfig.availableCapital`: dynamically decremented on buy, incremented on sell.
- Position sizing: `qty = (int)(availableCapital / openSlots / ltp)`. If `qty == 0 && availableCapital >= ltp`, `qty = 1`.

## Code Layout
- `src/main/java/com/telestock/`:
  - `TeleStockApplication.java`: Main application entry point
  - `config/`: Configuration classes (`AppConfig.java`, task scheduler, etc.)
  - `controller/`: REST controllers (`DashboardController.java`, `TestController.java`, `HealthController.java`, `StrategyController.java`)
  - `model/`: JPA entities and DTOs (`Position.java`, `TradeRecord.java`, `SystemConfig.java`, `MarketData.java`, etc.)
  - `repository/`: Spring Data JPA repositories (`PositionRepository.java`, `TradeRecordRepository.java`, `SystemConfigRepository.java`)
  - `service/` / `feed/`: Market data ingestion (`LiveMarketDataService.java`, `NseSymbolDiscoveryService.java`, `HistoricalDataService.java`)
  - `strategy/`: Strategy evaluation & indicators (`StrategyEngine.java`, `CandidateSignal.java`, `CurrentCandle.java`)
  - `ledger/`: Trade accounting & statutory fees (`LedgerService.java`)
  - `ai/`: Gemini AI integration (`GeminiAiService.java`)
  - `telegram/`: Telegram alerts (`TelegramService.java`)
- `src/test/java/com/telestock/`:
  - Unit and integration tests (`LedgerServiceTest.java`, `StrategyEngineTest.java`, etc.)
- `e2e/` or root:
  - Test runner scripts, verification scripts (`verify_cold_boot.py` or `.ps1`, `Dockerfile`, `render.yaml`)
