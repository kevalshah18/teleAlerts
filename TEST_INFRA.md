# E2E Test Infra: teleStock

## Test Philosophy
- Opaque-box, requirement-driven per ORIGINAL_REQUEST.md.
- Methodology: Category-Partition + Boundary Value Analysis + Pairwise Combinatorial + Real-World Workload Testing.
- Key Acceptance Criteria:
  1. Prove the Spring Boot server boots cleanly on port 8080 (or ${PORT:8080}).
  2. Prove that the bot successfully calculates indicators (EMA9, EMA21, RSI14) immediately after a fresh cold reboot without waiting for hours of live data collection.
  3. Prove that the bot can execute a simulated trade immediately after cold boot.

## Feature Inventory
| # | Feature | Source (requirement) | Tier 1 | Tier 2 | Tier 3 |
|---|---------|---------------------|:------:|:------:|:------:|
| 1 | Port 8080 Clean Boot & Health Check | ORIGINAL_REQUEST §Acceptance Criteria & R3 | 5 | 5 | ✓ |
| 2 | Dynamic Environment Config Injection | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 3 | Immediate Indicator Calculation Post-Boot | ORIGINAL_REQUEST §Acceptance Criteria & R2 | 5 | 5 | ✓ |
| 4 | Immediate Cold-Boot Trade Execution | ORIGINAL_REQUEST §Acceptance Criteria & R2 | 5 | 5 | ✓ |
| 5 | Dynamic Position Sizing & Multi-Trade | ORIGINAL_REQUEST §R1 | 5 | 5 | ✓ |
| 6 | Price Streaming & Dashboard REST API | ORIGINAL_REQUEST §R3 | 5 | 5 | ✓ |

## Test Architecture
- **Test Runner**: Standalone Python / PowerShell test script (`test_e2e_cold_boot.py` or `.ps1`) independent of internal class dependencies.
- **Protocol**: HTTP REST API calls to `http://localhost:8080` (or `$PORT`).
- **Pass/Fail Semantics**: All tier tests must pass with exit code 0.
- **Directory Layout**:
  - `e2e/`: Test scripts, test scenarios, mock payloads, and verification runners.

## Real-World Application Scenarios (Tier 4)
| # | Scenario | Features Exercised | Complexity |
|---|----------|--------------------|------------|
| 1 | Fresh Container Cold Boot on Ephemeral Cloud | F1, F2, F3 (Immediate warm-up, port bind) | High |
| 2 | Immediate Crossover Buy & Ledger Accounting | F3, F4, F5 (Indicators ready -> Buy executed -> Capital deducted) | High |
| 3 | Multi-Stock Concurrent Order Execution | F4, F5 (Multiple positions within capital limits without starvation) | Medium |
| 4 | Offline / Market-Closed Boot Resilience | F1, F3, F4 (Synthetic fallback warm-up when market APIs offline) | High |
| 5 | Position Exit on Stop-Loss / Target | F4, F5 (Price moves to target or stop -> Sell executed -> Capital credited) | Medium |

## Coverage Thresholds
- Tier 1: ≥5 per feature (Total ≥30)
- Tier 2: ≥5 per feature (boundary and error conditions) (Total ≥30)
- Tier 3: pairwise coverage of major feature interactions
- Tier 4: ≥5 realistic application scenarios
