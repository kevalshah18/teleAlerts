# Original User Request

## 2026-09-27T13:57:46Z

# Teamwork Project Prompt

> Requested team: Full team

Conduct a comprehensive audit and debugging session on the `teleStock` trading application to identify and fix all issues preventing the bot from running reliably and executing trades both locally and on the cloud.

Working directory: c:\Users\keval\teleStock
Integrity mode: development

## Requirements

### R1. Comprehensive Application Audit
Analyze the entire Spring Boot application—including the data ingestion, the `StrategyEngine`, capital management, and configuration state—to find exactly what is hindering the bot from executing trades. 

### R2. Fix Execution Blockers & Uptime Constraints
Implement robust fixes for the identified issues. Specifically, ensure the bot can evaluate strategies and execute trades shortly after a cold boot, rather than failing silently or requiring hours of continuous uptime before making its first decision (which completely breaks on ephemeral cloud hosts like Render).

### R3. Cloud Deployment Strategy
After fixing the application, provide a foolproof, live way to run this on the cloud (e.g., Render + UptimeRobot or an alternative) so the user can monitor the dashboard and receive trades with their laptop completely closed.

## Acceptance Criteria

### Programmatic Verification
- [ ] A programmatic test script must be created and executed to prove the server boots cleanly on port 8080.
- [ ] The programmatic test script must prove that the bot successfully calculates indicators (EMA/RSI) and can execute a simulated trade immediately after a fresh reboot, without waiting for hours of live data collection.


## 2026-10-03T16:22:41Z

# Teamwork Project Prompt

> Requested team: Full team

Implement a final security sweep on the `teleStock` application to ensure no secrets are checked into source control, and build a secure login page where credentials are not exposed on GitHub.

Working directory: c:\Users\keval\teleStock
Integrity mode: development

## Requirements

### R1. Secrets Audit & Remediation
Verify that all environment variables (API keys, bot tokens) are strictly local and not hardcoded anywhere in the git history or codebase. If any are found, remove them.

### R2. Secure Login Page
Create a basic authentication login page (e.g. using Spring Security) that guards the dashboard. The application code must be pushed to GitHub so Render can build it, but the username (`keval`) and password (`keval123`) must **not** be hardcoded anywhere in the source code. They must be configured to pull from Environment Variables (`ADMIN_USER`, `ADMIN_PASS`) so absolutely no one else can see them.

### R3. Final Security Check
Perform a general security review of the application to ensure it is up to date and safe for public deployment on Render.

## Acceptance Criteria

### Programmatic Verification
- [ ] An automated script must be executed to prove that the exact string `keval123` and all API keys do not exist anywhere in the tracked git files.
- [ ] An automated test script must prove that an unauthenticated request to the dashboard is blocked (401/302), and a request with the correct environment credentials successfully loads the dashboard.
