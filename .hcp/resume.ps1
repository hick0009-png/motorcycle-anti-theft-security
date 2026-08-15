param(
    [Parameter(Mandatory=$false)]
    [string]$CheckpointId = "",

    [Parameter(Mandatory=$false)]
    [ValidateSet("summary","full","handoff","json")]
    [string]$Format = "summary"
)

$ErrorActionPreference = "Stop"

$HCPDir = "d:\security\.hcp"
$CkpDir = "$HCPDir\checkpoints"
$LatestCkpFile = "$CkpDir\latest.json"

# --- Find Checkpoint File ---
$CkpFile = ""
if ($CheckpointId -ne "") {
    $CkpFile = "$CkpDir\$CheckpointId.json"
    if (-not (Test-Path $CkpFile)) {
        Write-Error "Checkpoint '$CheckpointId' not found at: $CkpFile"
        exit 1
    }
} else {
    # Check latest.json first
    if (Test-Path $LatestCkpFile) {
        try {
            $latestData = Get-Content $LatestCkpFile -Raw | ConvertFrom-Json
            if ($latestData.latest_checkpoint_path -and (Test-Path $latestData.latest_checkpoint_path)) {
                $CkpFile = $latestData.latest_checkpoint_path
            }
        } catch {}
    }

    if ($CkpFile -eq "") {
        $Latest = Get-ChildItem -Path $CkpDir -Filter "CKP-*.json" -ErrorAction SilentlyContinue |
                  Sort-Object LastWriteTime -Descending |
                  Select-Object -First 1
        if ($null -eq $Latest) {
            Write-Error "No checkpoints found in $CkpDir"
            exit 1
        }
        $CkpFile = $Latest.FullName
    }
}

$Ckp = Get-Content $CkpFile -Raw | ConvertFrom-Json
$Id  = $Ckp.id

# --- Phase Display ---
$PhaseNames = @{
    P1 = "Security Foundation"
    P2 = "Anti-Tamper & Kiosk"
    P3 = "Core Sensor Engine"
    P4 = "Background Service"
    P5 = "Communication Layer"
    P6 = "UI & Configuration"
}

$PhaseName = $Ckp.phase
if ($PhaseNames.ContainsKey($Ckp.phase)) {
    $PhaseName = $PhaseNames[$Ckp.phase]
}

switch ($Format) {

    "json" {
        Get-Content $CkpFile -Raw
        return
    }

    "summary" {
        Write-Host ""
        Write-Host "================================================" -ForegroundColor Cyan
        Write-Host "  HCP RESUME - $Id" -ForegroundColor White
        Write-Host "================================================" -ForegroundColor Cyan
        Write-Host "  Saved   : $($Ckp.timestamp)" -ForegroundColor Gray
        Write-Host "  Phase   : $($Ckp.phase) - $PhaseName" -ForegroundColor Yellow
        Write-Host "  Task    : $($Ckp.task_id)" -ForegroundColor Yellow
        Write-Host "  Status  : $($Ckp.status)" -ForegroundColor Yellow
        Write-Host "  Progress: $($Ckp.progress)%" -ForegroundColor Yellow
        Write-Host "  Build   : $($Ckp.snapshot.build_status)" -ForegroundColor Green
        Write-Host "  Git     : $($Ckp.git.branch)" -ForegroundColor Gray
        Write-Host "  Agent   : $($Ckp.agent.name) ($($Ckp.agent.model))" -ForegroundColor Gray
        Write-Host ""
        Write-Host "  Description:" -ForegroundColor White
        Write-Host "     $($Ckp.description)" -ForegroundColor Cyan
        Write-Host "================================================" -ForegroundColor Cyan
        Write-Host ""
    }

    "full" {
        Write-Host ""
        Write-Host "================================================" -ForegroundColor Cyan
        Write-Host "  HCP CHECKPOINT - $Id" -ForegroundColor White
        Write-Host "================================================" -ForegroundColor Cyan

        Write-Host ""
        Write-Host "[HEADER]" -ForegroundColor Magenta
        Write-Host "  Timestamp : $($Ckp.timestamp)"
        Write-Host "  Phase     : $($Ckp.phase) - $PhaseName"
        Write-Host "  Task      : $($Ckp.task_id)"
        Write-Host "  Status    : $($Ckp.status)"
        Write-Host "  Progress  : $($Ckp.progress)%"
        Write-Host "  Build     : $($Ckp.snapshot.build_status)"
        Write-Host "  Branch    : $($Ckp.git.branch)"
        Write-Host "  Agent     : $($Ckp.agent.name) / $($Ckp.agent.model)"

        Write-Host ""
        Write-Host "[DESCRIPTION]" -ForegroundColor Magenta
        Write-Host "  $($Ckp.description)"

        if ($Ckp.decisions -and $Ckp.decisions.Count -gt 0) {
            Write-Host ""
            Write-Host "[DECISIONS]" -ForegroundColor Magenta
            foreach ($d in $Ckp.decisions) {
                Write-Host "  Question: $($d.question)"
                Write-Host "  Decision: $($d.decision)"
                if ($d.rationale) { Write-Host "  Rationale: $($d.rationale)" -ForegroundColor Gray }
            }
        }

        if ($Ckp.snapshot -and $Ckp.snapshot.files_changed -and $Ckp.snapshot.files_changed.Count -gt 0) {
            Write-Host ""
            Write-Host "[FILES CHANGED]" -ForegroundColor Magenta
            foreach ($f in $Ckp.snapshot.files_changed) {
                Write-Host "  $($f.action): $($f.path)"
            }
        }

        Write-Host ""
        Write-Host "================================================" -ForegroundColor Cyan
        Write-Host ""
    }

    "handoff" {
        $HandoffPath = "$HCPDir\handoff\handoff-$Id.md"
        $null = New-Item -ItemType Directory -Path (Split-Path $HandoffPath) -Force

        $nowStr = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
        $branchStr = $Ckp.git.branch

        $sb = New-Object System.Text.StringBuilder
        [void]$sb.AppendLine('# HCP Session Handoff - ' + $Id)
        [void]$sb.AppendLine('**Generated:** ' + $nowStr)
        [void]$sb.AppendLine('**Previous Agent:** ' + $Ckp.agent.name + ' (' + $Ckp.agent.model + ')')
        [void]$sb.AppendLine('')
        [void]$sb.AppendLine('---')
        [void]$sb.AppendLine('')
        [void]$sb.AppendLine('## Current State')
        [void]$sb.AppendLine('| Field | Value |')
        [void]$sb.AppendLine('|:------|:------|')
        [void]$sb.AppendLine('| Checkpoint ID | `' + $Id + '` |')
        [void]$sb.AppendLine('| Phase | ' + $Ckp.phase + ' - ' + $PhaseName + ' |')
        [void]$sb.AppendLine('| Task ID | ' + $Ckp.task_id + ' |')
        [void]$sb.AppendLine('| Status | ' + $Ckp.status + ' |')
        [void]$sb.AppendLine('| Progress | ' + $Ckp.progress + '% |')
        [void]$sb.AppendLine('| Build | ' + $Ckp.snapshot.build_status + ' |')
        [void]$sb.AppendLine('| Git Branch | ' + $branchStr + ' |')
        [void]$sb.AppendLine('| Timestamp | ' + $Ckp.timestamp + ' |')
        [void]$sb.AppendLine('')
        [void]$sb.AppendLine('## What Was Done')
        [void]$sb.AppendLine($Ckp.description)
        [void]$sb.AppendLine('')
        [void]$sb.AppendLine('## Key Decisions Made')

        if ($Ckp.decisions -and $Ckp.decisions.Count -gt 0) {
            foreach ($d in $Ckp.decisions) {
                [void]$sb.AppendLine('- **' + $d.question + '** -> ' + $d.decision)
            }
        } else {
            [void]$sb.AppendLine('_No decisions recorded._')
        }

        [void]$sb.AppendLine('')
        [void]$sb.AppendLine('## Files Changed')
        if ($Ckp.snapshot -and $Ckp.snapshot.files_changed -and $Ckp.snapshot.files_changed.Count -gt 0) {
            foreach ($f in $Ckp.snapshot.files_changed) {
                [void]$sb.AppendLine('- ' + $f.action + ': `' + $f.path + '`')
            }
        } else {
            [void]$sb.AppendLine('_No files tracked._')
        }

        [void]$sb.AppendLine('')
        [void]$sb.AppendLine('## Environment')
        [void]$sb.AppendLine('- **Project Root:** `d:\security\MotorcycleAntiTheftSensor`')
        [void]$sb.AppendLine('- **Implementation Plan:** `d:\security\docs\implementation_plan.md`')
        [void]$sb.AppendLine('- **Architecture Docs:** `d:\security\docs\ARCHITECTURE.md`')
        [void]$sb.AppendLine('- **HCP Config:** `d:\security\.hcp\hcp-config.json`')
        [void]$sb.AppendLine('')
        [void]$sb.AppendLine('## How to Resume')
        [void]$sb.AppendLine('```powershell')
        [void]$sb.AppendLine('cd d:\security\.hcp')
        [void]$sb.AppendLine('.\resume.ps1 -CheckpointId "' + $Id + '"')
        [void]$sb.AppendLine('```')

        $sb.ToString() | Set-Content -Path $HandoffPath -Encoding UTF8
        Write-Host ''
        Write-Host ("Handoff document saved: " + $HandoffPath) -ForegroundColor Green
        Write-Host ''
    }
}

return $Ckp
