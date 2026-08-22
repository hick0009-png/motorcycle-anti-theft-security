# Mobile-only Three Protection Profiles Design

**Status:** Draft for product review
**Date:** 2026-08-22
**Scope:** One Android phone only. No external control box, USB data protocol, or external environmental sensor is included in this release.

## 1. Product decision

The app offers three selectable protection profiles. A profile applies a safe recommended sensor configuration and incident policy, while preserving a separate advanced configuration for customers who need finer control.

1. **Vehicle Guard** — cars and motorcycles.
2. **Entry Guard** — rooms, apartments, shops, kiosks, offices, and warehouses.
3. **Power Guard** — detect whether the phone's charging power is connected or disconnected.

The app does not infer the customer's business automatically. The customer chooses a profile; the app then inspects supported phone sensors and applies that profile's recommendation.

## 2. Shared principles

- A profile is a preset, not a locked product edition.
- Each profile keeps its own overrides. Switching profiles must never overwrite another profile's custom settings.
- Existing customer configuration stays unchanged until the customer explicitly chooses a profile or taps **Use recommended settings**.
- Missing hardware is visible as `Unavailable` or `Degraded`; it is never silently treated as healthy.
- Losing charging power while armed must not disarm the app. The app continues on battery and reports the health change.
- All remote notifications remain owner-authorized Telegram messages. SMS behavior is unchanged by this design.
- GPS and Live Map remain exclusive to Vehicle Guard.

## 3. First-run and settings UX

### 3.1 Profile picker

The first configuration screen asks **What are you protecting?** and shows three cards:

| Card | Plain-language promise | Default health requirement |
|---|---|---|
| Vehicle Guard | Detect movement and follow a confirmed relocation | Motion-capable phone; location is optional until movement is confirmed |
| Entry Guard | Detect a door opening or a disturbance at the entry | Phone must be mounted firmly and kept still during calibration |
| Power Guard | Detect charging power to this phone being disconnected | Phone should be plugged in before arming for full protection |

Selecting a card opens a short setup check, applies recommended values, then takes the customer to a test/Arm screen. The selected card is shown persistently in Settings and the protection dashboard.

### 3.2 Recommended versus customized values

Each profile settings page has two sections:

- **Recommended for this use**: a readable summary of active detectors and their intent.
- **Advanced settings**: sensor role (`Off`, `Supporting`, `Primary`), sensitivity, confirmation time, and profile-specific thresholds.

When an advanced value differs from the profile preset, show `Customized` beside it. A **Restore recommended values** action affects only the current profile and requires confirmation.

### 3.3 Profile persistence model

Store the selected profile independently from a profile's settings:

```text
selectedProfile
profilePresetVersion[profile]
profileOverrides[profile]
```

The effective configuration is `current preset + current profile overrides`. Future preset improvements may update untouched settings, but must not overwrite an explicit customer override.

## 4. Profile definitions

### 4.1 Vehicle Guard

**Customer outcome:** alert when a vehicle is disturbed or moved; start Live Map only after confirmed movement.

| Area | Recommended behavior |
|---|---|
| Calibration | Keep the existing 10-second armed calibration while the vehicle is stationary |
| Primary evidence | Acceleration / significant motion / confirmed displacement |
| Supporting evidence | Rotation, gyro, light, audio threat signal, and magnetic-field change when available |
| Location | No location publishing during arming; Live Map begins only after confirmed movement |
| Charging health | Supporting health signal: connected, disconnected, or unknown |
| Resolution | Preserve the existing incident quiet window; disarm closes immediately |

Default Telegram text remains vehicle-specific, such as `ตรวจพบรถหรืออุปกรณ์ถูกเคลื่อนย้าย`.

### 4.2 Entry Guard

**Customer outcome:** alert when a mounted phone detects that the protected door has opened beyond a chosen angle or has sustained a disturbance.

#### Customer-facing terminology

Use **เข็มทิศประตู** as the card/feature name. The actual control is precise and understandable:

> แจ้งเมื่อประตูเปิดเกิน **15°** จากตำแหน่งปิด

Do not expose magnetic-field strength in microtesla as the primary door control.

#### Detection contract

- During arming, the customer closes the door and keeps the phone still for a five-second calibration.
- The detector records a relative orientation baseline, then computes `door_angle_delta_deg` from the phone's orientation change.
- Default trigger: angle is at least **15°** for **750 ms**.
- Advanced range: **5°–90°**, confirmation time **250 ms–3 s**.
- Closing threshold uses hysteresis: below **3°** and stable for **5 s** marks the door closed and resolves the event.
- Rotation/gyro are the main angle sources. Raw magnetic-field change is supporting evidence only because metal frames and nearby magnets can distort it.
- Vibration, light, proximity, and audio may be enabled as supporting evidence, but should not be described as proof that the door is open.
- GPS and Live Map are off by default and unavailable in this profile.

The event taxonomy must add a dedicated `DOOR_OPEN`/`DOOR_CLOSED` path. Orientation or magnetic evidence must not be reported to the customer as generic vehicle vibration.

Suggested Telegram messages:

- `ประตูเปิด 18° จากตำแหน่งปิด`
- `ประตูปิดและนิ่งแล้ว`
- `ตรวจพบแรงกระแทกที่ประตู` when impact evidence is independently confirmed

#### Installation constraints

- The phone must be firmly mounted to the moving door or the protected surface; a loose phone cannot provide a trustworthy angle.
- A customer must complete one test opening before the profile is called healthy.
- The screen explains that large metal doors, magnets, or moving the phone itself can degrade compass-derived evidence.

### 4.3 Power Guard

**Customer outcome:** alert when charging power to this phone is disconnected and when it becomes stable again.

This profile is intentionally named **Power Guard**, not freezer-temperature monitoring or mains-outage confirmation. With a phone alone, the reliable claim is the state of its charging connection.

| State | Behavior |
|---|---|
| Armed while charging | Healthy; monitor plug/power connection |
| Armed while not charging | Armed Degraded; no false initial “power lost” incident |
| Charging to not charging for 10 s | Open `PHONE_POWER_LOST` incident and notify |
| Still disconnected | Edit the open incident on the normal progress cadence and send continuation notices on the normal continuation cadence |
| Charging restored for 30 s | Close the incident and send `ไฟเลี้ยงโทรศัพท์กลับมาแล้ว` |

The detector uses Android's charging/power connection state rather than battery percentage or instantaneous charge current. A full battery while still plugged in must be considered connected.

GPS, Live Map, door-angle controls, and motion alerts are off by default in this profile. Advanced users can opt into supporting motion evidence, but Power Guard's primary incident remains a charging connection transition.

## 5. Health and notification semantics

Each profile has a profile-specific definition of resolution; do not apply the vehicle's 30-second quiet rule globally.

| Profile | Open condition | Close condition |
|---|---|---|
| Vehicle Guard | Confirmed movement / sensor incident | Existing accepted-evidence quiet rule or disarm |
| Entry Guard | Door angle stays over threshold for confirmation time | Below close threshold and stable for 5 s |
| Power Guard | Charging connection absent for 10 s after a connected baseline | Charging connection present and stable for 30 s |

All profiles show charging health in the dashboard:

- `Charging connected`
- `Charging disconnected`
- `Charging state unknown`

Charging health is informational in Vehicle Guard and Entry Guard, and primary in Power Guard.

## 6. Reliability and safety boundaries

- A disconnected charger never auto-disarms the app.
- A phone-alone Entry Guard cannot prove forced entry if the phone is removed, poorly mounted, or its orientation is disturbed outside the door movement.
- A phone-alone Power Guard cannot prove that mains power, a freezer compressor, water temperature, or a fish-pond pump has failed.
- The app must state these boundaries in setup text and alert copy; it must not make broader claims.
- Unsupported sensors, missing permissions, weak calibration, or an unavailable charging state make the relevant capability `Degraded`, not silently successful.
- Existing encryption, authorization, Telegram pinning, and SMS fallback boundaries are unchanged.

## 7. Android architecture boundaries

Introduce a profile layer above the existing sensor configuration rather than scattering `if (profile)` checks across detector classes.

```text
ProtectionProfile
  -> preset configuration and calibration contract
  -> profile event policy
  -> profile notification and resolution policy
  -> effective configuration resolver (preset + overrides)
```

Required domain additions:

- `ProtectionProfile`: `VEHICLE`, `ENTRY`, `POWER`.
- Entry-specific orientation baseline and `DOOR_OPEN`/`DOOR_CLOSED` events.
- Power-specific charging transition detector and `PHONE_POWER_LOST`/`PHONE_POWER_RESTORED` events.
- Profile-aware health projection and Thai message formatter.

The protection coordinator remains the authoritative owner of armed state. Sensor sources publish observations and health; they do not send Telegram messages directly.

## 8. Verification plan

### Automated tests

- Profile resolver keeps overrides isolated and restore affects only one profile.
- Existing customers keep their configuration until choosing a profile.
- Entry angle wraps correctly across 0°/360° and applies threshold, confirmation, and close hysteresis.
- Magnetic interference cannot alone produce a `DOOR_OPEN` event under the recommended preset.
- Power Guard detects connected-to-disconnected and restored transitions with debounce.
- Disconnected charging sets degraded health but does not disarm Vehicle Guard or Entry Guard.
- Each event produces exactly one intended Telegram owner message; progress edits never alter a Live Map message.

### Device acceptance

- Vehicle: arm, calibration, real movement, map start, and clean closure.
- Entry: 20 repeated open/close cycles on a mounted phone, including a metal-frame location.
- Power: unplug/replug while armed, with Telegram delivery and recovery message observed.
- Battery, thermal state, background survival, reboot recovery, and permission-denied paths are recorded separately from host test results.

## 9. Out of scope for this release

- External control boxes, USB data transport, BLE/Wi-Fi sensors, smart plugs, temperature probes, water-level sensors, and pump control.
- Claiming verified mains outage, freezer temperature, pond water condition, or compressor/pump state from a phone alone.
- Changing current Telegram authorization, encryption, SMS policy, or GPS privacy boundaries.

## 10. Approval checklist

Before implementation, confirm:

1. Product names: `Vehicle Guard`, `Entry Guard`, and `Power Guard`.
2. Entry default: 15° for 750 ms; close at below 3° for 5 s.
3. Power default: disconnect for 10 s; restore for 30 s.
4. Entry and Power profiles have no Live Map by default.
5. The present release makes phone-charging claims only for Power Guard.
