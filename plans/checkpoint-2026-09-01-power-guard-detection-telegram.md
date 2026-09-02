# Checkpoint — Power Guard detection & Telegram (2026-09-01)

## Resume point

- **Worktree:** `D:\security` (main worktree, not `.worktrees\`)
- **Branch:** `feature/motorcycle-guard-protection`
- **HEAD:** `5bbb06f` — `fix(power): apply the frozen configuration and stop counting unused sensors` (2026-09-01 08:32:19 +07)
- **Working tree:** clean except pre-existing untracked files (listed below). Nothing staged, nothing in progress.
- **Nothing pushed.** All four commits are local only.
- **Device:** Huawei INE-LX2 `JUCDU18811013149` — running the build from HEAD (installed 2026-09-01 08:13:36), armed on Power Guard, state `ARMED_HEALTHY`.

### Untracked files — DO NOT delete or modify
```
.hermes/
MotorcycleAntiTheftSensor/docs/user-guide-power-guard.md
moto-guard-advanced.xml
moto-guard-details.xml
moto-guard-window.xml
plans/checkpoint-2026-08-30-power-guard-restart-recovery.md
work/tasks/power-guard-detection-telegram-handoff.md
work/tasks/power-guard-witness-recovery-fix.md
```

---

## What this session did

Started from the owner's report: **"โหมดไฟเลี้ยง — การตรวจจับเซนเซอร์และข้อความ Telegram ไม่ถูกต้อง"**.

### Commits (oldest first)

| Commit | What |
|---|---|
| `22926dc` | Merge `codex/continuity-recovery-tdd` into `feature/motorcycle-guard-protection` (71 conflict hunks, 14 files) |
| `f2bb01e` | Remove duplicate LazyColumn keys the merge reinstated (crashed on launch) |
| `72ce156` | Fix A+B+C: profile-pinned OFF sources, power incident ownership, close copy |
| `5bbb06f` | Fix D1+D2: frozen configuration actually applied; unused sensors no longer degrade |

### Defects found and fixed

| # | Defect | Fixed in |
|---|---|---|
| 1 | Loss/recovery timers could never elapse — arbiter only advanced on sensor events, but `TYPE_LIGHT` is on-change | `22926dc` (merge brought `powerConfirmationRunnable` + `nextConfirmationAtMs`) |
| 2 | Guard-band lux discarded a conclusive charging signal and reset both timers | `22926dc` (`lastConclusiveWitnessLit`) |
| 3 | `fresh` derived from `lux != null`; a stale cached reading could decide a verdict | `22926dc` (`PowerWitnessContinuityCache` + generation) |
| 4a | Raw `charger_disconnected` opened a POWER incident immediately during an armed POWER session, bypassing the 10 s rule | `22926dc` (`powerSessionActive` gate) |
| 4b | Telegram rendered the untranslated enum `"🚨 ตรวจพบ POWER"` | `22926dc` (`PresentationTextCatalog.incidentTypeLabel`) |
| 5 | The per-arm confirmation button did not exist — `81d4845` dropped it during the UI redesign, so `PowerArmChallengeRegistry.isSatisfied()` was always false | `22926dc` (restored into the redesigned `PowerGuardSection`) |
| 6 | Witness commissioning window could never complete on an on-change light sensor | `22926dc` (`onTick`) + `powerCommissioningRepeatSample` 1 Hz re-emit added in the merge commit |
| 7 | `recoveryConfirmationMs` was 30 s, contract says 10 s | `22926dc` |
| — | Duplicate `item(key = "protection-state")` and duplicate readiness card → `IllegalArgumentException` on first measure | `f2bb01e` |
| A | Stored per-source overrides could re-enable sources the POWER preset pins OFF | `72ce156` (`lockedOffSources`) |
| B1 | `POWER_CONFIRMED_LOSS` opened a second incident and orphaned the rival one → two owner messages for one unplug | `72ce156` (`IncidentUpdate.Opened.supersededIncident`) |
| B2 | Movement evidence relabelled an open POWER incident, so `POWER_RECOVERED` found a non-POWER incident and returned `Ignored` → recovery message never sent | `72ce156` (power episode keeps its type) |
| C | `GuidanceCode.INCIDENT_CLOSED` hardcoded "ไม่มีความเคลื่อนไหวต่อเนื่อง 30 วินาที" for every incident type | `72ce156` (body follows incident type) |
| D1 | `startDetectors(armedSessionId, configuration)` default body **dropped** `configuration`; `AndroidProtectionRuntime` never overrode it. The armed snapshot's frozen configuration was computed, persisted, then discarded | `5bbb06f` |
| D1b | Microphone and location are not `SensorSource`s, so no configuration could switch them off | `5bbb06f` (`ProtectionProfilePolicy.usedSensorKinds`) |
| D2 | `unhealthySensorReasons` counted sensors the armed profile does not use → permanent "ทำงานแบบจำกัด" that also buried real degradations | `5bbb06f` |

---

## Verification evidence

### Host gate (at HEAD)
```
:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug --rerun-tasks
```
exit 0 · **113 suites, 928 tests, 0 failures, 0 errors, 0 skipped** · APK 68,601,259 bytes

### Device (`JUCDU18811013149`, build installed 08:13:36)

- `dumpsys sensorservice`: the app holds **`light-ltr578` only**. Before the fix it held ten: accelerometer, gyroscope, linear acceleration, rotation vector, game rotation vector, magnetometer, geomagnetic rotation vector, proximity, significant motion, light.
- Foreground notification: **"การป้องกันทำงานสมบูรณ์"**. Before: `"การป้องกันทำงานแบบจำกัด: LOCATION not healthy, MICROPHONE not healthy, VIBRATION not healthy"`.
- The "สาเหตุที่ระบบทำงานจำกัด" card is gone from the protection screen.
- Closed power incidents render **"ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว"**, confirming fix C end to end.
- The per-arm confirmation button "ยืนยันตำแหน่งไฟ (ปิด-เปิดไฟยืนยันแล้ว)" is present and the owner used it successfully.
- No FATAL/ANR after install.

---

## OPEN — resume here

### 1. Unanswered question to the owner (blocks the next fix)

The owner reported: **turning the witness lamp OFF sent a message; turning it back ON sent nothing.**

Device state at 08:31 showed a WARNING power incident at **08:17** already **CLOSED**.

**Question that was asked and not yet answered:**

> ตอนปิดไฟยืนยันทิ้งไว้ ได้ข้อความ **2 ข้อความ** ไหม — อันแรกแจ้ง "ไฟยืนยันไม่พบ..." แล้วอีกราว 20 วินาทีต่อมามีอันที่บอก "ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว"?

**Leading hypothesis (unproven):** the 30-second quiet watchdog closes POWER incidents.

- `ProtectionRuntimeGraph.INCIDENT_QUIET_WINDOW_MS = 30_000`; `scheduleQuietWatchdog()` runs after every processed update and `incidentCloser` closes the active incident with no regard for its type.
- Sequence that would match: lamp off → +10 s health alert sent → +30 s watchdog closes the incident and sends **"ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว" while the lamp is still off** (a false all-clear) → lamp back on → `POWER_RECOVERED` finds nothing active → `IncidentUpdate.Ignored` → silence.
- If confirmed, the fix is: a power episode may be closed only by its own recovery verdict. The quiet watchdog is a movement-domain rule ("no more vibration for 30 s") and must not apply to POWER incidents.
- **Do not implement before the owner answers** — if the answer is "no second message", the close came from another path and the diagnosis is wrong.

### 2. Proven bug, not yet fixed: the Events screen does not refresh

- Home card "เหตุการณ์ล่าสุด" showed an event at **08:17**; the Events tab's newest row was **08:09**.
- `ProtectionViewModel.refreshEvents()` runs at init and on explicit retry only; nothing re-reads the repository when a new incident is persisted.
- This also explains an earlier false lead this session: a cable unplug at 07:14 appeared to have produced no incident, when in fact the list was stale. **It cost a wrong diagnosis once — fix it before the next debugging round.**
- The owner agreed this one could proceed while waiting.

### 3. Environment risk to watch, not a code defect

- Ambient light at the last check was **1003–1026 lux**; the witness model was commissioned earlier the same morning at ~**327 lux**.
- If the witness lamp is dim relative to room light, switching it off may not move lux across the calibrated dark threshold at all.
- During the next test, watch the on-screen **"ค่าที่วัดล่าสุด X lux"** while switching the lamp. If it barely moves, the problem is physical placement / commissioning conditions, not detection logic.

### 4. Still unexplained

- Nothing outstanding from the 07:14 mystery — it was the stale Events list (item 2), not a missing incident.

---

## Owner acceptance still outstanding

These were never triggered automatically, by policy — no real Telegram traffic was generated from this session:

- Physical cable removal while armed → exactly one localized message after 10 s, and none before.
- Cable restored + lamp lit → close message within 10 s.
- USB-only unplug (lamp still lit) → `"การชาร์จโทรศัพท์หยุด ..."`, never `"ยืนยันไฟเลี้ยงขาด"`.
- `/status` cable line matches physical reality.

---

## Exact resume commands

```powershell
Set-Location 'D:\security'
git log --oneline -4          # expect 5bbb06f at HEAD
git status --porcelain        # expect only the 8 untracked entries above
```

```powershell
Set-Location 'D:\security\MotorcycleAntiTheftSensor'
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug --rerun-tasks --no-daemon --max-workers=1
& 'C:\Users\ASUS\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s JUCDU18811013149 install -r 'app\build\outputs\apk\debug\app-debug.apk'
```

Useful diagnostics that paid off this session:

```powershell
# which sensors the app actually holds — this is what exposed the movement bleed
& $adb -s JUCDU18811013149 shell dumpsys sensorservice | Select-String -Context 0,1 'motorcycleantitheftsensor'
# armed state without opening the app
& $adb -s JUCDU18811013149 shell "dumpsys notification --noredact | grep -A12 motorcycleantitheft | grep android.text"
```

The incident history file `files/protection_incidents.bin` is **encrypted** — string extraction returns nothing. Read incidents through the app UI, and remember the Events tab is stale (item 2).

---

## Working agreements honoured this session

- TDD RED before GREEN for every new behaviour, with the failing output recorded.
- No real Telegram message was ever sent.
- Nothing pushed; no untracked file deleted or modified.
- Conflict resolution favoured `codex` for the power/detection sources and `feature` for the profile-aware Thai UI, mixing only where both sides contributed.
- One androidTest was deliberately dropped: `protectionSettingsUsesNavyHeaderAndKeepsPowerSignalsReachable`, whose navy-header design was replaced by the paper-light migration in `5b7f0b4`; a comment in the file records why.
