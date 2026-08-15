param(
    [string]$Filter = "all",
    [ValidateSet("console","markdown","csv")]
    [string]$Output = "console"
)

$ErrorActionPreference = "Stop"

$HCPDir   = "d:\security\.hcp"
$AuditLog = "$HCPDir\audit\audit.jsonl"
$CkpDir   = "$HCPDir\checkpoints"

# --- Load Audit Entries ---
$Entries = @()
if (Test-Path $AuditLog) {
    $Entries = Get-Content $AuditLog | ForEach-Object {
        if ($_.Trim() -ne "") { $_ | ConvertFrom-Json }
    }
}

# Load full checkpoint data for decisions
$CheckpointData = @{}
if (Test-Path $CkpDir) {
    Get-ChildItem -Path $CkpDir -Filter "CKP-*.json" | ForEach-Object {
        try {
            $ckp = Get-Content $_.FullName -Raw | ConvertFrom-Json
            $CheckpointData[$ckp.id] = $ckp
        } catch {}
    }
}

# --- Apply Filter ---
$Filtered = $Entries
if ($Filter -ne "all") {
    if ($Filter -match "^phase:(\w+)$") {
        $p = $Matches[1]
        $Filtered = $Entries | Where-Object { $_.phase -eq $p }
    } elseif ($Filter -match "^task:(.+)$") {
        $t = $Matches[1]
        $Filtered = $Entries | Where-Object { $_.task_id -eq $t }
    } elseif ($Filter -match "^status:(\w+)$") {
        $s = $Matches[1]
        $Filtered = $Entries | Where-Object { $_.status -eq $s }
    }
}

$Total        = $Filtered.Count
$BuildSuccess = ($Filtered | Where-Object { $_.build -eq "SUCCESS" }).Count
$BuildFailed  = ($Filtered | Where-Object { $_.build -eq "FAILED" }).Count
$DoneCount    = ($Filtered | Where-Object { $_.status -eq "DONE" }).Count
$FailedCount  = ($Filtered | Where-Object { $_.status -eq "FAILED" }).Count

switch ($Output) {

    "console" {
        Write-Host ""
        Write-Host "===========================================================" -ForegroundColor Cyan
        Write-Host "  HCP AUDIT TIMELINE  |  Filter: $Filter  |  Total: $Total" -ForegroundColor White
        Write-Host "===========================================================" -ForegroundColor Cyan
        Write-Host "  Build Success: $BuildSuccess/$Total | Failed: $BuildFailed | Done: $DoneCount | Fail: $FailedCount" -ForegroundColor Gray
        Write-Host ""

        $i = 1
        foreach ($entry in ($Filtered | Sort-Object timestamp)) {
            $ts = [DateTime]::Parse($entry.timestamp).ToString("MM-dd HH:mm")

            Write-Host "  [$i] $ts | $($entry.phase)/$($entry.task_id) | $($entry.status) | Build: $($entry.build) | $($entry.id)" -ForegroundColor Yellow
            Write-Host "        - $($entry.description)" -ForegroundColor Gray

            if ($CheckpointData.ContainsKey($entry.id)) {
                $ckp = $CheckpointData[$entry.id]
                if ($ckp.decisions) {
                    foreach ($d in $ckp.decisions) {
                        Write-Host "        Decision: $($d.question) -> $($d.decision)" -ForegroundColor Magenta
                    }
                }
            }
            $i++
        }
        Write-Host "===========================================================" -ForegroundColor Cyan
        Write-Host ""
    }

    "markdown" {
        $OutPath = "$HCPDir\audit\audit-report.md"
        $sb = New-Object System.Text.StringBuilder
        [void]$sb.AppendLine("# HCP Audit Timeline")
        [void]$sb.AppendLine("**Filter:** " + $Filter + " | **Total:** " + $Total)
        [void]$sb.AppendLine("**Build Success:** " + $BuildSuccess + " | **Failed:** " + $BuildFailed + " | **Done:** " + $DoneCount)
        [void]$sb.AppendLine("")
        [void]$sb.AppendLine("## Timeline")
        [void]$sb.AppendLine("| # | Time | Phase/Task | Status | Build | Description | ID |")
        [void]$sb.AppendLine("|:-:|:-----|:-----------|:------:|:-----:|:------------|:---|")

        $i = 1
        foreach ($entry in ($Filtered | Sort-Object timestamp)) {
            $ts = [DateTime]::Parse($entry.timestamp).ToString("MM-dd HH:mm")
            [void]$sb.AppendLine("| " + $i + " | " + $ts + " | " + $entry.phase + "/" + $entry.task_id + " | " + $entry.status + " | " + $entry.build + " | " + $entry.description + " | " + $entry.id + " |")
            $i++
        }

        $sb.ToString() | Set-Content -Path $OutPath -Encoding UTF8
        Write-Host "Audit Markdown saved: $OutPath" -ForegroundColor Green
    }

    "csv" {
        $CsvPath = "$HCPDir\audit\audit.csv"
        $Filtered | Sort-Object timestamp |
            Select-Object id, timestamp, phase, task_id, status, build, agent, description |
            Export-Csv -Path $CsvPath -NoTypeInformation -Encoding UTF8
        Write-Host "Audit CSV saved: $CsvPath" -ForegroundColor Green
    }
}
