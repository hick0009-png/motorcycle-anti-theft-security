#!/usr/bin/env pwsh
<#
.SYNOPSIS
    HCP Git Workflow — ระบบจัดการ Git สำหรับ HCP checkpoints

.DESCRIPTION
    คำสั่ง Git ที่ใช้ร่วมกับ HCP:
    - init    : ตั้งค่า Git repo สำหรับโปรเจค
    - commit  : commit พร้อม HCP message format
    - push    : push ไปยัง remote
    - pull    : pull + stash ก่อน pull
    - branch  : สร้าง checkpoint branch
    - status  : แสดง git status รูปแบบ HCP
    - log     : แสดง git log พร้อม checkpoint links

.PARAMETER Action
    init | commit | push | pull | branch | status | log

.PARAMETER Message
    commit message (ใช้กับ commit)

.PARAMETER Phase
    Phase ปัจจุบัน (ใช้ประกอบ message)

.PARAMETER TaskId
    Task ID ปัจจุบัน

.EXAMPLE
    .\git-workflow.ps1 -Action status
    .\git-workflow.ps1 -Action commit -Message "เพิ่ม SecureKeyManager" -Phase P1 -TaskId SEC-01
    .\git-workflow.ps1 -Action push
    .\git-workflow.ps1 -Action branch -Phase P2 -TaskId SEC-06
#>
param(
    [Parameter(Mandatory=$true)]
    [ValidateSet("init","commit","push","pull","branch","status","log")]
    [string]$Action,

    [string]$Message  = "",
    [string]$Phase    = "",
    [string]$TaskId   = "",
    [string]$Remote   = "origin",
    [string]$Branch   = ""
)

$HCPDir  = "d:\security\.hcp"
$GitRoot = "d:\security"

function Invoke-Git {
    param([string[]]$Args)
    git -C $GitRoot @Args
}

switch ($Action) {

    "init" {
        Write-Host "🔧 Initializing Git repo..." -ForegroundColor Cyan
        if (-not (Test-Path "$GitRoot\.git")) {
            Invoke-Git "init"
            Write-Host "  ✅ git init done" -ForegroundColor Green
        } else {
            Write-Host "  ℹ️  Git already initialized" -ForegroundColor Yellow
        }

        # Create .gitignore
        @"
# Build outputs
MotorcycleAntiTheftSensor/.gradle/
MotorcycleAntiTheftSensor/app/build/
MotorcycleAntiTheftSensor/build/
*.apk
*.aab

# IDE
.idea/
*.iml
local.properties

# HCP - keep checkpoints and audit, ignore handoff temp
.hcp/handoff/
.hcp/recovery/*.json

# Secrets (never commit)
*.keystore
*.jks
secrets.properties
"@ | Set-Content "$GitRoot\.gitignore" -Encoding UTF8
        Write-Host "  ✅ .gitignore created" -ForegroundColor Green

        # Initial commit
        Invoke-Git "add", "."
        Invoke-Git "commit", "-m", "[HCP] Initial commit — Project setup + HCP system"
        Write-Host "  ✅ Initial commit done" -ForegroundColor Green
    }

    "status" {
        Write-Host "`n📊 HCP Git Status" -ForegroundColor Cyan
        $branch = (Invoke-Git "branch", "--show-current") -join ""
        $hash   = (Invoke-Git "rev-parse", "--short", "HEAD") -join ""
        $dirty  = (Invoke-Git "status", "--porcelain") | Where-Object { $_ -ne "" }

        Write-Host "  Branch : $branch @ $hash" -ForegroundColor Yellow
        Write-Host "  Dirty  : $(if($dirty){'Yes — uncommitted changes'}else{'No — clean'})" -ForegroundColor $(if($dirty){"Red"}else{"Green"})
        Write-Host ""
        Invoke-Git "status", "--short"
        Write-Host ""
    }

    "commit" {
        if ($Message -eq "") { Write-Error "❌ -Message is required for commit"; exit 1 }
        $Prefix = "[HCP]"
        if ($Phase -ne "" -and $TaskId -ne "") { $Prefix = "[HCP/$Phase/$TaskId]" }
        $FullMsg = "$Prefix $Message"

        Write-Host "📦 Committing: $FullMsg" -ForegroundColor Cyan
        Invoke-Git "add", "."
        $out = Invoke-Git "commit", "-m", $FullMsg
        Write-Host $out -ForegroundColor Green
        Write-Host "  ✅ Committed" -ForegroundColor Green
    }

    "push" {
        $currentBranch = (Invoke-Git "branch", "--show-current") -join ""
        Write-Host "🚀 Pushing $currentBranch → $Remote..." -ForegroundColor Cyan
        $out = Invoke-Git "push", $Remote, $currentBranch
        Write-Host $out
        Write-Host "  ✅ Pushed" -ForegroundColor Green
    }

    "pull" {
        Write-Host "⬇️  Pulling (with stash)..." -ForegroundColor Cyan
        $stash = Invoke-Git "stash"
        Write-Host "  Stash: $stash" -ForegroundColor Gray
        Invoke-Git "pull", $Remote
        Invoke-Git "stash", "pop"
        Write-Host "  ✅ Pull complete + stash restored" -ForegroundColor Green
    }

    "branch" {
        $BranchName = if ($Branch -ne "") { $Branch } else {
            $ts = Get-Date -Format "yyyyMMdd-HHmm"
            "hcp/checkpoint-$ts$(if($Phase){"-$Phase"})$(if($TaskId){"-$TaskId"})"
        }
        Write-Host "🌿 Creating branch: $BranchName" -ForegroundColor Cyan
        Invoke-Git "checkout", "-b", $BranchName
        Write-Host "  ✅ Switched to branch: $BranchName" -ForegroundColor Green
    }

    "log" {
        Write-Host "`n📜 HCP Git Log (last 20):" -ForegroundColor Cyan
        $logOut = Invoke-Git "log", "--oneline", "-20", "--color=always"
        $logOut | ForEach-Object {
            if ($_ -match "\[HCP") {
                Write-Host "  🔖 $_" -ForegroundColor Yellow
            } else {
                Write-Host "  $_" -ForegroundColor Gray
            }
        }
        Write-Host ""
    }
}
