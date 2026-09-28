## 2026-09-27T14:45:09Z

You are Worker M2 for the teleStock project.
Your assigned working directory is: c:\Users\keval\teleStock\.agents\teamwork\worker_m2\
The original user request is at: c:\Users\keval\teleStock\.agents\teamwork\ORIGINAL_REQUEST.md
The project scope is at: c:\Users\keval\teleStock\PROJECT.md

You MUST read c:\Users\keval\teleStock\.agents\teamwork\ORIGINAL_REQUEST.md and c:\Users\keval\teleStock\PROJECT.md before starting work.

MANDATORY INTEGRITY WARNING:
DO NOT CHEAT. All implementations must be genuine. DO NOT hardcode test results, create dummy/facade implementations, or circumvent the intended task. An auditor will independently verify your work. Integrity violations WILL be detected and your work WILL be rejected.

Mission: Implement Milestone 2 (Indicator Math, Dynamic Position Sizing & Concurrency).
You have exclusive write ownership of:
- src/main/java/com/telestock/strategy/StrategyEngine.java
- src/test/java/com/telestock/strategy/StrategyEngineTest.java (create)

Read the following Explorer reports for exact code, diffs, and test suites:
1. c:\Users\keval\teleStock\.agents\teamwork\explorer_m2_1\handoff.md:
   - Make calculateEMA and calculateRSI public.
   - Fix calculateRSI: start index at `prices.size() - period - 1` to `prices.size() - 1` (14 differences from 15 prices), return neutral 50.0 for flat market (`avgGain == 0.0 && avgLoss == 0.0`) and when prices is null/empty or size <= period.
   - Fix calculateEMA: seed with SMA over initial `period` prices, then exponentially smooth over subsequent prices using full available history. Return SMA fallback when size < period.
2. c:\Users\keval\teleStock\.agents\teamwork\explorer_m2_2\handoff.md:
   - Dynamic position sizing: Replace `int qty = (int) (10000 / ltp)` with dynamic sizing based on `availableCapital / openSlots` (up to 5 concurrent positions) with minimum 1 share if `availableCapital >= ltp`, preventing zero-quantity and negative capital orders.
   - Thread safety: In `candleCloses`, use `CopyOnWriteArrayList` to prevent `ConcurrentModificationException`.
3. c:\Users\keval\teleStock\.agents\teamwork\explorer_m2_3\proposed_StrategyEngineTest.java and handoff.md:
   - Implement `src/test/java/com/telestock/strategy/StrategyEngineTest.java` containing the complete 25+ unit and integration tests covering RSI, EMA, position sizing, exit conditions, and concurrency.

Verification Requirements:
- Run Maven tests using `./mvnw test` or `.\mvnw.cmd test`.
- Verify that all tests across the entire application (`LedgerServiceTest`, `PropertyInjectionTest`, `HealthControllerTest`, `AppConfigTest`, `DashboardControllerSseTest`, and `StrategyEngineTest`) compile and pass cleanly with 0 errors and 0 failures.
- Document exact commands executed and results in your handoff report at c:\Users\keval\teleStock\.agents\teamwork\worker_m2\handoff.md.
- Send a message to orchestrator when complete.
