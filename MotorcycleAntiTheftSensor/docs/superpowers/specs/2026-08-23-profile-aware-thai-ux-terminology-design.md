# Profile-aware Thai UX and terminology design

**Status:** Draft for owner review
**Date:** 2026-08-23
**Scope:** User-facing language, information hierarchy, units, accessibility text, and adaptive presentation across the production Android app
**Parent specifications:**

- `docs/superpowers/specs/2026-08-22-mobile-only-three-protection-profiles-design.md`
- `docs/superpowers/specs/2026-08-23-entry-guard-door-angle-design.md`
- `docs/superpowers/specs/2026-08-20-configurable-sensor-fusion-design.md`

This specification refines presentation only. It does not replace the protection,
profile, calibration, authorization, incident, recovery, or delivery contracts in
the parent specifications.

## 1. Goal

Make the whole app understandable in Thai without assuming that the protected place
is a motorcycle. The same phone can protect:

- a car or motorcycle;
- a door or entrance in a home, room, apartment, shop, kiosk, office, or warehouse;
- the configured charging outlet or power strip at an installation point.

The normal interface explains the outcome the owner controls. Android sensor names,
raw enum values, diagnostic codes, and engineering units appear only when they add
meaning and belong in an explicitly advanced diagnostic surface.

## 2. Product model shown to the owner

The first choice is a use profile, not a sensor preset. Ask:

> ต้องการเฝ้าระวังแบบใด?

Show three cards:

| Internal profile | Thai name | Short promise |
|---|---|---|
| `VEHICLE` | ยานพาหนะ | แจ้งเตือนเมื่อรถยนต์หรือรถจักรยานยนต์ถูกกระทบ ขยับ หรือเคลื่อนย้าย |
| `ENTRY` | ประตูและทางเข้า | แจ้งเตือนเมื่อประตูที่ติดตั้งโทรศัพท์ไว้เปิดเกินมุมที่กำหนด หรือเกิดแรงกระแทก |
| `POWER` | ไฟเลี้ยงจุดติดตั้ง | เฝ้าระวังสายชาร์จและไฟยืนยันของปลั๊กหรือรางไฟที่ตั้งค่าไว้ |

The app never infers the customer's business or installation type. The owner selects
the profile. Profile names are protection contexts, not extra bottom-navigation tabs.

Keep the approved bottom destinations:

> ปกป้อง · เหตุการณ์ · ตั้งค่า

## 3. Presentation layers

Every user-facing value belongs to exactly one presentation layer.

### 3.1 Normal owner language

Use an outcome, object, condition, and recovery action. Examples:

- `ประตูเปิด 18° จากตำแหน่งปิด`
- `ตรวจพบการสั่นหรือการขยับของยานพาหนะ`
- `การชาร์จโทรศัพท์หยุด แต่ยังพบไฟยืนยัน`
- `ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว`

Normal language must not show raw enums, source identifiers, diagnostic strings,
threshold formulas, or unexplained units.

### 3.2 Readable supporting evidence

Show supporting evidence only after the primary outcome, using context-neutral copy:

- `ตรวจพบการสั่นหรือการขยับ`
- `มุมเปลี่ยนจากตำแหน่งเริ่มต้น`
- `แสงบริเวณจุดติดตั้งเปลี่ยน`
- `สิ่งที่บังเซ็นเซอร์ถูกนำออก`
- `เสียงผิดปกติบริเวณจุดติดตั้ง`
- `โทรศัพท์หรือขายึดถูกขยับ`

Supporting evidence never claims by itself that theft, forced entry, a door opening,
or a site-wide power outage occurred.

### 3.3 Advanced diagnostics

Only the advanced diagnostics surface may show technical source names and units such
as accelerometer, gyroscope, rotation vector, `m/s²`, `rad/s`, `µT`, `lux`, or `dBFS`.
Each value must include:

1. a Thai name;
2. the formatted value and correct unit;
3. a one-sentence interpretation;
4. whether it is primary or supporting evidence;
5. freshness/availability when relevant.

Internal roles are displayed as:

| Internal role | Thai label |
|---|---|
| `PRIMARY` | ใช้ยืนยันหลัก |
| `SUPPORTING` | ใช้ประกอบการยืนยัน |
| `OFF` | ไม่ใช้ |

## 4. Global terminology boundaries

Shared catalogs, navigation, permission text, generic notifications, and generic
events must not contain the words `รถ`, `ใต้เบาะ`, `รอบตัวรถ`, or
`สตาร์ทเครื่องยนต์`. Those words are allowed only in the active Vehicle context.

Do not use a single global label for every sensor capability. In particular:

- `MOVEMENT` is not always vehicle movement;
- `ROTATION` is not always a customer-adjustable sensitivity;
- `MAGNETIC` is supporting evidence and not a plain-language protection goal;
- generic ambient-light detection is different from Power Guard witness-light logic;
- proximity sensors often provide only a near/far state and must not imply a reliable
  distance in centimetres.

The app must not say `ไฟดับทั้งอาคาร`. Power Guard can confirm loss only for the
configured shared charging outlet or power strip.

## 5. Profile-specific controls

### 5.1 Vehicle

Normal settings show the intended outcome, not individual Android sensors.

Primary adjustable control:

> การขยับที่ต้องการให้แจ้งเตือน

If the existing 1–10 abstraction remains, label its endpoints:

> ต้องขยับมากจึงตรวจพบ ↔ ขยับเล็กน้อยก็ตรวจพบ

Do not present 1–10 as a physical unit. Rotation, gyro, light, audio, and magnetic
change are supporting evidence under the recommended profile and belong in an
expandable explanation or advanced diagnostics.

GPS and Live Map copy appears only in Vehicle. It starts only after movement is
confirmed under the parent policy.

### 5.2 Entry

The normal control is an exact door-angle outcome:

> แจ้งเมื่อประตูเปิดเกิน 15° จากตำแหน่งปิด

- Default: `15°`.
- Quick choices: `5°`, `15°`, `30°`.
- Exact range: `5°–90°`.
- Explain that the angle is measured from the calibrated closed position.
- Do not call it rotation sensitivity, compass direction, or magnetic strength.
- Confirmation time belongs in Advanced and is shown in seconds, not raw
  milliseconds.

Magnetic-field change is supporting evidence only. It must never be the main Entry
control and `µT` must not appear on the normal Entry screen. Vibration, light,
proximity, and audio may support an event but must not be described as proof that the
door opened.

### 5.3 Power

Power has no generic sensitivity slider. Always show two independent rows:

- `การชาร์จโทรศัพท์`: เชื่อมต่อ / หยุด / ไม่ทราบ
- `ไฟยืนยันจุดติดตั้ง`: พบ / ไม่พบ / ใช้งานไม่ได้

The short explanation is:

> ระบบยืนยันไฟเลี้ยงขาดเมื่อทั้งการชาร์จโทรศัพท์และไฟยืนยันหายต่อเนื่องตามเวลาที่กำหนด

Generic ambient-light sensitivity must not configure the witness-light detector.
Power uses its commissioned lit/dark ranges and profile-specific decision policy.

## 6. Values and units

### 6.1 The 1–10 abstraction

`1–10` is a relative detection level, not a unit. It may remain only where it changes
a real bounded detector parameter. Every such control must explain both endpoints and
the practical result. Do not repeat `ระดับความไว x/10` under every capability.

Do not show a 1–10 control for a source that has no adjustable threshold, including a
hardware significant-motion trigger. Do not invent a sensitivity control for Power,
audio diagnostics, or a binary proximity source.

### 6.2 Formatting rules

| Quantity | Normal presentation | Advanced presentation |
|---|---|---|
| Door angle | `15°` with meaning from closed position | degrees plus source/quality details |
| Temperature | `35 °C` | same unit with sensor timestamp |
| Duration | `1.5 วินาที`, `750 มิลลิวินาที` only when precision is necessary | exact bounded value |
| Light | semantic lit/dark/change description | `lux` or calibrated ratio with interpretation |
| Acceleration | plain vibration/movement evidence | `m/s²` with interpretation |
| Angular rate | plain rapid-rotation evidence | `rad/s` with interpretation |
| Magnetic change | hidden or supporting description | `µT`, supporting role, interference warning |
| Audio level | plain sound-analysis status | `dBFS` with explanation that more-negative values are quieter |

Use Thai locale-aware dates and numbers. Raw enum names, `N/A`, `Ready`, `Running`,
and concatenated forms such as `25ms` must not reach user-facing output.

## 7. Screen and channel coverage

The same terminology and formatting rules apply to:

- profile picker and profile-switch confirmation;
- Protection screen and persistent status banner;
- Events list, detail, empty, loading, error, and clear-history confirmation;
- Settings, setup checks, permissions, continuity, and diagnostics;
- Snackbar and dialogs;
- foreground-service and Direct Boot notifications;
- Telegram status, commands, incidents, delivery, and recovery messages;
- SMS fallback copy;
- TalkBack labels, roles, states, ranges, hints, and live-region announcements.

The production source of truth remains the coordinator-backed `ProtectionAppScreen`
flow. Do not revive the disconnected legacy dashboard and do not fabricate values
that are absent from the authoritative read model.

## 8. Information architecture

Settings follows this order:

1. การใช้งานปัจจุบัน
2. การตรวจจับของรูปแบบนี้
3. การแจ้งเตือน
4. ความต่อเนื่องของระบบ
5. การวินิจฉัยขั้นสูง

Normal users see recommended profile-specific controls and a readable summary.
Engineering controls use progressive disclosure. Advanced controls that are not
actually wired or do not mutate effective configuration must not be shown as working.

## 9. Centralization and architecture

Implementation must preserve existing domain ownership while centralizing
presentation:

- Android string resources own static UI, accessibility, channel, and notification
  copy.
- A pure presentation catalog/formatter owns profile-aware domain terms, state,
  severity, lifecycle, delivery, evidence, dates, values, and units.
- Compose screens consume presentation models and resources; they do not transform
  enum names with `name`, `lowercase`, or string replacement.
- Telegram, SMS, notification, and UI projections reuse the same approved semantic
  vocabulary while retaining channel-appropriate length.
- `ProtectionCoordinator` remains the sole armed-state authority. Presentation code
  cannot mutate protection state or infer health from text.

No new dependency is required.

## 10. Adaptive layout and accessibility

- Minimum touch target: `48 dp`.
- Support small phones, large phones, tablets, portrait, and landscape.
- Reflow through at least `200%` font scale; do not force long Thai labels into one
  line or three equal-width buttons.
- Prefer wrapping or vertical stacking over truncation.
- Use vector icons from one consistent family; no decorative emoji.
- Every control has a Thai accessible name, role, state, range, and useful hint.
- Dynamic status changes use the approved single live-region precedence and do not
  duplicate announcements from hero, banner, and Snackbar.
- Color is never the only state indicator.

## 11. Security and truthfulness

- Never expose Telegram token, chat ID, pairing code, command arguments, or secret
  configuration in UI, logs, accessibility semantics, screenshots, notifications,
  Events, Telegram, or SMS.
- Keep current explicit reveal and `FLAG_SECURE` behavior for the pairing code.
- Do not restore removed Authenticator/QR/TOTP or Demo Mode UI.
- Do not claim successful delivery without a recorded accepted result.
- Do not claim that one sensor proves theft, forced entry, door opening, or a wider
  site power outage.
- Do not claim support on every phone model from hardware discovery alone. Report
  unavailable, calibration, readiness, and degraded states truthfully.

## 12. Acceptance criteria

The redesign is accepted only when all of the following are true:

1. The normal UI contains no unexplained English or raw enum/diagnostic text.
2. Shared copy is installation-neutral; vehicle wording appears only in Vehicle.
3. Entry presents door angle in degrees from the calibrated closed position and does
   not expose `µT` as a primary control.
4. Power shows charging and witness-light state independently and has no generic
   sensitivity slider.
5. Every remaining 1–10 control changes a real bounded parameter and explains both
   endpoints.
6. UI, Events, notifications, Telegram, SMS, and TalkBack use the same semantic
   vocabulary.
7. Unit formatter tests cover `°C`, degrees, seconds/milliseconds, `lux`, `m/s²`,
   `rad/s`, `µT`, and `dBFS`.
8. Source-contract tests reject raw enum names, banned shared vehicle wording,
   unexplained unit output, and the old global `ระดับความไว` pattern.
9. Compose tests cover all three profiles, normal/advanced disclosure, long Thai
   labels, 200% font scale, and minimum target size.
10. Full host tests, Android-test compilation, APK build, and fresh device acceptance
    pass before claiming completion.

Device acceptance must cover at least one small-width configuration, the connected
Huawei device, portrait and landscape, TalkBack, large text, and the three real
profile flows. Build success alone is not visual, accessibility, sensor, delivery, or
cross-device acceptance.

## 13. Non-goals

- Changing sensor algorithms, profile decision thresholds, incident arbitration,
  Telegram authorization, encryption, GPS privacy, or SMS policy.
- Adding external sensor hardware, smart plugs, temperature probes, or building-wide
  power claims.
- Renaming internal enums or public APIs solely for display text.
- Reviving legacy Dashboard/MainScreen code.
- Implementing a new dark theme in this slice.

## 14. Implementation handoff boundary

After owner approval of this written specification, create a separate implementation
plan and agent handoff. That handoff must include exact production/test files, scoped
TDD slices, one-Gradle-at-a-time commands, device matrices, security boundaries,
commit boundaries, and a requirement to preserve unrelated dirty files.

The implementing agent must not begin production edits from this draft alone.
