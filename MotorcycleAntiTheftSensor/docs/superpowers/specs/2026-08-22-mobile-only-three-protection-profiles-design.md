# Mobile-only Three Protection Profiles Design

**Status:** Draft for product review
**Date:** 2026-08-22
**Scope:** One Android phone only. No external control box, USB data protocol, or external environmental sensor is included in this release.

## 1. Product decision

The app offers three selectable protection profiles. A profile applies a safe recommended sensor configuration and incident policy, while preserving a separate advanced configuration for customers who need finer control.

1. **Vehicle Guard** — cars and motorcycles.
2. **Entry Guard** — rooms, apartments, shops, kiosks, offices, and warehouses.
3. **Power Guard** — detect an interruption in the phone's charging path and, when a configured witness lamp also goes dark, confirm loss of power for the monitored charging setup.

The app does not infer the customer's business automatically. The customer chooses a profile; the app then inspects supported phone sensors and applies that profile's recommendation.

## 2. Shared principles

- A profile is a preset, not a locked product edition.
- Each profile keeps its own overrides. Switching profiles must never overwrite another profile's custom settings.
- The owner can change the intended use of the phone at any time. A profile switch while armed is a deliberate, confirmed transition; it never silently swaps the active detection policy underneath an armed session.
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
| Power Guard | Detect a charging-path interruption and a configured power-witness lamp going dark | Phone should be charging and the witness lamp visible to the light sensor for confirmed power-loss protection |

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

### 3.4 Changing the use of the phone later

The dashboard and Settings each expose a clearly labeled **Change use** action, so the same phone can move between a vehicle, a door, and a powered location without losing the customer's work.

- While disarmed, the owner selects another profile, reviews its current recommended/customized values, completes that profile's readiness check, and can arm it immediately.
- While armed, the action remains available but opens a confirmation sheet: **Keep current protection** or **Stop protection and change use**. Confirming the latter disarms the current profile, preserves all per-profile overrides, runs the new profile's setup/calibration, and requires an explicit Arm action afterward. The app must never auto-arm a newly selected profile.
- If an incident is active, the sheet requires a second explicit confirmation. The incident is retained in history as stopped by the owner during a profile change; it must not be reported as an automatically resolved incident.
- A single persistent profile label and readable health summary identify what the phone is protecting now. Status is conveyed by text and icon as well as color; normal tap targets are at least 48 dp.

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

#### Door-angle control

The normal Entry Guard settings screen always shows **มุมเปิดประตูที่ต้องการแจ้งเตือน**; it is not hidden in Advanced settings.

- Default: **15°**. Quick choices are **5°**, **15°**, and **30°**.
- The customer can set an exact value from **5° to 90°** in one-degree increments using a slider and numeric entry.
- A short test after calibration shows the live relative angle, starting near `0°` while the door is closed. The setup only becomes healthy after one test opening reaches the selected angle and one close returns below the close threshold.
- The label always means “angle opened from the calibrated closed position,” not a compass bearing or a magnetic-field strength. This makes the value meaningful even when the door faces a different direction after installation.
- Changing this control while disarmed takes effect for the next arm. While armed, the app asks the owner to make the controlled disarm/calibrate/re-arm transition so that a changed threshold cannot create a surprise alert from an old baseline.

#### Detection contract

- During arming, the customer closes the door and keeps the phone still for a five-second calibration.
- The detector records a relative orientation baseline, then computes `door_angle_delta_deg` from the phone's orientation change.
- The angle calculation uses a normalized relative rotation (quaternion or rotation matrix) and reports the 0°–180° physical change. It must not reuse an approximate generic vector delta or raw magnetic-field strength as the door angle.
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

**Customer outcome:** distinguish a cable/charger problem from an outage at the monitored charging setup, then report a stable recovery without duplicate messages.

Power Guard uses two independent signals:

1. **Charging path** — Android reports whether external power is connected to the phone. It uses plug/power state, not battery percentage or instantaneous current, so a full phone that remains plugged in is still connected.
2. **Power-witness light** — a small, low-heat LED lamp is plugged into the same monitored outlet or power strip as the phone charger and shines directly onto the phone's ambient-light sensor.

#### Physical setup and readiness

- The witness lamp and phone charger must share the same monitored outlet/power strip. The app must not assume that nearby sockets are on the same circuit.
- The lamp may be hung in front of the phone, but it must be held in a small opaque hood/clip that directs its light to the ambient sensor and blocks daylight and room lighting. The hood must not cover the phone's cooling path or prevent secure mounting.
- At setup and every arm, the app records a stable lit-light baseline and verifies both `Charging connected` and `Witness light detected`. If either is unavailable, arming is allowed only as `Armed Degraded`; no initial outage incident is created.
- This is a dedicated calibrated light path. It must not reuse the generic light-intrusion threshold, because Power Guard looks for a sustained drop from this lamp's own baseline, not an increase in ambient room light.

#### Decision contract

| Observed state for 10 s after a healthy baseline | Customer-visible result | Incident semantics |
|---|---|---|
| Charging connected + witness light detected | Power monitoring ready | No incident |
| Charging disconnected + witness light detected | `การชาร์จโทรศัพท์หยุด` — check the cable, charger, or phone charging port | A charging-path health alert; never call this a power outage |
| Charging connected + witness light dark | `ไฟยืนยันไม่พบ` — check the lamp, its placement, and its power path | A witness-light health alert; never call this a power outage |
| Charging disconnected + witness light dark | `ยืนยันไฟเลี้ยงขาดในจุดที่เฝ้าระวัง` | Open `PHONE_POWER_LOST` only in this dual-signal state |

When both signals are healthy again for **30 s**, close the confirmed `PHONE_POWER_LOST` incident and send `ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว`. The default dual-signal loss confirmation remains **10 s**. A one-signal health alert sends one opening message and one recovery message only after its missing signal has been stable again for **30 s** while the other signal remains healthy; it has no continuation spam. An open confirmed outage retains the existing incident progress/continuation policy.

GPS, Live Map, door-angle controls, and motion alerts are off by default in this profile. Advanced users can opt into supporting motion evidence, but Power Guard's primary claim remains the dual-signal charging-setup state.

## 5. Health and notification semantics

Each profile has a profile-specific definition of resolution; do not apply the vehicle's 30-second quiet rule globally.

| Profile | Open condition | Close condition |
|---|---|---|
| Vehicle Guard | Confirmed movement / sensor incident | Existing accepted-evidence quiet rule or disarm |
| Entry Guard | Door angle stays over threshold for confirmation time | Below close threshold and stable for 5 s |
| Power Guard | Charging connection and witness light both absent for 10 s after a healthy dual-signal baseline | Charging connection and witness light both present and stable for 30 s |

All profiles show charging health in the dashboard:

- `Charging connected`
- `Charging disconnected`
- `Charging state unknown`

Charging health is informational in Vehicle Guard and Entry Guard, and primary in Power Guard.

Power Guard additionally shows the witness-light health beside it:

- `Witness light detected`
- `Witness light dark`
- `Witness light unavailable`

Neither color nor an icon alone may express these statuses; each status has clear text and a recovery instruction where action is needed.

## 6. Reliability and safety boundaries

- A disconnected charger never auto-disarms the app.
- A phone-alone Entry Guard cannot prove forced entry if the phone is removed, poorly mounted, or its orientation is disturbed outside the door movement.
- A dual-signal Power Guard can confirm loss of power only for the configured shared charging outlet/power strip. It cannot claim a building-wide mains outage, a freezer compressor failure, water temperature, or a fish-pond pump state.
- Power Guard must never send a “power outage” message when only charging or only witness light is missing. A failed lamp, displaced hood, cable fault, or charger fault has its own explicit health message.
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
- Profile-switch transition policy that preserves per-profile overrides and prevents silent policy swaps while armed.
- Entry-specific orientation baseline, configurable door-angle threshold, and `DOOR_OPEN`/`DOOR_CLOSED` events.
- Power-specific charging transition detector, calibrated power-witness light detector, and `PHONE_POWER_LOST`/`PHONE_POWER_RESTORED` events. The generic ambient-light intrusion detector remains semantically separate.
- Profile-aware health projection and Thai message formatter.

The protection coordinator remains the authoritative owner of armed state. Sensor sources publish observations and health; they do not send Telegram messages directly.

## 8. Verification plan

### Automated tests

- Profile resolver keeps overrides isolated and restore affects only one profile.
- Switching profile while disarmed preserves each profile's custom settings; switching while armed requires confirmation, does not auto-arm, and never emits a false incident-resolution message.
- Existing customers keep their configuration until choosing a profile.
- Entry exposes and persists a customer-selected 5°–90° angle; it wraps correctly across 0°/360° and applies threshold, confirmation, and close hysteresis.
- Magnetic interference cannot alone produce a `DOOR_OPEN` event under the recommended preset.
- Power Guard checks all four charging/witness combinations, lamp-baseline readiness, 10-second loss debounce, 30-second dual-signal recovery, and distinct Thai notification text.
- Power Guard sends exactly one opening and one recovery notification per charging-path or witness-light health transition; a confirmed outage alone uses incident progress/continuation delivery.
- Disconnected charging sets degraded health but does not disarm Vehicle Guard or Entry Guard.
- Each event produces exactly one intended Telegram owner message; progress edits never alter a Live Map message.

### Device acceptance

- Vehicle: arm, calibration, real movement, map start, and clean closure.
- Entry: 20 repeated open/close cycles on a mounted phone, including a metal-frame location.
- Power: verify the lamp hood/baseline, unplug only the phone charger, remove only the witness lamp, remove both power signals, then restore both; observe the correct Telegram text and a single recovery message for each case.
- Battery, thermal state, background survival, reboot recovery, and permission-denied paths are recorded separately from host test results.

## 9. Out of scope for this release

- External control boxes, USB data transport, BLE/Wi-Fi sensors, smart plugs, temperature probes, water-level sensors, and pump control.
- Claiming a building-wide mains outage, freezer temperature, pond water condition, or compressor/pump state from the phone and witness lamp.
- Changing current Telegram authorization, encryption, SMS policy, or GPS privacy boundaries.

## 10. Approval checklist

Before implementation, confirm:

1. Product names: `Vehicle Guard`, `Entry Guard`, and `Power Guard`.
2. Entry control: default 15°, quick choices 5°/15°/30°, exact range 5°–90°; open for 750 ms and close below 3° for 5 s.
3. The owner may always select another use; an armed switch is confirmed, disarms, preserves all profile-specific settings, calibrates, and requires explicit re-arm.
4. Power default: both charging and witness light absent for 10 s; both stable again for 30 s.
5. The witness lamp is a low-heat LED on the same monitored outlet/power strip as the phone charger, placed in a shaded hood over the phone light sensor.
6. Entry and Power profiles have no Live Map by default.
7. Power Guard may confirm loss only for the monitored shared charging setup, never a wider building, freezer, or pond condition.
