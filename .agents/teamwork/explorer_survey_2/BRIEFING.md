# BRIEFING — 2026-09-27T14:26:00Z

## Mission
Investigate StrategyEngine, technical indicator calculations (EMA, RSI, MACD, etc.), candle history storage/buffers, and cold boot behavior in teleStock.

## 🔒 My Identity
- Archetype: Explorer
- Roles: Teamwork explorer (read-only investigation, analysis, structured reporting)
- Working directory: c:\Users\keval\teleStock\.agents\teamwork\explorer_survey_2
- Original parent: a0b7ac32-5baa-4658-8b65-50f7031b1df7
- Milestone: Explorer Survey 2

## 🔒 Key Constraints
- Read-only investigation — do NOT implement
- Do NOT modify source code or tests
- Update progress.md with timestamp header after meaningful progress
- Write comprehensive survey report to handoff.md
- Send message to orchestrator with summary and handoff path

## Current Parent
- Conversation ID: a0b7ac32-5baa-4658-8b65-50f7031b1df7
- Updated: 2026-09-27T14:26:00Z

## Investigation State
- **Explored paths**:
  - `src/main/java/com/telestock/strategy/StrategyEngine.java`
  - `src/main/java/com/telestock/feed/LiveMarketDataService.java`
  - `src/main/java/com/telestock/feed/NseSymbolDiscoveryService.java`
  - `src/main/java/com/telestock/ai/GeminiAiService.java`
  - `src/main/java/com/telestock/ledger/LedgerService.java`
  - `src/main/java/com/telestock/model/*`
  - Peer handoffs (`explorer_survey_1`, `explorer_survey_3`)
- **Key findings**:
  - 105-minute starvation: 21 5-min candles must accumulate live in volatile memory before evaluating.
  - RSI calculation has an off-by-one loop accumulating only 13 changes for a 14-period RSI, plus returns 100 on flat markets.
  - EMA calculation truncates to last `period` elements with single-price seed, creating seed distortion.
  - Strategy trigger is trend level (`ema9 > ema21`), not a crossover; lacks whipsaw guard after stop loss.
  - `candleCloses` uses non-thread-safe `ArrayList` inside `ConcurrentHashMap`.
  - Discarding OHLV: Only close prices are kept; Open, High, Low, Volume are discarded.
  - Single-threaded `@Scheduled` causes Gemini AI calls and Yahoo polls to block the entire application.
  - Position sizing hardcoded to Rs. 10,000 against Rs. 10,000 total capital blocks all subsequent trades.
  - Fast historical backfill using Yahoo Finance chart API `/v8/finance/chart/{symbol}?interval=5m&range=2d` enables instant cold-boot readiness (<2s).
- **Unexplored areas**: None. All core questions investigated and answered.

## Key Decisions Made
- Fully documented all 5 survey questions with code references and mathematical proofs.
- Designed comprehensive multi-tier warm-up solution (Yahoo Chart API + synthetic seed fallback + persistent DB option).

## Artifact Index
- `c:\Users\keval\teleStock\.agents\teamwork\explorer_survey_2\DISPATCH.md` — Received prompts log
- `c:\Users\keval\teleStock\.agents\teamwork\explorer_survey_2\progress.md` — Liveness & status tracking
- `c:\Users\keval\teleStock\.agents\teamwork\explorer_survey_2\BRIEFING.md` — Working memory
- `c:\Users\keval\teleStock\.agents\teamwork\explorer_survey_2\handoff.md` — Final survey report
