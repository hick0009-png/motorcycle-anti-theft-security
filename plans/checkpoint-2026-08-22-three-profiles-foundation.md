# Three Protection Profiles Foundation Checkpoint — 2026-08-23

## Resume point

- Worktree: `D:\security\.worktrees\continuity-recovery-tdd`
- Branch: `codex/continuity-recovery-tdd`
- Foundation implementation commits:
  - Task 1 domain: `0adff63`
  - Task 2 persistence: `1bbf298`
  - Task 3 armed snapshots: `0655d22`
  - Task 4 Arm freeze: `f8ed4f7`
  - Task 5 crash-safe switching: `c11699a`
  - Task 6 graph/recovery wiring: `a7fc2aa`
  - Owner theme change (white/black): `602b00b`
  - Task 7 profile picker: `27c7168`
  - Task 8 change-use control (device-acceptance gap fix): `a51c907`
- Next work: Entry Guard plan (two-cycle hinge commissioning), then Power Guard plan,
  then recovery/readiness + paper-light completion plan.

## Completed in this verification slice (Task 8)

### Host gates (all sequential, one Gradle process at a time)
- Focused profile gate: `testDebugUnitTest --tests '*ProtectionProfile*' --tests '*ArmedProfileSnapshot*' --tests '*ProfileSwitchPolicyTest' --tests '*ProtectionCoordinatorTest' --tests '*ProtectionSnapshotStoreTest' --tests '*DirectBootBootstrapPolicyTest'` → 108 tests, failures=0, errors=0.
- Memory-pressure check before the full gate: no java/kotlin processes, single adb server; FreeRAM ≈ 1.2 GB.
- Full host suite: `testDebugUnitTest` → 700 tests across 100 classes, failures=0, errors=0, skipped=0.
- APK build: `assembleDebug` → BUILD SUCCESSFUL.

### Change-use control gap found and fixed during device acceptance
- Device acceptance exposed a real UI gap: once a profile was selected the picker
  disappeared (`showPicker = selected == null`) and no "Change use" control existed,
  so an owner could never switch profiles again and the armed confirmation flow was
  unreachable through the UI. This blocked both the armed-change acceptance and the
  Direct Boot regression (Entry stayed selected with SETUP_REQUIRED blocking Arm).
- Fix committed as `a51c907`: `ProtectionScreen.kt` now shows a current-use chip plus
  a "เปลี่ยนการใช้งาน (Change use)" button that expands the three-card picker
  (current selection labeled "ปัจจุบัน"). Disarmed selection applies immediately;
  armed selection stages `pendingSwitchTarget` and renders the existing confirmation
  card. Rebuilt and re-verified: full suite 700/700 again, new APK installed.

### Installed APK evidence (final build)
- Path: `app\build\outputs\apk\debug\app-debug.apk`, size 68,644,327 bytes,
  LastWrite 2026-08-23 10:05:49 (+07:00).
- SHA-256: `A5A054F385DE20E9DD3F4BBE15FBEE8F88E30AE92CCB887A1631703084908448`.
- Device package: `versionCode=1 versionName=1.0`,
  `lastUpdateTime=2026-08-23 09:41:56` (first install; replaced in place by `-r`
  install of the final build without changing lastUpdateTime semantics on EMUI).

### Huawei INE-LX2 device acceptance (owner-approved live testing)
Evidence screenshots saved under `plans/task8-*.png` (untracked by design):
1. Picker: three cards under "คุณกำลังปกป้องอะไร?"; Vehicle Guard ready,
   Entry/Power Guard honestly labeled "Setup required"; white background/black ink.
2. Disarmed Entry selection applied immediately ("ปลดการป้องกันสำเร็จ" snackbar).
3. Safe blocking: Arm with Entry selected → headline "ต้องตั้งค่าระบบก่อนเปิดการป้องกัน",
   Arm disabled, Protection blockers card "Selected profile setup required access is
   missing.", snackbar "คำสั่งไม่สำเร็จ". Nothing armed.
4. Change use → Vehicle Guard selected (disarmed apply).
5. Vehicle Arm → arming countdown ("Armed in 7 seconds"), Audio calibrating,
   classifier Ready; settled ARMED_DEGRADED (LOCATION/MICROPHONE not healthy —
   expected indoors), Telegram polling Running/Reachable.
6. Armed change use: tapping Entry while armed staged ONLY the confirmation card
   "ยืนยันการเปลี่ยนการใช้งาน" with "Keep current protection" /
   "Stop protection and change use"; protection stayed armed the whole time.
7. Cancel path: "Keep current protection" cleared the staged target; still armed,
   still Vehicle Guard; nothing persisted.
8. Legacy preservation: before any selection the picker showed with legacy config
   untouched; after testing, final state is Vehicle Guard selected (owner-informed).

### Direct Boot regression gate (kept separate from profile acceptance)
- Pre-reboot marker read via `run-as`: device-protected storage contains exactly
  `armed=true` and `auto_recovery_after_boot=true` — two non-secret booleans only.
- Controlled reboot issued. After boot: `sys.boot_completed=1`;
  `dumpsys activity services` shows exactly one app service record:
  `.service.SensorService` running; `DirectBootBootstrapService` no longer running
  (handoff completed). UI showed "การป้องกันทำงานแบบจำกัด" (armed recovery) still on
  Vehicle Guard — recovery used the frozen armed-profile snapshot, not the editable
  selected profile.
- Post-test Disarm returned the device to disarmed; marker updated to
  `armed=false` automatically (failsafe write path works).
- Not claimed: true locked-before-unlock window behavior (no-PIN device cannot prove
  it) and outbound Telegram message counting — carried over unchanged from
  `plans/checkpoint-2026-08-22-direct-boot-bootstrap.md`.

## Decisions

- The missing "Change use" control was treated as an incomplete Task 7 slice item
  (plan Task 7 Step 3 explicitly requires "a profile chip and Change use action")
  rather than new scope; fixed minimally inside `ProtectionScreen.kt` reusing the
  existing ViewModel actions and confirmation card.
- Screenshots are kept untracked locally as evidence; the checkpoint references them
  by name instead of committing binaries.
- Final device state intentionally left as: Vehicle Guard selected, disarmed,
  service running — matching the owner's pre-test state except for the explicit
  profile selection made during approved testing.

## Pending next plans (in order)

1. **Entry Guard plan**: two-cycle hinge commissioning, quaternion/axis validation,
   door episodes, Entry-specific delivery, device acceptance; flips ENTRY setupState
   to READY.
2. **Power Guard plan**: charging/witness commissioning, composite-state arbiter,
   durable episode/outbox, ambiguity policy, device acceptance; flips POWER to READY.
3. **Recovery/readiness + paper-light completion plan**: profile-specific recovery
   truth, delivery-path readiness, remaining paper-light surfaces, TalkBack/large-font
   and device matrix, full regression gates.

## Risks / open items

- Compose androidTests (`ProtectionProfilesUiTest`) compile but have never executed on
  a device; TalkBack and large-font passes are still outstanding (next plan).
- Settings screen still lists legacy sensor settings ordering; profile-scoped settings
  grouping remains open (deferred by design in Task 7).
- Armed change-use confirm path was exercised only through cancel ("Keep current
  protection"); the destructive "Stop protection and change use" branch is covered by
  host tests (`ProfileSwitchPolicyTest`, `ProtectionCoordinatorTest`) but was not
  clicked on the live device to avoid an extra real disarm/rearm cycle.
- No-PIN device cannot prove the true locked-window bootstrap path (unchanged risk
  from the Direct Boot checkpoint).
- EncryptedSharedPreferences means profile state cannot be inspected or repaired via
  `run-as`; any future manual repair needs an in-app path.

## Exact resume commands

```powershell
Set-Location D:\security\.worktrees\continuity-recovery-tdd
git status --short
git log -10 --oneline
Get-Content -Raw .\plans\checkpoint-2026-08-22-three-profiles-foundation.md

Set-Location .\MotorcycleAntiTheftSensor
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionProfile*' --tests '*ArmedProfileSnapshot*' --tests '*ProfileSwitchPolicyTest' --tests '*ProtectionCoordinatorTest' --tests '*ProtectionSnapshotStoreTest' --tests '*DirectBootBootstrapPolicyTest'
```

Then start the Entry Guard plan (new spec + plan documents first, per the staged
delivery map in `docs/superpowers/plans/2026-08-22-three-protection-profiles-foundation.md`).
