# Milestone 1 Exploration & Fix Design Report: Configuration & Property Injection

**Agent**: Explorer M1-1  
**Working Directory**: `c:\Users\keval\teleStock\.agents\teamwork\explorer_m1_1\`  
**Date**: 2026-09-27  
**Status**: COMPLETE (Hard Handoff)

---

## 1. Observation

### 1.1 `GeminiAiService.java` Property Injection Defect
- **File**: `c:\Users\keval\teleStock\src\main\java\com\telestock\ai\GeminiAiService.java`
- **Lines 21–25**:
  ```java
  21:     @Value("")
  22:     private String apiKey;
  23: 
  24:     @Value("")
  25:     private String model;
  ```
- **Lines 27–36**:
  ```java
  27:     public GeminiDecision evaluateSignal(String symbol, double price, String type) {
  28:         if (apiKey == null || apiKey.isEmpty()) {
  29:             log.warn("Gemini API Key missing, defaulting to approved");
  30:             return new GeminiDecision(true, 0.0, "API key missing, auto-approved.");
  31:         }
  32:         
  33:         String url = String.format("https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s", model, apiKey);
  ```
- **Direct Observation**:
  - The annotation `@Value("")` injects a literal empty string `""` into `apiKey` and `model`.
  - Spring's `PropertySourcesPlaceholderConfigurer` does not evaluate placeholders because no `${...}` SpEL syntax is present.
  - Any configuration provided in `application.yml` (`gemini.api.key`, `gemini.api.model`) or environment variables (`GEMINI_API_KEY`, `GEMINI_API_MODEL`) is completely ignored.
  - Because `apiKey` is always `""`, line 28 always triggers: `apiKey.isEmpty() == true`. The AI evaluation is permanently bypassed with `"API key missing, auto-approved."`.
  - If `apiKey` were somehow set while `model` remained `@Value("")`, line 33 would construct an invalid URL (`https://generativelanguage.googleapis.com/v1beta/models/:generateContent?key=...`), causing HTTP 404 / 400 failures.

---

### 1.2 `TelegramService.java` Property Injection Defect
- **File**: `c:\Users\keval\teleStock\src\main\java\com\telestock\telegram\TelegramService.java`
- **Lines 16–26**:
  ```java
  16:     @Value("")
  17:     private String token;
  18: 
  19:     @Value("")
  20:     private String chatId;
  21: 
  22:     public void sendMessage(String message) {
  23:         if (token == null || token.isEmpty() || chatId == null || chatId.isEmpty()) {
  24:             log.warn("Telegram credentials not set. Skipping alert: {}", message);
  25:             return;
  26:         }
  ```
- **Direct Observation**:
  - The annotation `@Value("")` injects literal empty string `""` into both `token` and `chatId`.
  - `telegram.bot.token` and `telegram.chat.id` defined in `application.yml` or passed via environment variables (`TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`) are never injected.
  - At line 23, `token.isEmpty() || chatId.isEmpty()` is permanently true.
  - Every call to `sendMessage()` logs `"Telegram credentials not set. Skipping alert"` and exits. Zero Telegram notifications can ever be dispatched.

---

### 1.3 `application.yml` Configuration & Port Binding Defects
- **File**: `c:\Users\keval\teleStock\src\main\resources\application.yml`
- **Lines 13–26**:
  ```yaml
  13: server:
  14:   port: 8080
  15: 
  16: gemini:
  17:   api:
  18:     key: ""
  19:     model: "gemini-2.5-flash"
  20: 
  21: telegram:
  22:   bot:
  23:     token: ""
  24:   chat:
  25:     id: ""
  ```
- **Direct Observation**:
  - `server.port: 8080`: Hardcoded literal integer `8080`. Cloud platforms (Render, Heroku) inject an arbitrary port via the `PORT` environment variable (e.g. `PORT=10000`). When deploying the Docker container on Render, the container binds to 8080 while Render's HTTP reverse proxy routes traffic to `$PORT`, resulting in port bind failure and deployment termination.
  - `gemini.api.key: ""`: Hardcoded empty string without environment variable placeholder expansion (`${GEMINI_API_KEY:}`).
  - `gemini.api.model: "gemini-2.5-flash"`: Hardcoded model without environment variable placeholder expansion (`${GEMINI_API_MODEL:gemini-2.5-flash}`).
  - `telegram.bot.token: ""`: Hardcoded empty string without environment variable placeholder expansion (`${TELEGRAM_BOT_TOKEN:}`).
  - `telegram.chat.id: ""`: Hardcoded empty string without environment variable placeholder expansion (`${TELEGRAM_CHAT_ID:}`).

---

## 2. Logic Chain

1. **Spring Value Resolution Mechanism**:
   Spring Boot processes `@Value` annotations using `PropertySourcesPlaceholderConfigurer`. For `@Value` to resolve against configuration files, environment variables, and system properties, the annotation argument must adhere to SpEL property syntax: `${property.name:default_value}`.
   *(Reference: Observation 1.1, 1.2)*

2. **Resolution of Gemini Service Properties**:
   Replacing `@Value("")` with:
   - `@Value("${gemini.api.key:}")`
   - `@Value("${gemini.api.model:gemini-2.5-flash}")`
   ensures:
   - When `gemini.api.key` (or env var `GEMINI_API_KEY`) is set, it is injected into `apiKey`.
   - When unset, it safely falls back to empty string `""`.
   - `GeminiAiService.evaluateSignal()` checks `apiKey == null || apiKey.isEmpty()`. When unset, it gracefully logs a warning and returns auto-approved `GeminiDecision(true, 0.0, "API key missing, auto-approved.")`, allowing paper trading to proceed seamlessly without requiring a mandatory Gemini key.
   - When `gemini.api.model` is unset, it defaults to `gemini-2.5-flash`.
   *(Reference: Observation 1.1)*

3. **Resolution of Telegram Service Properties**:
   Replacing `@Value("")` with:
   - `@Value("${telegram.bot.token:}")`
   - `@Value("${telegram.chat.id:}")`
   ensures:
   - When credentials are provided (via `application.yml` or `TELEGRAM_BOT_TOKEN`/`TELEGRAM_CHAT_ID`), they are injected into `token` and `chatId`.
   - When missing, both fall back to `""`.
   - `TelegramService.sendMessage()` checks `token == null || token.isEmpty() || chatId == null || chatId.isEmpty()`. When missing, it safely skips message transmission without throwing exceptions.
   *(Reference: Observation 1.2)*

4. **Two-Tier Defense in `application.yml`**:
   Configuring `application.yml` as:
   ```yaml
   server:
     port: ${PORT:8080}

   gemini:
     api:
       key: ${GEMINI_API_KEY:}
       model: ${GEMINI_API_MODEL:gemini-2.5-flash}

   telegram:
     bot:
       token: ${TELEGRAM_BOT_TOKEN:}
     chat:
       id: ${TELEGRAM_CHAT_ID:}
   ```
   establishes a robust two-tier configuration architecture:
   - **Tier 1 (YAML layer)**: Maps standard cloud environment variables (`PORT`, `GEMINI_API_KEY`, `GEMINI_API_MODEL`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`) to standard hierarchical property names with clean fallback values.
   - **Tier 2 (Java `@Value` layer)**: Default fallback values in `@Value("${...:}")` guarantee that even if `application.yml` is overridden by external test configurations or profiles, bean instantiation never fails due to missing placeholders.
   *(Reference: Observation 1.3)*

---

## 3. Proposed Changes & Code Artifacts

### 3.1 `GeminiAiService.java`
**Target File**: `src/main/java/com/telestock/ai/GeminiAiService.java`  
**Patch**: `.agents/teamwork/explorer_m1_1/gemini_ai_service.patch`  
**Full Replacement**: `.agents/teamwork/explorer_m1_1/proposed_GeminiAiService.java`

#### Diff:
```diff
--- a/src/main/java/com/telestock/ai/GeminiAiService.java
+++ b/src/main/java/com/telestock/ai/GeminiAiService.java
@@ -21,5 +21,5 @@ public class GeminiAiService {
-    @Value("")
+    @Value("${gemini.api.key:}")
     private String apiKey;
 
-    @Value("")
+    @Value("${gemini.api.model:gemini-2.5-flash}")
     private String model;
```

---

### 3.2 `TelegramService.java`
**Target File**: `src/main/java/com/telestock/telegram/TelegramService.java`  
**Patch**: `.agents/teamwork/explorer_m1_1/telegram_service.patch`  
**Full Replacement**: `.agents/teamwork/explorer_m1_1/proposed_TelegramService.java`

#### Diff:
```diff
--- a/src/main/java/com/telestock/telegram/TelegramService.java
+++ b/src/main/java/com/telestock/telegram/TelegramService.java
@@ -16,5 +16,5 @@ public class TelegramService {
-    @Value("")
+    @Value("${telegram.bot.token:}")
     private String token;
 
-    @Value("")
+    @Value("${telegram.chat.id:}")
     private String chatId;
```

---

### 3.3 `application.yml`
**Target File**: `src/main/resources/application.yml`  
**Patch**: `.agents/teamwork/explorer_m1_1/application_yml.patch`  
**Full Replacement**: `.agents/teamwork/explorer_m1_1/proposed_application.yml`

#### Diff:
```diff
--- a/src/main/resources/application.yml
+++ b/src/main/resources/application.yml
@@ -13,14 +13,14 @@
 server:
-  port: 8080
+  port: ${PORT:8080}
 
 gemini:
   api:
-    key: ""
-    model: "gemini-2.5-flash"
+    key: ${GEMINI_API_KEY:}
+    model: ${GEMINI_API_MODEL:gemini-2.5-flash}
 
 telegram:
   bot:
-    token: ""
+    token: ${TELEGRAM_BOT_TOKEN:}
   chat:
-    id: ""
+    id: ${TELEGRAM_CHAT_ID:}
```

---

### 3.4 Integration Test Suite Design
**Target File**: `src/test/java/com/telestock/config/PropertyInjectionTest.java`  
**File Created**: `.agents/teamwork/explorer_m1_1/proposed_PropertyInjectionTest.java`

Covers 4 critical verification scenarios:
1. `testGeminiPropertiesInjected`: Verifies `${gemini.api.key}` and `${gemini.api.model}` are injected into `GeminiAiService`.
2. `testTelegramPropertiesInjected`: Verifies `${telegram.bot.token}` and `${telegram.chat.id}` are injected into `TelegramService`.
3. `testGeminiAutoApproveWhenKeyEmpty`: Verifies `evaluateSignal` returns auto-approved (`true`) with message `"API key missing, auto-approved."` without attempting network calls.
4. `testTelegramSkipWhenCredentialsEmpty`: Verifies `sendMessage` safely skips alert without throwing exceptions when credentials are empty.

---

## 4. Caveats

1. **Avoid Circular Placeholders**:
   In `application.yml`, do not define `key: ${GEMINI_API_KEY:${gemini.api.key:}}`. In Spring Boot, referencing the property name inside its own definition causes `IllegalArgumentException: Circular placeholder reference 'gemini.api.key' in property definitions`. Use `${GEMINI_API_KEY:}`.
2. **Whitespace in Environment Variables**:
   If an environment variable is set to `"   "` (blank spaces), `isEmpty()` returns false. Both `GeminiAiService` and `TelegramService` can be optionally hardened to `isBlank()` (Java 11+ / Java 17).
3. **Property Precedence Order**:
   Spring Boot property source order is:
   1. Command-line arguments (`--server.port=9090`)
   2. JVM System Properties (`-Dserver.port=9090`)
   3. OS Environment Variables (`PORT=9090`, `GEMINI_API_KEY=...`)
   4. `application.yml`
   This hierarchy guarantees that cloud platforms setting OS env vars or docker `-e` flags will reliably override `application.yml`.
4. **Scope Boundaries**:
   - `LedgerServiceTest.java` Mockito NPE fix is assigned to Explorer M1-2.
   - `HealthController` (`/health`), `ThreadPoolTaskScheduler`, and SSE thread leak in `DashboardController` are assigned to Explorer M1-3.

---

## 5. Conclusion

- The root cause of non-configurable AI and Telegram credentials is the use of literal `@Value("")` instead of Spring SpEL property placeholders.
- The root cause of cloud deployment port binding failure is the hardcoded `server.port: 8080` in `application.yml`.
- The proposed changes in `GeminiAiService.java`, `TelegramService.java`, and `application.yml` completely resolve these defects, ensuring 100% cloud readiness, seamless local execution, and graceful fallback when credentials are not configured.

---

## 6. Verification Method

### 6.1 Automated Unit / Integration Test
Apply the patch and run the proposed test:
```powershell
./mvnw test -Dtest=PropertyInjectionTest
```
**Expected Result**: All 4 tests pass with exit code 0.

### 6.2 Manual Verification of Local & Cloud Port Binding
1. **Local Default (Port 8080)**:
   ```powershell
   ./mvnw spring-boot:run
   ```
   Verify logs indicate `Tomcat started on port 8080 (http)` and `curl http://localhost:8080/api/config` returns 200 OK.
2. **Cloud Dynamic Port Binding (e.g. Port 9090)**:
   ```powershell
   $env:PORT="9090"
   ./mvnw spring-boot:run
   ```
   Verify logs indicate `Tomcat started on port 9090 (http)` and `curl http://localhost:9090/api/config` returns 200 OK.

### 6.3 Invalidation Conditions
- If `GeminiAiService` fails to start when `GEMINI_API_KEY` is not present in the environment.
- If `TelegramService` throws a `NullPointerException` or `RestClientException` when token or chatId are empty.
- If Spring Boot throws `Circular placeholder reference` on startup.
