# Checkpoint — Profile-aware Home UI Blueprint and Power Status Investigation

**Date:** 2026-08-28  
**Workspace:** `D:\security\MotorcycleAntiTheftSensor`  
**Branch:** `feature/motorcycle-guard-protection`  
**Status:** Paused at user request; do not make further code changes until the user resumes and explicitly approves implementation.

## Product model — authoritative

This is a **multi-purpose monitoring product**, not a vehicle-only application. It supports three intended modes:

1. **Vehicle Guard** — vehicle monitoring/protection.
2. **Entry Guard** — shop/warehouse door and area monitoring.
3. **Power Guard** — power-outage monitoring using supply/charging and witness-light signals.

All future UI copy, information architecture, status claims, and profile-specific controls must be installation-neutral. Do not make vehicle-specific safety claims in shared UI.

## User constraints

- User requested inspection and blueprint first.
- Do **not** modify code for the current UI redesign request until the user reviews and approves the blueprint.
- Preserve unrelated dirty work; do not reset, checkout, stash, or broadly revert the worktree without explicit direction.

## Current working tree

`git status --short` shows modified production/test files, including:

- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/UserGuidance.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/events/EventsScreen.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
- related tests

Also present are untracked documentation/artifacts and `android-mcp.log`. They are not authorized for deletion by this checkpoint.

## Power Guard charging-status investigation

### Observed defect

While protection was disarmed, Power Guard could show “Charging” continuously because power/thermal monitoring was tied to the protection detector lifecycle. After disarm, live battery broadcasts no longer updated the presentation snapshot, leaving a stale initial/cached charging state.

### Existing implementation slice in the dirty tree

The current WIP appears to:

- add independent `startPowerStatusMonitoring` / `stopPowerStatusMonitoring` lifecycle support;
- initiate power-status monitoring when the Power profile is selected;
- retain monitoring while protection is disarmed and clean it up when `SensorService` is destroyed;
- update Power summary rows reactively from coordinator snapshots;
- add a ViewModel regression test for a disarmed Power profile receiving a live charging-state change.

### Device evidence

- Device detected previously: Huawei `INE-LX2`, serial `JUCDU18811013149`.
- Debug APK was installed and verified by `adb shell pm path com.example.motorcycleantitheftsensor`.
- On the live Power Guard screen while USB was connected, the app displayed:
  - `กำลังชาร์จ`
  - `ไฟยืนยัน: ตรวจพบแสง`

This confirms the connected-state rendering path on that device. A fresh physical unplug/replug transition acceptance test is still required after the user resumes.

### Verification caveat

Focused Gradle commands were previously invoked for `ProtectionViewModelTest`, `PowerThermalMonitorTest`, and Android-test compilation, but transcript output was abbreviated and system verification marked the edited workspace unverified. Treat full fresh verification as pending; do not claim suite-green until commands produce retained, inspectable output.

## UI regression / mixed-worktree finding

The charging-state slice does **not** directly modify theme colors, padding, spacing, or screen coordinates. It can only change the Power Guard card’s state-dependent text/color and, in a disconnected/fault state, conditionally add a recovery hint that makes that card taller.

Material UI layout changes are separately present in the dirty tree:

- `ui/protection/ProtectionScreen.kt`: runtime/sensor/battery details collapsed behind `AnimatedVisibility`, changing initial vertical layout.
- `ui/ProtectionUiModels.kt`: expanded persistent-guidance conditions can insert a card before main content.
- `ui/settings/SettingsScreen.kt`: new readiness card with spacing and blue/red state treatment.
- `ui/events/EventsScreen.kt`: anti-spam caption list item.
- `ui/ProtectionAppScreen.kt`: Thai destination labels; longer localized copy can wrap differently.

On-device screenshots showed the first Protect screen with a large Power Guard card ahead of the protection-status card, a collapsed `ดูรายละเอียดระบบ` control, and raw English values (`Warning`, `Closed`, `Sent`) mixed with Thai copy.

## Design references analyzed

User provided:

- `C:\Users\ASUS\OneDrive\รูปภาพ\S__24420356_0.jpg`
- `C:\Users\ASUS\OneDrive\รูปภาพ\S__24420357_0.jpg`
- `C:\Users\ASUS\OneDrive\รูปภาพ\S__24420358_0.jpg`

They show a calm iOS/Obsidian settings aesthetic:

- `#F6F6F7`-like warm light-gray page background;
- large white cards with roughly 28dp rounded corners;
- 20dp inner card padding and 20dp page gutters;
- subtle dividers; low/no heavy shadow;
- dark charcoal typography, muted gray helper text;
- light-gray pill controls;
- purple toggles used sparingly for selected/active controls;
- compact top navigation with circular icon buttons.

## Proposed home-screen blueprint — awaiting approval

### Direction

**Calm Safety Settings:** one shared visual system across all three profiles, while status rows, primary action, and profile card adapt to the selected mode.

### Proposed hierarchy

1. Compact header: `การป้องกัน` plus settings action.
2. System-status card: state, one short explanation, one primary arm/disarm action.
3. Current-use card: selected profile and route to change profile.
4. Profile-specific status card:
   - Vehicle Guard: vehicle-relevant monitoring summary.
   - Entry Guard: door/area summary.
   - Power Guard: charger, witness light, and last update.
5. Latest-event card.
6. `รายละเอียดระบบ` route/disclosure for technical diagnostics only.
7. Consistent bottom navigation.

### Proposed Power Guard behavior

- `CHARGING`: `กำลังชาร์จ`, safe/confirmed green.
- `DISCHARGING`: `ไม่ได้เสียบสายชาร์จ`, neutral gray unless another signal confirms a fault.
- `FULL`: `ชาร์จเต็ม / ยังเสียบสายอยู่`, green.
- `NOT_CHARGING`: amber, with clear contextual explanation.
- `UNKNOWN`: neutral gray.
- Confirmed dual-signal fault only: red plus one recovery action.

Keep the normal Power card compact (target 220–250dp). Do not permanently show commissioning guidance and confirmation controls unless commissioning is required or the runtime is inconclusive.

### Copy and accessibility plan

- Fully translate raw enum values before presentation (`Warning`, `Closed`, `Sent`, etc.).
- Use installation-neutral language in shared UI.
- Use `Noto Sans Thai` or `Sarabun` for Thai readability, with 48dp minimum targets and 200% font-scale layout checks.
- Use status text and icons in addition to color.

## Next steps when user resumes

1. Confirm or revise this blueprint; no code before approval.
2. Inventory the dirty diff and separate the charging slice from unrelated UI WIP before assembling any new APK.
3. With approved design, write/review targeted Compose tests first for each selected profile and normal/fault Power states.
4. Implement the approved visual tokens and hierarchy only in the production route (`Navigation.kt -> ProtectionAppScreen`); do not revive legacy screens.
5. Run fresh retained verification output:
   ```bash
   ./gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug
   ```
6. Install the approved debug APK and perform manual device acceptance on Huawei INE-LX2:
   - Power profile, disarmed: connect/unplug charger and observe transitions;
   - each profile’s first-screen hierarchy;
   - Thai text wrapping;
   - font scale 200%;
   - no bottom navigation/content collision.

## Resume prompt

> Continue from `D:\security\plans\checkpoint-2026-08-28-profile-aware-home-ui-blueprint.md`. First show the current dirty diff grouped into (a) charging status, (b) unrelated UI WIP, and (c) documentation/artifacts. Do not edit any source until the user approves the profile-aware home UI blueprint.
