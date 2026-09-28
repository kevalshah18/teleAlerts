# BRIEFING — 2026-09-27T14:43:00Z

## Mission
Investigate and design the exact fix strategy for Milestone 2 - Dynamic Position Sizing & Concurrency in StrategyEngine.java.

## 🔒 My Identity
- Archetype: explorer
- Roles: explorer, investigator, designer
- Working directory: c:\Users\keval\teleStock\.agents\teamwork\explorer_m2_2
- Original parent: a0b7ac32-5baa-4658-8b65-50f7031b1df7
- Milestone: Milestone 2 - Dynamic Position Sizing & Concurrency in StrategyEngine

## 🔒 Key Constraints
- Read-only investigation — do NOT implement or modify source/test files
- Write only to assigned directory: c:\Users\keval\teleStock\.agents\teamwork\explorer_m2_2
- Update progress.md with timestamp
- Comprehensive handoff.md following 5-component handoff protocol
- Send message to orchestrator upon completion

## Current Parent
- Conversation ID: a0b7ac32-5baa-4658-8b65-50f7031b1df7
- Updated: 2026-09-27T14:43:00Z

## Investigation State
- **Explored paths**:
  - `ORIGINAL_REQUEST.md`
  - `PROJECT.md`
  - `src/main/java/com/telestock/strategy/StrategyEngine.java`
  - `src/main/java/com/telestock/ledger/LedgerService.java`
  - `src/main/java/com/telestock/model/SystemConfig.java`
  - `src/main/java/com/telestock/model/Position.java`
  - `src/main/java/com/telestock/config/ConfigService.java`
  - `src/main/java/com/telestock/repository/PositionRepository.java`
  - Peer explorer scopes (`explorer_m2_1`, `explorer_m2_3`)
- **Key findings**:
  - Hardcoded `(int)(10000 / ltp)` requests ~₹10,000 for every trade, causing subsequent trades to be rejected by `LedgerService.executeBuy` once initial ₹10,000 capital is partially spent.
  - Stocks with LTP > 10,000 truncate to `qty = 0`, forced to `1`, which immediately fails in `LedgerService` when capital < LTP, and fails to scale even if capital is larger.
  - Sizing must be dynamic: `availableCapital / openSlots` with `MAX_CONCURRENT_POSITIONS = 5` and minimum 1 share if `availableCapital >= ltp`.
  - Non-thread-safe `ArrayList` in `candleCloses` causes `ConcurrentModificationException` and dirty reads under Spring scheduler and REST/telemetry access.
- **Unexplored areas**: None. Complete investigation and design delivered.

## Key Decisions Made
- Designed `calculatePositionSize(double ltp, double availableCapital, int openSlots)` pure method with safety clamps.
- Guarded candidate BUY before and after AI veto with `MAX_CONCURRENT_POSITIONS = 5` and `availableCapital < ltp` check.
- Specified replacement of `ArrayList` with `CopyOnWriteArrayList` in `candleCloses`.
- Added public candle accessors for testing and telemetry.

## Artifact Index
- `DISPATCH.md` — Task dispatch record
- `BRIEFING.md` — Persistent context & memory
- `progress.md` — Liveness heartbeat & task progress
- `handoff.md` — Final 5-component handoff report
