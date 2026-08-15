param(
    [string]$From = "",
    [string]$To   = "",
    [switch]$Step
)

$ErrorActionPreference = "Stop"

$HCPDir = "d:\security\.hcp"
$CkpDir = "$HCPDir\checkpoints"

$AllCkps = Get-ChildItem -Path $CkpDir -Filter "CKP-*.json" -ErrorAction SilentlyContinue |
           Sort-Object LastWriteTime |
           ForEach-Object {
               try { Get-Content $_.FullName -Raw | ConvertFrom-Json }
               catch { $null }
           } | Where-Object { $_ -ne $null }

if ($AllCkps.Count -eq 0) {
    Write-Error "No checkpoints found in $CkpDir"
    exit 1
}

Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
Write-Host "  HCP REPLAY - $($AllCkps.Count) Checkpoints" -ForegroundColor White
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""

$Prev = $null

foreach ($Ckp in $AllCkps) {
    $Ts = [DateTime]::Parse($Ckp.timestamp).ToString("yyyy-MM-dd HH:mm:ss")

    Write-Host "[$Ts] $($Ckp.id)" -ForegroundColor Cyan
    Write-Host "  Phase: $($Ckp.phase) | Task: $($Ckp.task_id) | Status: $($Ckp.status) | Progress: $($Ckp.progress)%" -ForegroundColor Yellow
    Write-Host "  Build: $($Ckp.snapshot.build_status) | Agent: $($Ckp.agent.name)" -ForegroundColor Gray
    Write-Host "  Description: $($Ckp.description)" -ForegroundColor White

    if ($null -ne $Prev) {
        if ($Prev.status -ne $Ckp.status) {
            Write-Host "  Status change: $($Prev.status) -> $($Ckp.status)" -ForegroundColor Magenta
        }
    }

    if ($Ckp.decisions -and $Ckp.decisions.Count -gt 0) {
        Write-Host "  Decisions:" -ForegroundColor Green
        foreach ($d in $Ckp.decisions) {
            Write-Host "    $($d.question) -> $($d.decision)" -ForegroundColor Green
        }
    }

    Write-Host "------------------------------------------------" -ForegroundColor DarkGray

    if ($Step) {
        Write-Host "Press ENTER for next step, Q to quit: " -ForegroundColor DarkYellow -NoNewline
        $key = Read-Host
        if ($key -match "^[Qq]") { break }
    }

    $Prev = $Ckp
}

Write-Host ""
Write-Host "Replay complete - $($AllCkps.Count) checkpoints traversed." -ForegroundColor Green
Write-Host ""
