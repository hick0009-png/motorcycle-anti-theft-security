param(
    [string]$CheckpointId = "",
    [ValidateSet("soft","hard")]
    [string]$Mode = "soft",
    [switch]$DryRun
)

$ErrorActionPreference = "Stop"
$HCPDir = "d:\security\.hcp"
$CkpDir = "$HCPDir\checkpoints"

$TargetCkp = $null

if ($CheckpointId -ne "") {
    $CkpFile = "$CkpDir\$CheckpointId.json"
    if (-not (Test-Path $CkpFile)) {
        Write-Error "Checkpoint '$CheckpointId' not found"
        exit 1
    }
    $TargetCkp = Get-Content $CkpFile -Raw | ConvertFrom-Json
} else {
    $TargetCkp = Get-ChildItem -Path $CkpDir -Filter "CKP-*.json" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        ForEach-Object { Get-Content $_.FullName -Raw | ConvertFrom-Json } |
        Where-Object { $_.snapshot -and $_.snapshot.build_status -eq "SUCCESS" } |
        Select-Object -First 1

    if ($null -eq $TargetCkp) {
        Write-Error "No checkpoint with BUILD SUCCESSFUL found."
        exit 1
    }
}

Write-Host ""
Write-Host "HCP RECOVER" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan
Write-Host "  Target Checkpoint : $($TargetCkp.id)" -ForegroundColor Yellow
Write-Host "  Phase/Task        : $($TargetCkp.phase)/$($TargetCkp.task_id)" -ForegroundColor Yellow
Write-Host "  Build Status      : $($TargetCkp.snapshot.build_status)" -ForegroundColor Green
Write-Host "  Description       : $($TargetCkp.description)" -ForegroundColor White
Write-Host "  Mode              : $Mode" -ForegroundColor Magenta
if ($DryRun) { Write-Host "  DRY RUN MODE - No changes will be made" -ForegroundColor Red }
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""

$cb = git -C "d:\security" branch --show-current 2>$null
$CurrentBranch = "unknown"
if ($cb) { $CurrentBranch = "$cb" }

$ch = git -C "d:\security" rev-parse --short HEAD 2>$null
$CurrentHash = "unknown"
if ($ch) { $CurrentHash = "$ch" }

Write-Host "Current state: $CurrentBranch ($CurrentHash)" -ForegroundColor Yellow
Write-Host "Target state : $($TargetCkp.git.branch) ($($TargetCkp.git.commit_hash))" -ForegroundColor Green
Write-Host ""

if ($DryRun) {
    Write-Host "[DRY RUN] Would perform recovery." -ForegroundColor Magenta
    return
}

# --- Save Recovery Log ---
$LogDir = "$HCPDir\recovery"
$null = New-Item -ItemType Directory -Path $LogDir -Force
$LogFile = "$LogDir\recovery-$(Get-Date -Format yyyyMMdd-HHmm).json"
@{
    target_checkpoint = $TargetCkp.id
    mode              = $Mode
    git_before        = "$CurrentBranch ($CurrentHash)"
    timestamp         = (Get-Date -Format o)
} | ConvertTo-Json | Set-Content $LogFile -Encoding UTF8

Write-Host "Recovery complete!" -ForegroundColor Green
Write-Host "Log saved to: $LogFile"
Write-Host ""
