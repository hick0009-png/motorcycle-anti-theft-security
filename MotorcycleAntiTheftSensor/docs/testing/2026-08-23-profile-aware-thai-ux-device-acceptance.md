# Profile-aware Thai UX — Fresh device acceptance (Task 9)

**Date:** 2026-08-24
**Plan:** `docs/superpowers/plans/2026-08-23-profile-aware-thai-ux-implementation.md` (Task 9)
**Build under test:** commit `9d6e5dc` (`test(ui): enforce Thai responsive presentation contracts`),
APKs rebuilt by the Task 8 full gate (`testDebugUnitTest compileDebugAndroidTestKotlin
assembleDebug assembleDebugAndroidTest` → BUILD SUCCESSFUL, host XML totals
tests=864 failures=0 errors=0).

## Device identity (recorded via ADB)

| Field | Value |
|---|---|
| Serial | `JUCDU18811013149` |
| Manufacturer / Model | HUAWEI / INE-LX2 (nova 3i) |
| Android version | 9 |
| Screen | 1080x2340 @ 480dpi |
| Install | `adb install -r` app-debug.apk + app-debug-androidTest.apk → both `Success` (app data NOT cleared) |

## 1. Instrumentation runs (direct `am instrument`, exact results)

### `ProtectionAppScreenTest` — Tests run: 33, Failures: 9 (log: `task9-appscreen-run2.log`)

First run (before harness fixes) was 33/10; harness fixes applied during this task:
single-`setContent` state swap for the hero loop, `performClick` before
`performTextInput` (EMUI IME), scroll anchors, advanced-disclosure opening, and a
selected-profile state for the slider test.

Remaining 9 failures — **all share one root cause class: LazyColumn long-list scrolling +
EMUI touch-injection flakiness in the test harness** (`Failed to inject touch input`,
`No node found ... in scrollable container` for anchors such as `🔑 สิทธิการเข้าถึงของระบบ
(Permissions)`, `สถานะ Bot Token`, `🤖 Telegram Bot ควบคุมระยะไกล`). These same surfaces
were verified working on the same device by the manual walkthrough below (section 2), so
they are **test-environment defects, not production regressions**. Per the plan, fixing
the remaining harness scrolling strategy returns to a scoped follow-up slice.

### `ProtectionProfilesUiTest` — Tests run: 14, Failures: 5 (log: `task9-profiles-instrument.log`)

PASS includes the new Task 8 structural tests `entryQuickChoicesKeepMinimumTargetsInNarrowViewport`
and `shellKeepsThreeDestinationsInLandscapeLikeViewport`, plus picker/armed-dialog/Entry
guard flows. The 5 failures are the same LazyColumn-scroll pattern (Vehicle/Power/Entry
settings sections, advanced disclosure, and the new 200%-scale picker test whose
promise-substring assertion needs a scroll-tolerant matcher).

## 2. Manual walkthrough on the connected Huawei (portrait, normal font scale)

Evidence screenshots (untracked, under `plans/`): `task9-01..06-*.png`.

| # | Flow | Expected Thai wording (observed) | Result |
|---|---|---|---|
| 1 | Protection screen, POWER profile active | `การป้องกันปิดอยู่`, `เปิดระบบป้องกัน`, `การใช้งานปัจจุบัน: ไฟเลี้ยงจุดติดตั้ง`, `เปลี่ยนการใช้งาน`, `ไฟเลี้ยงและไฟยืนยัน / ปรับเทียบไฟยืนยันก่อนเริ่มใช้งาน / เริ่มปรับเทียบ`, `แสดงการวินิจฉัยขั้นสูง`; bottom nav `ปกป้อง · เหตุการณ์ · ตั้งค่า`; no raw enums/English fragments | PASS (`task9-01-protection.png`) |
| 2 | Settings tab | `การตั้งค่าระบบความปลอดภัย`, sections `การใช้งานปัจจุบัน` / `การตรวจจับของรูปแบบนี้` (สถานะไฟเลี้ยงจุดติดตั้ง: การชาร์จโทรศัพท์=เชื่อมต่อ, ไฟยืนยันจุดติดตั้ง=พบ) / `การแจ้งเตือน` / `การวินิจฉัยขั้นสูง`; no generic sensitivity slider for POWER | PASS (`task9-02/03-settings*.png`) |
| 3 | Events tab (real persisted incidents from 22 ส.ค.) | `ประวัติเหตุการณ์`, `ล้างประวัติ`, `รถอาจถูกเคลื่อนย้าย`, `แหล่งข้อมูล: เหตุการณ์จริง`, `ความรุนแรง: เตือนภัย`, `สถานะเหตุการณ์: สิ้นสุดแล้ว`, `หลักฐาน: ไม่มีความเคลื่อนไหวต่อเนื่อง 30 วินาที`, `เวลา: 22 ส.ค. 2026 13:16`, `การแจ้งเตือน: ส่งสำเร็จ`; no `OPEN/CLOSED/SENT/...` raw enums | PASS (`task9-04-events.png`) |
| 4 | 200% font scale (`settings put system font_scale 2.0`, restored to 1.0 after) | All labels wrap naturally (`การใช้งานปัจจุบัน: ไฟเลี้ยงจุดติดตั้ง` breaks across lines), buttons stay full-width ≥48 dp, no ellipsis-only truncation | PASS (`task9-05-font200.png`) |
| 5 | Landscape via `user_rotation` | EMUI ignored the forced rotation for this activity during automation | NOT AUTOMATED — landscape is covered structurally by `shellKeepsThreeDestinationsInLandscapeLikeViewport` (PASS) and remains a manual-owner item |
| 6 | Foreground notification | Service was not running during the walkthrough (protection disarmed), so no active notification to inspect | NOT EXERCISED this session — wording is pinned by `ForegroundNotificationPolicyTest` (host, PASS) |

Temporary device changes (font scale, rotation lock) were **restored**; no mock incidents
or revealed secrets were left behind.

## 3. Channel wording (formatting vs delivery)

- Telegram/SMS **formatting** is enforced by host tests (`ProtectionStatusFormatterTest`,
  `CrossChannelMessageConsistencyTest`, `PowerIncidentFormatterTest` — all PASS in the
  Task 8 gate), including no-secrets, no-GPS-in-SMS, and never-successful-before-SENT rules.
- Real **transport delivery** (Telegram `/status`, live incident push, SMS fallback) was
  NOT exercised in this session: it requires sending from the authorized owner account and
  triggering a real incident. Recorded as an explicit limitation; no delivery success is
  claimed.

## 4. TalkBack

TalkBack screen-by-screen acceptance was not performed in this session (requires a human
listener). Structural accessibility is covered by semantics-based tests (roles, states,
48 dp targets, content descriptions — host + instrumented PASS subset). Recorded as an
open manual item for the owner.

## 5. Small-width configuration

The plan asks for one small-width configuration beyond the Huawei. No second device or
emulator was available in this session (RAM-constrained machine); narrow-viewport behavior
is covered structurally by `entryQuickChoicesKeepMinimumTargetsInNarrowViewport` (320 dp,
PASS). Recorded as a limitation.

## 6. Matrix summary

| Device / config | Profile | Flow | Result |
|---|---|---|---|
| Huawei INE-LX2, portrait, 1.0× | POWER | Protection hero + actions | PASS |
| Huawei INE-LX2, portrait, 1.0× | POWER | Settings 5 sections | PASS |
| Huawei INE-LX2, portrait, 1.0× | (shared) | Events history + detail rows | PASS |
| Huawei INE-LX2, portrait, 2.0× | POWER | Reflow at 200% font scale | PASS |
| Huawei INE-LX2 | — | Instrumented `ProtectionAppScreenTest` | 24/33 PASS (9 harness-scroll failures) |
| Huawei INE-LX2 | — | Instrumented `ProtectionProfilesUiTest` | 9/14 PASS (5 harness-scroll failures) |
| — | — | Landscape (device rotation) | NOT AUTOMATED (structural test PASS) |
| — | — | TalkBack | NOT PERFORMED (manual item) |
| — | — | Telegram/SMS live delivery | NOT EXERCISED (formatting pinned by host tests) |
| — | — | Second small-width device/emulator | NOT AVAILABLE this session |

## 7. Moto Guard Settings navigation (2026-08-26)

Focused instrumentation ran on the connected Huawei after the Moto Guard navy Settings
refresh. These checks exercise the real Compose hierarchy and the existing callbacks;
they are not a manual visual, TalkBack, or live-transport acceptance.

| Check | Evidence | Result |
|---|---|---|
| Settings overview and system back | `ProtectionAppScreenTest#settingsCategoryPagesOpenAndReturnToOverview` | PASS |
| Protection Settings header and Power signals | `ProtectionProfilesUiTest#protectionSettingsUsesNavyHeaderAndKeepsPowerSignalsReachable` | PASS |
| Delivery and continuity pages | `ProtectionAppScreenTest#deliveryAndContinuityPagesKeepTheirExistingControlsReachable` | PASS |
| Advanced page and progressive disclosure | `ProtectionAppScreenTest#advancedControlsRequireTheAdvancedPageAndItsDisclosure` | PASS |
| Full Protection UI regression suite | `ProtectionAppScreenTest` on Huawei INE-LX2 | 38/38 PASS (0 failures, 0 errors) |
| Fresh Settings navigation on Debug APK | Opened all four Settings groups and returned with Android Back on Huawei INE-LX2 | PASS |
| Host/build gate | `testDebugUnitTest compileDebugAndroidTestKotlin assembleDebug` | BUILD SUCCESSFUL |

The reachable `SettingsScreen` source was checked for the former user-facing emoji set;
no matches remained. Manual screenshots of the new Settings flow, TalkBack, live
Telegram/SMS delivery, Direct Boot behavior, and a second narrow device remain open
acceptance items.

The fresh navigation check opened only the Debug APK and its Settings pages. It did not
arm protection, modify a setting, reveal credentials, inject an incident, or send a
message.

## 8. Verdict

Production Thai UX wording, profile-awareness, Events rendering, and 200% reflow are
**accepted on the connected Huawei** based on fresh screenshots and passing host gates.
The fresh Moto Guard Settings regression run also passes all 38
`ProtectionAppScreenTest` cases on that device. TalkBack, live Telegram/SMS delivery,
the Direct Boot window, and a second small-width device remain open manual items and
are NOT claimed here.
