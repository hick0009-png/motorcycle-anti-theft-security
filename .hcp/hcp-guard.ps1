#!/usr/bin/env pwsh
<#
.SYNOPSIS
    hcp-guard — Wrapper ที่ auto-detect error จาก Gradle build และ trigger recovery อัตโนมัติ

.DESCRIPTION
    รันคำสั่ง Gradle (หรือคำสั่งอื่น) แล้วตรวจสอบ output:
    - หาก BUILD FAILED → แจ้งเตือน → บันทึก error checkpoint → เสนอ recover
    - หาก BUILD SUCCESSFUL → บันทึก checkpoint อัตโนมัติ

.PARAMETER Command
    คำสั่งที่จะรัน (default: "assembleDebug")

.PARAMETER Phase
    Phase ปัจจุบัน

.PARAMETER TaskId
    Task ID ปัจจุบัน

.PARAMETER Description
    คำอธิบาย action ที่ทำ

.PARAMETER AutoRecover
    กู้คืนอัตโนมัติเมื่อ build fail (default: true)

.EXAMPLE
    .\hcp-guard.ps1 -Phase P1 -TaskId SEC-01 -Description "เพิ่ม SecureKeyManager"
    # รัน build + checkpoint + auto-recover ถ้า fail

.EXAMPLE
    .\hcp-guard.ps1 -Command "test" -Phase P3 -TaskId SEN-01 -Description "Unit test sensors" -AutoRecover $false
    # รัน test โดยไม่ auto-recover
#>
param(
    [string]$Command     = "assembleDebug",
    [string]$Phase       = "P1",
    [string]$TaskId      = "SEC-01",
    [string]$Description = "Build verification",
    [bool]$AutoRecover   = $true
)

$HCPDir = "d:\security\.hcp"

Write-Host "`n🛡️  HCP-GUARD activated" -ForegroundColor Cyan
Write-Host "   Command: $Command | Phase: $Phase/$TaskId" -ForegroundColor Gray
Write-Host "   Description: $Description`n" -ForegroundColor Gray

# --- Load Config ---
$Config = Get-Content "$HCPDir\hcp-config.json" | ConvertFrom-Json
$ErrorPatterns = $Config.guard.error_patterns

# --- Run Gradle with JAVA_HOME ---
& "d:\security\scripts\setup_env.ps1" *>$null

$StartTime = Get-Date
$Output = & "d:\security\MotorcycleAntiTheftSensor\gradlew.bat" `
    -p "d:\security\MotorcycleAntiTheftSensor" $Command 2>&1
$ElapsedMs = [int]((Get-Date) - $StartTime).TotalMilliseconds

$OutputText = $Output -join "`n"

# --- Detect Errors ---
$ErrorsFound = @()
foreach ($pattern in $ErrorPatterns) {
    if ($OutputText -match [regex]::Escape($pattern)) {
        $ErrorsFound += $pattern
    }
}

$BuildSuccess = ($OutputText -match "BUILD SUCCESSFUL")
$HasErrors    = $ErrorsFound.Count -gt 0

# --- Display Result ---
if ($BuildSuccess -and -not $HasErrors) {
    Write-Host "✅ BUILD SUCCESSFUL in ${ElapsedMs}ms" -ForegroundColor Green

    # Auto-checkpoint on success
    & "$HCPDir\checkpoint.ps1" `
        -Phase $Phase `
        -TaskId $TaskId `
        -Status "DONE" `
        -Description "$Description | Auto-checkpoint by hcp-guard | Build: SUCCESS" `
        -AgentName "hcp-guard" `
        -Tags "auto,build-success"
} else {
    Write-Host "❌ BUILD FAILED in ${ElapsedMs}ms" -ForegroundColor Red
    Write-Host "`nError patterns detected:" -ForegroundColor Yellow
    foreach ($e in $ErrorsFound) { Write-Host "  - $e" -ForegroundColor Red }

    # Scan output for actual error lines
    $ErrorLines = $Output | Where-Object { $_ -match "error:|FAILED|Exception" } | Select-Object -First 10
    if ($ErrorLines) {
        Write-Host "`nFirst 10 error lines:" -ForegroundColor Yellow
        $ErrorLines | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    }

    # Save error checkpoint
    & "$HCPDir\checkpoint.ps1" `
        -Phase $Phase `
        -TaskId $TaskId `
        -Status "FAILED" `
        -Description "$Description | Build FAILED: $($ErrorsFound -join ', ')" `
        -AgentName "hcp-guard" `
        -Tags "auto,build-failed,error"

    # Auto-recover?
    if ($AutoRecover -and $Config.guard.auto_recover_on_error) {
        Write-Host "`n⚠️  Auto-recovery triggered..." -ForegroundColor Yellow
        & "$HCPDir\recover.ps1" -Mode soft
    } else {
        Write-Host "`n⚠️  AutoRecover is disabled. Run manually: .\recover.ps1" -ForegroundColor Yellow
    }
}

# --- Return output for pipeline use ---
return @{
    success = $BuildSuccess
    errors  = $ErrorsFound
    output  = $OutputText
    elapsed_ms = $ElapsedMs
}
