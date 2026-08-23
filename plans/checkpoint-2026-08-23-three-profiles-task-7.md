# Three Protection Profiles Task 7 Checkpoint — 2026-08-23

## Resume point

- Worktree: `D:\security\.worktrees\continuity-recovery-tdd`
- Branch: `codex/continuity-recovery-tdd`
- Approved design/plan commit: `324cfa4`
- Task 1 implementation commit: `0adff63`
- Task 2 implementation commit: `1bbf298`
- Task 3 implementation commit: `0655d22`
- Task 4 implementation commit: `f8ed4f7`
- Task 5 implementation commit: `c11699a`
- Task 6 implementation commit: `a7fc2aa`
- Theme change (owner request): `602b00b` — paper-light WHITE background + black ink
- Task 7 implementation commit: `27c7168`
- Next task: Task 8, sequential verification and foundation checkpoint

## Completed

### Owner-requested theme change (`602b00b`)
- Owner asked for "พื้นขาว ฟอนต์ดำ" (white background, black text) instead of the
  original paper `#FCFBF8`. Applied:
  - `Theme.kt`: `OledSecurityColorScheme` is now a light scheme — white
    background/surfaces, ink `#171717`, secondary text `#4B4B4B`, outline
    `#767676`; dynamic color stays disabled; default `darkTheme = false`.
  - `ProtectionAppScreen.kt`: `ProtectionMonochromeColorScheme` switched from
    dark to light (white surfaces, black ink, primary action = black with white
    text); bottom bar inverted to white surface with black labels and a black
    selected pill with white icon.
  - Design spec token table updated: Paper background `#FCFBF8` → `#FFFFFF`,
    card noted as white with a 1 dp outline border.

### Task 7 profile picker (`27c7168`)
- `ProtectionUiModels.kt`: added `ProtectionProfileUiState`
  (selectedProfile / armedProfile / setupState / customized / showPicker /
  pendingSwitchTarget) and `profile` field on `ProtectionUiState` +
  `ProtectionUiState.from(...)`.
- `ProtectionViewModel.kt`: constructor now takes optional
  `profileRepository` (+ `profilePolicy`); profile read model refreshed from the
  repository only; new actions:
  - `selectProfile(profile)` — disarmed: persists via coordinator immediately;
    armed with an existing selection: stages ONLY `pendingSwitchTarget`,
    persists nothing, touches no runtime.
  - `confirmProfileSwitch()` — calls `coordinator.changeProfile(confirmed=true)`.
  - `cancelProfileSwitch()` — clears the staged target.
  - `restoreRecommendedProfile()` — `profilePolicy.restoreRecommended` then save.
- `Navigation.kt`: injects `graph.profileRepository` into the ViewModel and wires
  the four new actions into `ProtectionAppActions`.
- `ProtectionScreen.kt`: when `showPicker`, renders three cards
  ("Vehicle Guard" ready; "Entry Guard"/"Power Guard" labeled "Setup required");
  when a switch target is staged, renders a confirmation card with
  "Keep current protection" and "Stop protection and change use".
- `ProtectionAppActions`: four new defaulted action lambdas (existing callers
  unaffected).
- Tests:
  - Host `ProtectionViewModelTest`: three new tests — picker for unselected,
    Entry selection shows SETUP_REQUIRED and Arm lands in SETUP_REQUIRED,
    armed change stages pendingSwitchTarget without persisting any transaction.
    Shared in-memory repository fake passed to BOTH coordinator and ViewModel.
  - New androidTest `ProtectionProfilesUiTest` (compile-only this slice):
    picker semantics + confirmation semantics.

## Verification evidence

All Gradle verification used one worker and no daemon because this machine has
limited RAM.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionViewModelTest'
.\gradlew.bat --no-daemon --max-workers=1 compileDebugAndroidTestKotlin
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest
```

- RED confirmed first: 10 compile errors (missing `profileRepository` param,
  missing `profile` state, missing `selectProfile`).
- GREEN after implementation: `*ProtectionViewModelTest` → XML evidence
  tests=39 failures=0 errors=0.
- `compileDebugAndroidTestKotlin`: BUILD SUCCESSFUL (one fix: missing
  `assertCountEquals` import).
- Full host suite `testDebugUnitTest`: BUILD SUCCESSFUL, no regression.
- `git diff --check` before commits: clean. Worktree clean after `27c7168`.

## Decisions

- Armed profile change is staged in the ViewModel (`pendingSwitchTarget`) and
  NOT persisted until explicit confirmation — matches the plan's
  `armedChangeUseKeepsProtectionUntilExplicitConfirmation` semantics without
  creating a durable transaction for an unconfirmed intent.
- The picker lives inside `ProtectionScreen` as list items (not tabs); the three
  bottom destinations are unchanged per spec.
- Settings screen reordering ("current use/profile first") is deferred to the
  follow-up slice with Task 8 device acceptance; this slice ships the picker,
  truthful Setup required labels, and the confirmation flow.
- Theme change was applied on top of the approved plan at the owner's request;
  recorded here so Task 8 acceptance checks contrast against WHITE, not cream.

## Current user-visible state

- White background with black text everywhere (app theme + bottom bar).
- No profile selected → three "คุณกำลังปกป้องอะไร?" cards; Entry/Power honestly
  say "Setup required"; selecting one while disarmed applies its resolved config
  and Arm truthfully blocks into SETUP_REQUIRED.
- Armed change of use requires explicit confirmation and never auto-arms.

## Pending

1. Task 8: focused/full host gates, APK build/install, Huawei UI acceptance
   (three cards, safe blocking, armed Change-use confirmation), separate Direct
   Boot regression gate, and the foundation checkpoint document.

## Risks and boundaries

- Compose UI tests compile but have NOT run on a device yet; TalkBack/large-font
  verification belongs to Task 8.
- Settings screen still shows legacy sensor settings ordering; profile-scoped
  settings grouping remains open work after Task 8.

## Exact resume commands

```powershell
Set-Location D:\security\.worktrees\continuity-recovery-tdd
git status --short
git log -6 --oneline
Get-Content -Raw .\plans\checkpoint-2026-08-22-three-profiles-task-7.md

Set-Location .\MotorcycleAntiTheftSensor
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat --no-daemon --max-workers=1 testDebugUnitTest --tests '*ProtectionProfile*' --tests '*ArmedProfileSnapshot*' --tests '*ProfileSwitchPolicyTest' --tests '*ProtectionCoordinatorTest' --tests '*ProtectionSnapshotStoreTest' --tests '*DirectBootBootstrapPolicyTest'
```

Then resume at Task 8 in:

`MotorcycleAntiTheftSensor/docs/superpowers/plans/2026-08-22-three-protection-profiles-foundation.md`
