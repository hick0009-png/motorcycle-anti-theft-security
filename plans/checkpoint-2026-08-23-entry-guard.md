# Entry Guard Final Checkpoint — 2026-08-23

## Resume point

- Worktree: `D:\security\.worktrees\continuity-recovery-tdd`
- Branch: `codex/continuity-recovery-tdd`
- Head: `181df0f` (feat: render typed entry guard delivery text)
- **Status: Tasks 1–7 complete and committed. Task 8 host gate + APK build/install done.
  Remaining: manual device acceptance (spec section 11) with the owner, then the
  Power Guard plan (slice 3 of 4).**

## Commits (Entry Guard plan)

| Commit | Content |
|---|---|
| `604d1af` | docs: approve entry guard door-angle spec and staged plan |
| `09d09ef` | feat: add entry guard quaternion twist door-angle math (Task 1) |
| `9ce977a` | feat: add entry guard two-cycle hinge commissioning policy (Task 2) |
| `185e8e5` | feat: add entry guard door episode detection policy (Task 3) |
| `b38d1bc` | feat: persist commissioned entry hinge model additively (Task 4) |
| `6f5140c` | feat: wire entry guard into coordinator and runtime (Task 5) |
| `d9a37c3` | feat: add entry guard commissioning UI and live angle control (Task 6) |
| `181df0f` | feat: render typed entry guard delivery text (Task 7) |

## Completed this session (Tasks 6 close + 7 + 8 host parts)

### Task 6 closed (`d9a37c3`)
- Root cause of the last failing test: `startEntryCommissioning` seeded
  `commissioningPolicyState = EntryCommissioningPolicy.State()` whose phase is IDLE;
  `onSample` drops every sample in IDLE, so commissioning never reached COMMISSIONED
  and nothing persisted. Fix: seed with `policy.start()` (STILL_CHECK).
- Evidence: `ProtectionViewModelTest` tests=42 failures=0.
- androidTest extended compile-only in `ProtectionProfilesUiTest`: setup section
  (compass heading, quick choices, start passes selected angle) and armed summary
  (`ประตูปิด · 0°`, `แจ้งเมื่อเกิน 15°`, controlled-rearm banner).
- `compileDebugAndroidTestKotlin` BUILD SUCCESSFUL.

### Task 7 (`181df0f`)
- `IncidentMessageFormatter` renders typed Entry copy for `IncidentType.ENTRY_DOOR`
  from the latest `entry_*` diagnostic; mount-moved outranks door events; independent
  vibration/audio evidence upgrades an open-door message to impact copy; a Closed
  update whose closeReason contains "interrupted" renders owner-stop copy (never a
  confirmed-close claim). Both Telegram and SMS paths route through it; no second
  delivery owner.
- All six spec-section-8 strings pinned by tests:
  `ประตูเปิด {N}° จากตำแหน่งปิด` / `ประตูปิดและนิ่งแล้ว` /
  `ข้อมูลมุมประตูขาดหาย กำลังรอเซนเซอร์กลับมาทำงาน` /
  `โทรศัพท์หรือขายึดถูกขยับ กรุณาตรวจสอบและปรับเทียบใหม่` / `ตรวจพบแรงกระแทกที่ประตู` /
  `หยุดการเฝ้าระวัง—หลักฐานตำแหน่งประตูขาดหาย`
- RED confirmed first: 17 tests completed, 7 failed (all new ones). Then GREEN.

## Verification evidence

All runs: one Gradle invocation at a time, `--no-daemon --max-workers=1`,
`JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`,
`ANDROID_HOME=C:\Users\ASUS\AppData\Local\Android\Sdk`.

```powershell
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*ProtectionViewModelTest*"      # 42/42 GREEN
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests "*IncidentMessageFormatterTest*" # RED 7 fail -> GREEN 17/17
.\gradlew.bat --no-daemon --max-workers=1 compileDebugAndroidTestKotlin                              # BUILD SUCCESSFUL
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest                                          # BUILD SUCCESSFUL
# report index: tests=749 failures=0 ignored=0  (gate >=700 met)
.\gradlew.bat --no-daemon --max-workers=1 assembleDebug                                              # BUILD SUCCESSFUL in 33s
```

APK:

- Path: `app\build\outputs\apk\debug\app-debug.apk`
- Size: 68,675,267 bytes
- SHA-256: `8A35EACAC09D7F878A1C2AB9E062EBB676559B897FBB15B45BC37CC2670B9DED`
- Installed on Huawei INE-LX2 (serial `JUCDU18811013149`) with `-r`: Success.

## Decisions

- Commissioning state must be seeded via `policy.start()`; bare `State()` is IDLE and
  silently drops samples (documented inline at the call site).
- Entry formatter keys off the latest `entry_*` diagnostic in evidence order; local
  copies of the diagnostic constants live in the formatter companion (engine keeps its
  private set); both are pinned by engine and formatter tests.
- Owner-stop-with-interrupted-evidence is detected via closeReason containing
  "interrupted"; confirmed-close reason stays `"entry door closed confirmed"`.
- Impact corroboration = any non-entry VIBRATION/MICROPHONE evidence inside the same
  ENTRY_DOOR incident (supporting sensors per parent spec).

## Remaining (before slice 2 can be called done)

1. Manual device acceptance on Huawei INE-LX2 per spec section 11 (owner-assisted):
   two-cycle commissioning end-to-end, 20 open/close cycles, slow opening below
   threshold, off-axis movement → mount-moved, source loss before/during open episode,
   5-second recovery, forced recommissioning only on mount failure, metal-frame smoke
   test, no false door-closed message. Screenshots under `plans/entrytask*-*.png`
   (untracked). Existing `plans/task8-*.png` cover earlier profile-foundation UI runs.
2. Power Guard plan (slice 3 of 4) — not yet written.
3. Recovery/readiness + paper-light completion plan (slice 4 of 4) — not yet written.

## Risks / notes

- Untracked scratch files exist in the worktree (`green-task*.log`, `red-task*.log`,
  `full-gate-task8.log`, `assemble-task8.log`, `plans/task8-*.png`,
  `plans/checkpoint-2026-08-23-entry-guard-tasks-5-6.md`). Clean up or commit the
  checkpoint docs deliberately; do not commit logs.
- RAM discipline unchanged: never run parallel Gradle invocations.
- edit_file silent-revert risk (seen twice earlier): verify edits with Select-String
  before rebuilding.

## Exact resume commands

```powershell
Set-Location D:\security\.worktrees\continuity-recovery-tdd
git status --short
git log -10 --oneline

Set-Location .\MotorcycleAntiTheftSensor
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
& 'C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest
```

Then run the spec-section-11 acceptance checklist with the owner present, capture
`plans/entrytask*-*.png`, and start the Power Guard plan (slice 3).
