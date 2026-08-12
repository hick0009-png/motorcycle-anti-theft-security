# Safe Workspace Hygiene and Pending-Work Reconciliation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Git status operationally clean without deleting files, while documenting the current, evidence-based state of pending work.

**Architecture:** A root Git ignore file suppresses only local generated artifacts; it does not change files already on disk. A project-local status document records the verified state and next gate for each active workstream without treating a checkpoint or build as completion proof.

**Tech Stack:** Git ignore rules, Markdown documentation, PowerShell, Git CLI.

## Global Constraints

- Do not delete, move, reset, clean, overwrite, or automatically stage any existing file.
- Do not modify production code, tests, Gradle configuration, or the existing app-level `.gitignore`.
- Do not ignore `.hcp/`, `.agents/`, docs, scripts, work, app source, tests, Gradle wrapper files, or configuration files.
- Do not run builds, device commands, dependency upgrades, or commits in this hygiene change.
- Preserve the QR security boundary: no secret in logs, screenshots, UI dumps, or accessibility semantics.

---

## File Structure

- Create: `D:\security\.gitignore` — narrow root rules for local/generated workspace artifacts.
- Create: `MotorcycleAntiTheftSensor/docs/superpowers/status/2026-08-12-pending-work-status.md` — current workstream status, evidence, blockers, and resume order.
- Create: `MotorcycleAntiTheftSensor/docs/superpowers/plans/2026-08-12-safe-workspace-hygiene.md` — this execution plan.

### Task 1: Add narrow root ignore rules

**Files:**
- Create: `.gitignore`
- Verify: root Git status and `git check-ignore -v`

**Interfaces:**
- Consumes: existing app-level `MotorcycleAntiTheftSensor/.gitignore` without modifying it.
- Produces: root-level ignore classification for only local caches, build output, and JVM diagnostic logs.

- [ ] **Step 1: Capture the pre-change classification**

Run:

```powershell
git status --short
git check-ignore -v .cursor .idea .serena .gradle-task2 .pytest_cache MotorcycleAntiTheftSensor/.gradle MotorcycleAntiTheftSensor/app/build MotorcycleAntiTheftSensor/hs_err_pid22172.log
```

Expected: `git status` lists local paths as untracked where they are not already covered; `git check-ignore` has no root-rule output yet.

- [ ] **Step 2: Create the root `.gitignore` with the exact narrow rules**

```gitignore
# Local editor and assistant state
/.cursor/
/.idea/
/.serena/
/.gradle-task2/
/.pytest_cache/

# Android/Gradle generated state and output
/MotorcycleAntiTheftSensor/.gradle/
/MotorcycleAntiTheftSensor/**/build/

# JVM diagnostic output generated locally
/MotorcycleAntiTheftSensor/hs_err_pid*.log
/MotorcycleAntiTheftSensor/replay_pid*.log
```

- [ ] **Step 3: Verify the ignore rules classify only intended generated paths**

Run:

```powershell
git check-ignore -v .cursor .idea .serena .gradle-task2 .pytest_cache MotorcycleAntiTheftSensor/.gradle MotorcycleAntiTheftSensor/app/build MotorcycleAntiTheftSensor/hs_err_pid22172.log MotorcycleAntiTheftSensor/replay_pid22172.log
git check-ignore -v .hcp .agents docs scripts work MotorcycleAntiTheftSensor/app/src MotorcycleAntiTheftSensor/gradlew.bat
```

Expected: the first command prints the new root `.gitignore` rule for every existing generated path; the second command prints nothing and exits nonzero because protected paths remain visible to Git.

- [ ] **Step 4: Verify no file was removed and unrelated tracked edits remain untouched**

Run:

```powershell
Test-Path .hcp
Test-Path MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt
git diff --name-only
git status --short
```

Expected: both `Test-Path` calls return `True`; the two existing Telegram diff paths remain present; local generated paths no longer clutter untracked status.

### Task 2: Record pending work with current evidence

**Files:**
- Create: `MotorcycleAntiTheftSensor/docs/superpowers/status/2026-08-12-pending-work-status.md`
- Verify: Markdown content and Git diff scope

**Interfaces:**
- Consumes: `.hcp/checkpoints/latest.json`, `CKP-20260811-2357-YD5EKY.json`, `git log --oneline`, tracked Telegram diff, and the existing SDD artifacts.
- Produces: a non-secret resume record with exact next actions and explicit no-completion claims.

- [ ] **Step 1: Capture current evidence without opening any secret-revealing UI or artifact**

Run:

```powershell
Get-Content -Raw .hcp/checkpoints/latest.json
git log --oneline -8
git diff --name-only
git diff -- MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClient.kt MotorcycleAntiTheftSensor/app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramBotClientSourceContractTest.kt
```

Expected: the checkpoint pointer, recent commit order, and the two local Telegram paths are captured without a build or device action.

- [ ] **Step 2: Create the status document with exactly three workstreams**

The document must contain these headings and facts:

```markdown
## Authenticator QR
- Checkpoint: `CKP-20260811-2357-YD5EKY` (`IN_PROGRESS`, 72%).
- Next gate: scoped re-review of `3ace729..3322c36`.
- After a clean review: fresh full automated gate, APK install, `SEND_SMS` deny re-verification, and manual two-phone QR scan plus six-digit TOTP verification.
- Status: not device-accepted.

## Telegram TLS Pin Refresh
- Implementation commit: `f9d7756`.
- Migration-sequence documentation commit: `c0ca4ce`.
- Next gate: inspect its SDD progress and review artifacts before declaring final verification.
- Status: do not infer completion from commit presence alone.

## Telegram Verification Refactor
- Pending tracked files: `TelegramBotClient.kt` and `TelegramBotClientSourceContractTest.kt`.
- Observed mismatch: the test expects `verifyBotTokenResult` and a compatibility overload not present in the current source diff.
- Next gate: reconcile production API and source-contract test in a dedicated reviewed change, then run its focused test.
- Status: not ready for verification.
```

- [ ] **Step 3: Verify required safety language and no secret material**

Run:

```powershell
rg -n "CKP-20260811-2357-YD5EKY|3ace729\.\.3322c36|f9d7756|c0ca4ce|verifyBotTokenResult|not device-accepted|not ready for verification" MotorcycleAntiTheftSensor/docs/superpowers/status/2026-08-12-pending-work-status.md
rg -n -i "token\s*=|secret\s*=|otpauth://" MotorcycleAntiTheftSensor/docs/superpowers/status/2026-08-12-pending-work-status.md
```

Expected: the first command finds all required status facts; the second prints nothing and exits nonzero.

- [ ] **Step 4: Verify additive-only scope**

Run:

```powershell
git diff --name-only
git status --short
```

Expected: only the new root `.gitignore` and the new status document are added by this plan; existing Telegram edits remain separate and unmodified.

## Final Verification

- [ ] **Step 1: Review the aggregate diff**

Run:

```powershell
git diff -- .gitignore MotorcycleAntiTheftSensor/docs/superpowers/status/2026-08-12-pending-work-status.md
git status --short
```

Expected: the diff contains only ignore classification and non-secret status documentation; no existing file was deleted, moved, or overwritten.

- [ ] **Step 2: Report exact evidence and remaining gates**

Report the root ignore rules added, the paths they classify, the three documented workstreams, the two untouched Telegram edits, and the fact that no build/device test was run.
