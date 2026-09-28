<#
.SYNOPSIS
    teleStock Automated Opaque-Box E2E Test Suite (PowerShell Runner)
.DESCRIPTION
    Validates teleStock Spring Boot application against requirements in:
    - ORIGINAL_REQUEST.md
    - PROJECT.md
    - TEST_INFRA.md

    Executes Tiers 1-4:
      Tier 1: Core Happy-Path Verification (30 tests)
      Tier 2: Boundary & Error Handling (30 tests)
      Tier 3: Pairwise Combinatorial Interactions (8 tests)
      Tier 4: Real-World Application Scenarios (5 scenarios)
.PARAMETER BaseUrl
    Base URL of running teleStock instance (default: http://localhost:8080 or $env:PORT)
.PARAMETER Port
    Override port (e.g. 8080)
.PARAMETER Tier
    Execute only specific tier (1, 2, 3, or 4)
.PARAMETER Feature
    Filter tests by feature (F1..F6 or 1..6)
.PARAMETER Scenario
    Execute specific real-world scenario (1..5)
.PARAMETER SelfTest
    Start an in-process specification mock server to verify the test suite
.PARAMETER Report
    Path to save the JSON test report
#>

param(
    [string]$BaseUrl = $env:BASE_URL,
    [int]$Port = 0,
    [int]$Tier = 0,
    [string]$Feature = "",
    [int]$Scenario = 0,
    [switch]$SelfTest,
    [string]$Report = "e2e/test_report.json",
    [int]$Timeout = 5
)

$ErrorActionPreference = "Stop"

if (-not $BaseUrl) {
    if ($env:PORT) {
        $BaseUrl = "http://localhost:$env:PORT"
    } else {
        $BaseUrl = "http://localhost:8080"
    }
}

if ($Port -gt 0) {
    $BaseUrl = "http://localhost:$Port"
}

Write-Host "================================================================================" -ForegroundColor Cyan
Write-Host "  teleStock Automated Opaque-Box E2E Test Suite (PowerShell Runner)" -ForegroundColor Cyan
Write-Host "  Target URL: $BaseUrl" -ForegroundColor Cyan
Write-Host "================================================================================" -ForegroundColor Cyan

# Check if Python is available to run full test suite with JSON reporting
$pythonCmd = Get-Command python -ErrorAction SilentlyContinue

if ($pythonCmd) {
    $pyArgs = @("e2e/test_cold_boot_e2e.py")
    if ($BaseUrl) { $pyArgs += @("--base-url", $BaseUrl) }
    if ($Tier -gt 0) { $pyArgs += @("--tier", "$Tier") }
    if ($Feature) { $pyArgs += @("--feature", "$Feature") }
    if ($Scenario -gt 0) { $pyArgs += @("--scenario", "$Scenario") }
    if ($SelfTest) { $pyArgs += "--self-test" }
    if ($Report) { $pyArgs += @("--report", $Report) }
    if ($Timeout) { $pyArgs += @("--timeout", "$Timeout") }

    Write-Host "[INFO] Executing E2E test harness via Python 3..." -ForegroundColor Gray
    & python $pyArgs
    exit $LASTEXITCODE
} else {
    Write-Host "[INFO] Python not found on PATH. Executing native PowerShell probe..." -ForegroundColor Yellow
    
    $totalPassed = 0
    $totalFailed = 0

    function Assert-Test([string]$id, [string]$name, [scriptblock]$action) {
        try {
            $sw = [System.Diagnostics.Stopwatch]::StartNew()
            & $action
            $sw.Stop()
            Write-Host ("{0,-12} | PASS | {1,6} ms | {2}" -f $id, $sw.ElapsedMilliseconds, $name) -ForegroundColor Green
            $script:totalPassed++
        } catch {
            $sw.Stop()
            Write-Host ("{0,-12} | FAIL | {1,6} ms | {2}" -f $id, $sw.ElapsedMilliseconds, $name) -ForegroundColor Red
            Write-Host ("             └──> ERROR: {0}" -f $_.Exception.Message) -ForegroundColor DarkRed
            $script:totalFailed++
        }
    }

    Write-Host "`nRunning Core Health & Cold Boot Probes against $BaseUrl...`n" -ForegroundColor Gray
    
    Assert-Test "T1_F1_01" "Health endpoint HTTP 200" {
        $resp = Invoke-RestMethod -Uri "$BaseUrl/health" -Method Get -TimeoutSec $Timeout
        if ($resp.status -ne "UP") { throw "Status is not UP: $($resp | ConvertTo-Json)" }
    }

    Assert-Test "T1_F2_01" "Config endpoint HTTP 200" {
        $cfg = Invoke-RestMethod -Uri "$BaseUrl/api/config" -Method Get -TimeoutSec $Timeout
        if (-not $cfg.tradingEnabled -and $cfg.tradingEnabled -ne $false) { throw "Missing tradingEnabled" }
    }

    Assert-Test "T1_F3_01" "Strategy status cold-boot warmed up" {
        $stat = Invoke-RestMethod -Uri "$BaseUrl/api/strategy/status" -Method Get -TimeoutSec $Timeout
        if ($stat.coldBootWarmedUp -ne $true) { throw "Strategy coldBootWarmedUp is not true" }
    }

    Assert-Test "T1_F4_01" "Simulated trade execution" {
        $buy = Invoke-WebRequest -Uri "$BaseUrl/api/test/buy" -Method Get -TimeoutSec $Timeout
        if ($buy.Content -notmatch "Buy executed") { throw "Buy failed: $($buy.Content)" }
    }

    Write-Host "`nSummary: Passed=$totalPassed, Failed=$totalFailed"
    if ($totalFailed -eq 0) { exit 0 } else { exit 1 }
}
