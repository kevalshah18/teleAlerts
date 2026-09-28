## 2026-09-27T14:20:00Z
You are Test Writer for the teleStock E2E Testing Track.
Your assigned working directory is: c:\Users\keval\teleStock\.agents\teamwork\test_writer_e2e\
The original user request is at: c:\Users\keval\teleStock\.agents\teamwork\ORIGINAL_REQUEST.md
The test infrastructure plan is at: c:\Users\keval\teleStock\TEST_INFRA.md
The project scope is at: c:\Users\keval\teleStock\PROJECT.md

Mission:
Design and build an automated opaque-box E2E test suite satisfying the Acceptance Criteria from ORIGINAL_REQUEST.md:
1. Create a standalone test runner script in `e2e/test_cold_boot_e2e.py` (and/or PowerShell `e2e/test_cold_boot_e2e.ps1`).
2. The script must be able to:
   - Prove the server boots cleanly on port 8080 (or $PORT) and responds to `GET /health` with HTTP 200 `{"status":"UP"}`.
   - Prove the bot calculates technical indicators (EMA/RSI) immediately on cold boot without waiting hours for live collection (via `GET /api/strategy/status` or `POST /api/test/warmup`).
   - Prove that a simulated trade can execute immediately after a fresh reboot and update positions and capital in the ledger.
   - Provide clear exit code 0 on pass, non-zero on failure, with structured summary output.
3. Implement Tiers 1-4 test scenarios per `TEST_INFRA.md`.
4. When test suite is authored and ready, create `c:\Users\keval\teleStock\TEST_READY.md` summarizing the test runner command, test counts across Tiers 1-4, and feature checklist.
5. Update your progress.md and write your handoff report to `c:\Users\keval\teleStock\.agents\teamwork\test_writer_e2e\handoff.md`.
6. Send a message to orchestrator when done.
