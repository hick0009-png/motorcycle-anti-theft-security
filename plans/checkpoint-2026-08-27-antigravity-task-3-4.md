# Checkpoint 2026-08-27 — Antigravity Task 3–4 complete

## Resume point

- Worktree: `D:\security\.worktrees\continuity-recovery-tdd\MotorcycleAntiTheftSensor`
- Branch: `codex/continuity-recovery-tdd`
- Current implementation HEAD after final review fix: `0c62c05 Use actionable permissions for remote readiness`
- External plan: `C:\Users\ASUS\.gemini\antigravity\brain\4372dedc-96bf-4641-982e-b8079e7b7040\implementation_plan.md`
- Scope completed here: Phase 3 Task 3A, confirmation of Task 3B, and Phase 4 automated/device verification.
- Tracked working tree was clean before adding this checkpoint. Preserve the 16 pre-existing untracked UI-audit PNGs.

## Commits

| Commit | Subject | Purpose |
|---|---|---|
| `e5215f5` | Add remote readiness checklist | Add the Settings overview readiness projection, remediation routing, stable semantics tags, and three device tests. |
| `9a2a503` | Localize remote readiness labels | Make controller-visible labels fully Thai and strengthen Telegram-action precedence coverage. |
| `04c485c` | Stabilize settings page navigation tests | Scroll the existing top-level Settings list before category clicks after the new card increased overview height. |
| `f193acd` | Document completion of Antigravity tasks 3 and 4 | Record the first complete verification/checkpoint before final review. |
| `0c62c05` | Use actionable permissions for remote readiness | Derive the permission row/action from `settings.missingPermissions` so Continuity always has a real remediation path. |

## What changed

### Task 3A — remote-control readiness

- Added `ความพร้อมการควบคุมผ่าน Telegram` immediately after the Settings overview header while retaining exactly four real category cards.
- Projected three existing facts without mutating protection state:
  - Bot token: `ตั้งค่าแล้ว` / `ยังไม่ได้ตั้งค่า`
  - Owner pairing: `จับคู่แล้ว (N เครื่อง)` / `ยังไม่ได้จับคู่`
  - Permissions: `พร้อมใช้งาน` / `ต้องตรวจสอบสิทธิ์`
- Added one state-correct primary action:
  - Missing token or owner pairing → `ตั้งค่า Telegram` → existing `DELIVERY_SECURITY` page.
  - Otherwise, a permission blocker → `ตรวจสอบสิทธิ์` → existing `CONTINUITY` page.
  - Fully ready → no primary action.
- Permission readiness uses `settings.missingPermissions`, the same source rendered by the Continuity page. Non-permission protection/runtime blockers are not mislabeled as permission problems.
- Kept all owner-facing labels Thai; no emoji/text pictograms, new destination, secret rendering, coordinator mutation, Direct Boot change, or dependency.

### Task 3B — advanced diagnostics

- No production edit was required. The current branch already keeps runtime, audio runtime, sensor health, battery, and incident/delivery diagnostics behind the single advanced disclosure.
- Fresh device tests confirmed the disclosure and truthful audio/microphone status behavior.

### Phase 4 regression repair

- The first full UI-class rerun found four Settings tests that clicked an offscreen category directly after the readiness card increased overview height.
- Updated only the test helper to use one `ui.settings.LIST.performScrollToNode(hasText(pageTitle))` before the ordinary click.
- No swipe/retry loop, index walking, direct semantics bypass, global animation mutation, or production workaround was added.

## Verification evidence

All Gradle runs used one invocation at a time with:

```text
JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
ANDROID_HOME=C:\Users\ASUS\AppData\Local\Android\Sdk
--no-daemon --max-workers=1
```

### Host/build gate

```text
:app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug
=> BUILD SUCCESSFUL in 17s
=> unit XML totals: 864 tests, 0 failures, 0 errors
```

### Huawei INE-LX2 (Android 9)

```text
Task 3A focused readiness tests                 => 3/3 passed
Task 3B focused disclosure/audio/health tests  => 3/3 passed in 49s
Combined Task 3A + Task 3B gate                => 6/6 passed in 51s
First full ProtectionAppScreenTest             => 37/41 passed; 4 offscreen-click failures
Former failures after test-helper repair       => 4/4 passed
Final full ProtectionAppScreenTest             => 41/41 passed in 1m42s
Final permission-truth-path readiness rerun    => 3/3 passed, 0 failures
Post-fix host/build gate                       => exit 0
git diff --check                               => clean
```

The earlier `No compose hierarchies found` result was traced to the Huawei being asleep with keyguard showing. After wake/keyguard dismissal, the focused test passed. No global animation setting was changed.

The Android SDK XML v4/v3 compatibility warning remains non-fatal and predates this slice.

### Fresh full rerun after APK installation (2026-08-27)

The completed branch was rebuilt and tested again on the connected Huawei after the
owner requested a fresh run:

```text
Host: testDebugUnitTest + compileDebugAndroidTestKotlin + assembleDebug
=> BUILD SUCCESSFUL in 19s; unit XML remains 864 tests, 0 failures, 0 errors

Device: connectedDebugAndroidTest (all instrumentation classes)
=> 59 tests, 54 passed, 5 failed, 0 errors, 0 skipped
```

All five failures are in `ProtectionProfilesUiTest` and reproduce its previously
recorded long-list/fixture mismatch:

- `vehicleSettingsShowFiveSectionsAndMovementControlWithoutOldSensitivityLabel`
- `powerSettingsShowIndependentChargingAndWitnessRows`
- `entryGuardSetupSectionShowsCompassQuickChoicesAndStartsCommissioning`
- `entrySettingsShowAngleOutcomeQuickChoicesAndNoMagneticControl`
- `advancedTechnicalControlsStayHiddenUntilDisclosureExpanded`

The failing methods use `settingsUiState`, whose default destination remains
`PROTECTION`, and/or direct `performScrollTo` / `assertIsDisplayed` against content
outside the current viewport. The correctly routed comparison test
`protectionSettingsUsesNavyHeaderAndKeepsPowerSignalsReachable` opens the Protection
Settings page and scrolls through `ui.settings.LIST`; it passed in the same 59-test run.
No production or test source was changed during this rerun.

After instrumentation, `app-debug.apk` was reinstalled successfully. A launch smoke
test resolved and opened `com.example.motorcycleantitheftsensor/.MainActivity`, confirmed
it as the resumed activity, observed the Moto Guard Thai hierarchy, and found no
`AndroidRuntime` fatal exception in the cleared launch log.

## Phase 4 checklist coverage

- Bottom navigation Thai labels and exactly three primary destinations: covered by the full device class.
- Events Thai labels/raw-enum removal and OPEN-event presentation: covered by the full device class plus the 864-test host suite.
- Protection runtime health behind the disclosure: covered by the focused Task 3B test and full device class.
- Persistent owner status priority: covered by the host suite; no projection logic changed in this slice.
- Settings remote readiness checklist and remediation routing: covered by three focused readiness tests and the full device class.
- Main screen/navigation regression: final `ProtectionAppScreenTest` passed 41/41.

This is automated/device-fixture evidence, not fresh TalkBack, live Telegram/SMS, Direct Boot, or second-device manual acceptance.

## Decisions and boundaries

- Continued on the active worktree because it started 26 commits ahead of the root checkout and contains the approved current Moto Guard Settings architecture.
- Adapted the stale plan's readiness concept to one non-page card plus the existing four Settings pages.
- Used explicit Thai status text instead of the stale plan's check/warning text pictograms.
- Routed remediation only to existing internal Settings pages.
- Corrected the stale plan/brief field after final review: `protection.permissionBlockers` contains non-permission blockers, so the permission row and Continuity action use `settings.missingPermissions` instead.
- Preserved the root checkout at `D:\security` untouched. Its six modified UI files are an older overlapping implementation and must not be merged or discarded without an owner-directed reconciliation decision.
- Final-fix HEAD `0c62c05` is 31 commits ahead of `feature/motorcycle-guard-protection`; committing this checkpoint update makes the branch 32 commits ahead. The root branch has no unique commits, but its uncommitted overlapping WIP still prevents a safe fast-forward in place.

## Exact resume commands

```powershell
Set-Location 'D:\security\.worktrees\continuity-recovery-tdd'
git branch --show-current
git log -4 --oneline
git status --short --untracked-files=all
git diff --check 40cf92d..HEAD

Set-Location '.\MotorcycleAntiTheftSensor'
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_HOME='C:\Users\ASUS\AppData\Local\Android\Sdk'
.\gradlew.bat :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin :app:assembleDebug --no-daemon --max-workers=1
```

Expected integration boundary: keep `codex/continuity-recovery-tdd` as the completed branch until the owner decides how to reconcile or archive the overlapping dirty root WIP.
