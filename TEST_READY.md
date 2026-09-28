# TEST_READY: teleStock Automated Opaque-Box E2E Test Suite

## Overview
An automated opaque-box E2E test suite has been designed, authored, and verified to enforce all Acceptance Criteria from `ORIGINAL_REQUEST.md`, `PROJECT.md`, and `TEST_INFRA.md`.

The test harness runs externally against the running application over HTTP, validating clean cold boot, immediate indicator availability (no 105-minute starvation window), rapid trade execution, position sizing, ledger accounting, and cloud health monitoring.

---

## Test Runner Commands

### Python Runner (Primary)
```bash
# Run all 73 tests against running instance (default: http://localhost:8080)
python e2e/test_cold_boot_e2e.py

# Run against custom host or port (e.g. cloud or dynamic PORT)
python e2e/test_cold_boot_e2e.py --base-url http://localhost:8080
python e2e/test_cold_boot_e2e.py --port 8080

# Run in self-verification mode (uses in-process specification mock server)
python e2e/test_cold_boot_e2e.py --self-test

# Filter by tier (1: Happy Path, 2: Boundary, 3: Pairwise, 4: Real-World)
python e2e/test_cold_boot_e2e.py --tier 1
python e2e/test_cold_boot_e2e.py --tier 4

# Filter by feature (F1..F6)
python e2e/test_cold_boot_e2e.py --feature F1
python e2e/test_cold_boot_e2e.py --feature F3

# Run a specific Tier 4 real-world scenario (1..5)
python e2e/test_cold_boot_e2e.py --scenario 1
python e2e/test_cold_boot_e2e.py --scenario 2

# Save machine-readable report to custom path
python e2e/test_cold_boot_e2e.py --report e2e/test_report.json
```

### PowerShell Runner (Windows Native)
```powershell
# Run full suite
powershell -ExecutionPolicy Bypass -File e2e/test_cold_boot_e2e.ps1

# Run with self-test mode
powershell -ExecutionPolicy Bypass -File e2e/test_cold_boot_e2e.ps1 -SelfTest

# Target specific tier or scenario
powershell -ExecutionPolicy Bypass -File e2e/test_cold_boot_e2e.ps1 -Tier 1
powershell -ExecutionPolicy Bypass -File e2e/test_cold_boot_e2e.ps1 -Scenario 1
```

---

## Test Inventory & Coverage Breakdown

| Tier | Category | Description | Test Count | Pass Target |
|:----:|:---------|:------------|:----------:|:-----------:|
| **Tier 1** | Happy-Path Core | Primary behavior across Features 1 through 6 | **30** | 30 / 30 |
| **Tier 2** | Boundary & Error Handling | Input edge cases, extremes, zero capital, flat RSI, malformed requests | **30** | 30 / 30 |
| **Tier 3** | Pairwise Interactions | Combinatorial pairings (Port x Config, Indicators x Trade, Sizing x History) | **8** | 8 / 8 |
| **Tier 4** | Real-World Application Scenarios | End-to-end cloud and trading flows per `TEST_INFRA.md` | **5** | 5 / 5 |
| **Total** | | | **73** | **73 / 73** |

---

## Feature Checklist & Contract Mapping

### Feature 1: Port 8080 Clean Boot & Health Check (F1)
- [x] `T1_F1_01`: `GET /health` returns HTTP 200 OK.
- [x] `T1_F1_02`: `GET /health` payload contains `{"status": "UP"}`.
- [x] `T1_F1_03`: `GET /health` contains valid ISO-8601 timestamp.
- [x] `T1_F1_04`: Server binds and responds on default port 8080 (or `${PORT}`).
- [x] `T1_F1_05`: Health check responds in < 500ms for cloud keep-alive (UptimeRobot).
- [x] `T2_F1_01`: `POST /health` returns 405 Method Not Allowed.
- [x] `T2_F1_02`: Handles `Accept` header variations cleanly.
- [x] `T2_F1_03`: Burst probe resilience (20 rapid probes without failure).
- [x] `T2_F1_04`: Malformed query parameters handled gracefully.
- [x] `T2_F1_05`: HTTP HEAD method support.

### Feature 2: Dynamic Environment Config Injection (F2)
- [x] `T1_F2_01`: `GET /api/config` returns HTTP 200.
- [x] `T1_F2_02`: `SystemConfig` includes `tradingEnabled` boolean.
- [x] `T1_F2_03`: `SystemConfig` includes `availableCapital` numeric field.
- [x] `T1_F2_04`: `POST /api/config` toggles trading state.
- [x] `T1_F2_05`: `POST /api/config` persists capital adjustments.
- [x] `T2_F2_01`: Negative capital input clamped/rejected safely.
- [x] `T2_F2_02`: Empty JSON body `{}` handled safely.
- [x] `T2_F2_03`: Malformed JSON returns 400 Bad Request.
- [x] `T2_F2_04`: High capital values handled without numeric overflow.
- [x] `T2_F2_05`: Idempotent consecutive reads.

### Feature 3: Immediate Indicator Calculation Post-Boot (F3)
- [x] `T1_F3_01`: `GET /api/strategy/status` returns HTTP 200.
- [x] `T1_F3_02`: Reports `coldBootWarmedUp: true` without waiting 105 minutes.
- [x] `T1_F3_03`: Calculates numeric EMA9 and EMA21 immediately on boot.
- [x] `T1_F3_04`: Calculates RSI14 bounded between 0.0 and 100.0.
- [x] `T1_F3_05`: `POST /api/test/warmup` triggers backfill on demand.
- [x] `T2_F3_01`: Flat market price action evaluates to neutral RSI = 50.0 (not 100.0).
- [x] `T2_F3_02`: RSI window evaluates 14 differences without off-by-one undercounting.
- [x] `T2_F3_03`: Unknown symbol warmup handled safely.
- [x] `T2_F3_04`: EMA9 > EMA21 correctly tracks uptrend momentum.
- [x] `T2_F3_05`: Indicator values are finite (no NaN or Infinity).

### Feature 4: Immediate Cold-Boot Trade Execution (F4)
- [x] `T1_F4_01`: `GET /api/test/buy` executes simulated trade and returns HTTP 200.
- [x] `T1_F4_02`: `GET /api/positions` contains newly opened position.
- [x] `T1_F4_03`: Position includes Stop-Loss (-1.5%) and Target (+3.0%).
- [x] `T1_F4_04`: `GET /api/test/sell` executes simulated sell and returns HTTP 200.
- [x] `T1_F4_05`: `GET /api/history` contains completed trade record.
- [x] `T2_F4_01`: `GET /api/test/sell` with zero open positions returns friendly message (no NPE).
- [x] `T2_F4_02`: Order rejected safely when available capital is 0.0.
- [x] `T2_F4_03`: Trade record verifies STT (0.1%), GST (18%), exchange turnover, and brokerage.
- [x] `T2_F4_04`: `GET /api/test/cleanup` wipes positions and history cleanly.
- [x] `T2_F4_05`: Duplicate symbol buy handled without database constraint violation.

### Feature 5: Dynamic Position Sizing & Multi-Trade (F5)
- [x] `T1_F5_01`: Initial capital starts at Rs. 10,000.0.
- [x] `T1_F5_02`: Buy order deducts exact trade value (`price * quantity`).
- [x] `T1_F5_03`: Second trade executable from remaining capital pool without starvation.
- [x] `T1_F5_04`: Sell order restores invested capital and net PnL back to ledger.
- [x] `T1_F5_05`: Minimum one-share allocation when capital >= LTP.
- [x] `T2_F5_01`: Stock LTP > single slot handled safely.
- [x] `T2_F5_02`: Stock LTP > total capital rejected without overdraft.
- [x] `T2_F5_03`: Max concurrent positions capacity saturation guard.
- [x] `T2_F5_04`: Capital adjustments rounded cleanly to 2 decimal places.
- [x] `T2_F5_05`: Zero-capital edge state handled without crash.

### Feature 6: Price Streaming & Dashboard REST API (F6)
- [x] `T1_F6_01`: `GET /api/positions` returns JSON array.
- [x] `T1_F6_02`: `GET /api/history` returns JSON array.
- [x] `T1_F6_03`: `GET /` serves Single-Page Application HTML.
- [x] `T1_F6_04`: `GET /api/stream/prices` establishes `text/event-stream` SSE connection.
- [x] `T1_F6_05`: SSE price stream emits live market data ticks.
- [x] `T2_F6_01`: SSE client early disconnect does not leak threads or crash server.
- [x] `T2_F6_02`: Position JSON schema validates all entity attributes.
- [x] `T2_F6_03`: Trade record JSON schema validates all financial attributes.
- [x] `T2_F6_04`: Static frontend index.html includes Vue.js 3 runtime.
- [x] `T2_F6_05`: Concurrent REST queries executed non-blocking.

---

## Tier 4 Real-World Application Scenarios (Per TEST_INFRA.md)

1. **Scenario 1: Fresh Container Cold Boot on Ephemeral Cloud (F1, F2, F3)**
   - Simulates cold container startup on Render: server binds to `${PORT:8080}`, `/health` responds with HTTP 200 `{"status":"UP"}`, and `/api/strategy/status` immediately reports warmed-up indicators for active symbols without waiting 105 minutes.
2. **Scenario 2: Immediate Crossover Buy & Ledger Accounting (F3, F4, F5)**
   - Simulates indicator crossover post-boot: EMA9 > EMA21 and RSI in [45, 65] triggers buy, capital is decremented, position is saved with stop-loss (-1.5%) and target (+3.0%).
3. **Scenario 3: Multi-Stock Concurrent Order Execution (F4, F5)**
   - Simulates multi-stock execution across the capital pool: multiple orders execute concurrently without capital exhaustion starvation.
4. **Scenario 4: Offline / Market-Closed Boot Resilience (F1, F3, F4)**
   - Simulates weekend or after-hours reboot: synthetic fallback seeding ensures indicators are ready and flat price action evaluates to neutral RSI = 50.0.
5. **Scenario 5: Position Exit on Stop-Loss / Target (F4, F5)**
   - Simulates market price reaching target or stop-loss: position closes, Indian statutory charges (brokerage, STT, exchange, SEBI, stamp duty, GST) are calculated, net PnL is computed, capital is credited back to ledger.

---

## Exit Code & Reporting Semantics
- **Exit Code 0**: 100% of executed tests passed.
- **Exit Code 1**: One or more tests failed.
- **Report Output**: Formatted console output table + detailed JSON at `e2e/test_report.json`.
