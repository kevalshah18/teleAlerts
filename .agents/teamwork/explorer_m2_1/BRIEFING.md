# BRIEFING — 2026-09-27T14:43:00Z

## Mission
Investigate and design the exact fix strategy for Milestone 2 - Indicator Math in StrategyEngine.java (RSI off-by-one and flat-market fixes, EMA full history and SMA seed fixes).

## 🔒 My Identity
- Archetype: explorer
- Roles: [investigation, synthesis]
- Working directory: c:\Users\keval\teleStock\.agents\teamwork\explorer_m2_1\
- Original parent: a0b7ac32-5baa-4658-8b65-50f7031b1df7
- Milestone: Milestone 2 - Indicator Math

## 🔒 Key Constraints
- Read-only investigation — do NOT implement
- Read-only exploration! DO NOT modify source code or tests directly.
- Update progress.md with timestamp.
- Write handoff report to c:\Users\keval\teleStock\.agents\teamwork\explorer_m2_1\handoff.md
- Send message to orchestrator when finished.

## Current Parent
- Conversation ID: a0b7ac32-5baa-4658-8b65-50f7031b1df7
- Updated: 2026-09-27T14:43:00Z

## Investigation State
- **Explored paths**:
  - `src/main/java/com/telestock/strategy/StrategyEngine.java` (lines 67-73, 100-122)
  - `src/test/java/com/telestock/config/AppConfigTest.java`
  - `src/test/java/com/telestock/ledger/LedgerServiceTest.java`
  - `PROJECT.md`, `ORIGINAL_REQUEST.md`, peer explorer dispatches (`explorer_m2_2`, `explorer_m2_3`)
- **Key findings**:
  - `calculateRSI`: Loop `for (int i = prices.size() - period; i < prices.size() - 1; i++)` runs only `period - 1` (13) times instead of 14, but divides by 14, creating a 7.14% distortion. Collecting 14 differences requires starting at `prices.size() - period - 1` over `period + 1` (15) prices.
  - `calculateRSI`: Flat market where all prices are equal produces `avgGain == 0.0 && avgLoss == 0.0`, triggering `if (avgLoss == 0) return 100;`, spuriously returning 100.0 (overbought) instead of neutral 50.0.
  - `calculateEMA`: Truncates history to only `period` prices (`prices.size() - period`), ignoring up to 41 of 50 candles, and seeds with an arbitrary single price tick instead of an SMA seed over the first `period` elements before smoothing over subsequent elements.
  - Method visibility: Currently `private`, blocking direct unit testing in `StrategyEngineTest.java` and status telemetry. Making them `public` is backwards-compatible and enables comprehensive unit testing.
- **Unexplored areas**: None for Indicator Math.

## Key Decisions Made
- Confirmed exact mathematical index bounds for `calculateRSI`: start index `prices.size() - period - 1`, iterating to `prices.size() - 1` to gather exactly `period` differences.
- Confirmed flat market guard: `if (avgGain == 0.0 && avgLoss == 0.0) return 50.0;`
- Confirmed SMA-seeded full-history smoothing for `calculateEMA`.
- Designed unified diff for `StrategyEngine.java`.
- Handoff written and orchestrator notified.

## Artifact Index
- c:\Users\keval\teleStock\.agents\teamwork\explorer_m2_1\DISPATCH.md — Dispatch log
- c:\Users\keval\teleStock\.agents\teamwork\explorer_m2_1\progress.md — Progress heartbeat
- c:\Users\keval\teleStock\.agents\teamwork\explorer_m2_1\BRIEFING.md — Situational awareness
- c:\Users\keval\teleStock\.agents\teamwork\explorer_m2_1\handoff.md — 5-component handoff report
