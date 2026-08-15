param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("P1","P2","P3","P4","P5","P6")]
    [string]$Phase,

    [Parameter(Mandatory=$true)]
    [ValidatePattern("^(SEC|SEN|SVC|COM|UI)-[0-9]{2}$")]
    [string]$TaskId,

    [Parameter(Mandatory=$true)]
    [ValidateSet("PLANNED","IN_PROGRESS","DONE","FAILED","SKIPPED","BLOCKED")]
    [string]$Status,

    [Parameter(Mandatory=$true)]
    [ValidateLength(5,500)]
    [string]$Description,

    [Parameter(Mandatory=$false)]
    [int]$Progress = 0,

    [Parameter(Mandatory=$false)]
    [switch]$Lite,

    [Parameter(Mandatory=$false)]
    [switch]$OnError,

    [Parameter(Mandatory=$false)]
    [string]$ErrorType = "unknown",

    [Parameter(Mandatory=$false)]
    [string]$ErrorMessage = "",

    [Parameter(Mandatory=$false)]
    [string]$ToolUsed = "",

    [Parameter(Mandatory=$false)]
    [string]$RecoveryInstruction = "",

    [Parameter(Mandatory=$false)]
    [string]$Tags = "",

    [Parameter(Mandatory=$false)]
    [string]$Decision = "",

    [Parameter(Mandatory=$false)]
    [string]$AgentName = "Antigravity",

    [Parameter(Mandatory=$false)]
    [string]$Model = "gemini-3.6-flash",

    [Parameter(Mandatory=$false)]
    [string]$ConversationId = "7f040a5e-f01f-4b15-9fe4-32b980c6aef1"
)

$ErrorActionPreference = "Stop"

# --- Paths ---
$HCPDir        = "d:\security\.hcp"
$CkpDir        = "$HCPDir\checkpoints"
$AuditLog      = "$HCPDir\audit\audit.jsonl"
$LatestCkpFile = "$CkpDir\latest.json"

# --- Generate Checkpoint ID ---
$Now       = Get-Date
$DatePart  = $Now.ToString("yyyyMMdd")
$TimePart  = $Now.ToString("HHmm")
$Chars     = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
$RandPart  = -join (1..6 | ForEach-Object { $Chars[(Get-Random -Maximum $Chars.Length)] })
$CkpId     = "CKP-$DatePart-$TimePart-$RandPart"
$Timestamp = $Now.ToString("o")

Write-Host ""
Write-Host "Creating Checkpoint: $CkpId" -ForegroundColor Cyan

# --- Git State ---
$GitInfo = @{
    commit_hash = ""
    branch      = "unknown"
    dirty       = $false
    remote_url  = ""
}
try {
    Push-Location "d:\security"
    $cHash = git rev-parse --short HEAD
    if ($cHash) { $GitInfo.commit_hash = "$cHash" }

    $bName = git branch --show-current
    if ($bName) { $GitInfo.branch = "$bName" }

    $dirtyStatus = git status --porcelain
    if ($dirtyStatus) { $GitInfo.dirty = $true }

    $rUrl = git remote get-url origin
    if ($rUrl) { $GitInfo.remote_url = "$rUrl" }
    Pop-Location
} catch {
    Pop-Location
}

# --- Build Status ---
$BuildStatus = "NOT_RUN"
$BuildSnippet = ""
if (-not $Lite) {
    Write-Host "Running Gradle build check..." -ForegroundColor Yellow
    try {
        powershell -ExecutionPolicy Bypass -Command "& d:\security\scripts\setup_env.ps1; Set-Location d:\security\MotorcycleAntiTheftSensor; .\gradlew.bat assembleDebug" | Out-Null
        if ($LASTEXITCODE -eq 0) {
            $BuildStatus = "SUCCESS"
            Write-Host "  Build: SUCCESS" -ForegroundColor Green
        } else {
            $BuildStatus = "FAILED"
            Write-Host "  Build: FAILED" -ForegroundColor Red
        }
    } catch {
        $BuildStatus = "FAILED"
        Write-Host "  Build check error" -ForegroundColor Red
    }
}

# --- Changed Files (git diff) ---
$FilesChanged = @()
try {
    $DiffFiles = git -C "d:\security" diff --name-status HEAD
    if ($DiffFiles) {
        foreach ($line in $DiffFiles) {
            if ($line -match "^([MAD])\s+(.+)$") {
                $act = $Matches[1]
                $pth = $Matches[2]
                $actionStr = "MODIFIED"
                if ($act -eq "A") { $actionStr = "CREATED" }
                elseif ($act -eq "D") { $actionStr = "DELETED" }
                $FilesChanged += @{ path=$pth; action=$actionStr }
            }
        }
    }
} catch {}

# --- Decisions ---
$Decisions = @()
if ($Decision -ne "") {
    $parts = $Decision -split "\|"
    $q = $Decision
    if ($parts.Count -gt 0 -and $parts[0]) { $q = $parts[0] }
    $d = ""
    if ($parts.Count -gt 1) { $d = $parts[1] }
    $r = ""
    if ($parts.Count -gt 2) { $r = $parts[2] }

    $Decisions += @{
        question   = $q
        decision   = $d
        rationale  = $r
        decided_by = "user"
    }
}

# --- Tags ---
$TagList = @()
if ($Tags -ne "") {
    $TagList = $Tags -split "," | ForEach-Object { $_.Trim() }
}

# --- Error Info ---
$ErrorInfo = $null
if ($OnError) {
    $ErrorInfo = @{
        error_type           = $ErrorType
        error_message        = $ErrorMessage
        error_timestamp      = $Timestamp
        actions_before_error = @()
        tool_used            = $ToolUsed
        stack_trace          = ""
        recovery_instruction = $RecoveryInstruction
    }
}

# --- Build Checkpoint Object ---
$Checkpoint = [ordered]@{
    "`$schema"           = "../schema/hcp-checkpoint.schema.json"
    id                  = $CkpId
    timestamp           = $Timestamp
    phase               = $Phase
    task_id             = $TaskId
    status              = $Status
    description         = $Description
    progress            = $Progress
    scope               = ""
    knowledge           = @()
    assumptions         = @()
    risks               = @()
    memory_snapshot     = @()
    dependencies        = @()
    error_info          = $ErrorInfo
    agent               = @{
        name            = $AgentName
        model           = $Model
        conversation_id = $ConversationId
    }
    snapshot            = @{
        files_changed        = $FilesChanged
        build_status         = $BuildStatus
        build_output_snippet = $BuildSnippet
    }
    decisions           = $Decisions
    open_tasks          = @()
    context             = @{
        summary    = $Description
        warnings   = @()
        next_steps = @()
    }
    git                 = $GitInfo
    parent_checkpoint_id = $null
    tags                = $TagList
}

# --- Save Checkpoint ---
$null = New-Item -ItemType Directory -Path $CkpDir -Force
$CkpFile = "$CkpDir\$CkpId.json"
$Checkpoint | ConvertTo-Json -Depth 10 | Set-Content -Path $CkpFile -Encoding UTF8
Write-Host "  Saved: $CkpFile" -ForegroundColor Green

# --- Update latest.json ---
$LatestInfo = @{
    latest_checkpoint_id   = $CkpId
    latest_checkpoint_path = $CkpFile
    updated_at             = $Timestamp
}
$LatestInfo | ConvertTo-Json | Set-Content -Path $LatestCkpFile -Encoding UTF8
Write-Host "  Updated latest pointer" -ForegroundColor Green

# --- Archive/Eviction ---
$Config = Get-Content "$HCPDir\hcp-config.json" | ConvertFrom-Json
$MaxCkp = 50
if ($Config.checkpoint -and $Config.checkpoint.max_checkpoints) {
    $MaxCkp = $Config.checkpoint.max_checkpoints
}
$CheckpointsList = Get-ChildItem -Path $CkpDir -Filter "CKP-*.json" | Sort-Object CreationTime
if ($CheckpointsList.Count -gt $MaxCkp) {
    $ArchiveDir = "$CkpDir\archive"
    $null = New-Item -ItemType Directory -Path $ArchiveDir -Force
    $ToArchive = $CheckpointsList[0..($CheckpointsList.Count - $MaxCkp - 1)]
    foreach ($f in $ToArchive) {
        Move-Item -Path $f.FullName -Destination $ArchiveDir -Force
        Write-Host "  Archived: $($f.Name)" -ForegroundColor DarkGray
    }
}

# --- Append to Audit Log ---
$null = New-Item -ItemType Directory -Path (Split-Path $AuditLog) -Force
$AuditEntry = @{
    id          = $CkpId
    timestamp   = $Timestamp
    phase       = $Phase
    task_id     = $TaskId
    status      = $Status
    description = $Description
    build       = $BuildStatus
    agent       = $AgentName
    git_commit  = $GitInfo.commit_hash
}
($AuditEntry | ConvertTo-Json -Compress) | Add-Content -Path $AuditLog -Encoding UTF8

# --- Git Auto-commit ---
if ($Config.git -and $Config.git.auto_commit_on_checkpoint) {
    try {
        git -C "d:\security" add "$CkpFile" "$AuditLog" "$LatestCkpFile"
        $shortDesc = $Description
        if ($Description.Length -gt 60) { $shortDesc = $Description.Substring(0, 60) }
        git -C "d:\security" commit -m "[HCP] $CkpId - $Phase/$TaskId ($Status): $shortDesc"
        Write-Host "  Git commit: $CkpId" -ForegroundColor Green
    } catch {
        Write-Host "  Git warning: commit skipped" -ForegroundColor Yellow
    }
}

$branchVal = $GitInfo.branch
$commitVal = $GitInfo.commit_hash

Write-Host ""
Write-Host "Checkpoint $CkpId saved successfully!" -ForegroundColor Green
Write-Host "Phase: $Phase | Task: $TaskId | Status: $Status | Progress: $Progress%" -ForegroundColor Cyan
Write-Host "Build: $BuildStatus | Git branch: $branchVal" -ForegroundColor Cyan
Write-Host ""

return $CkpId
