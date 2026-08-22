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
- Arming freezes the selected profile, resolved preset, overrides, thresholds, and calibration generation into one immutable armed-session snapshot. Editing settings or installing a newer preset cannot change detectors that are already protecting an active session.
- Existing customer configuration stays unchanged until the customer explicitly chooses a profile or taps **Use recommended settings**.
- Missing hardware is visible as `Unavailable` or `Degraded`; it is never silently treated as healthy.
- Losing charging power while armed must not disarm the app. The app continues on battery and reports the health change.
- All Telegram notifications remain owner-authorized. SMS behavior is unchanged by this design.
- GPS and Live Map remain exclusive to Vehicle Guard.
- Automatic recovery is a shared continuity policy for all three profiles, not a Power Guard detector. It resumes only a valid, durably armed session and never overrides an explicit owner Stop or Disarm.
- This release recovers after Android has booted successfully; it does not claim that an Android app can power on a fully powered-off phone. Hardware/OEM automatic power-on remains a separate per-device capability and acceptance result.
- Full unattended recovery in this release requires the dedicated monitoring phone to have no PIN, Pattern, or Password at boot. Direct Boot and moving Telegram or other secrets into device-protected storage are not part of this design.

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
armedProfileSnapshot? {
  armedSessionId
  profile
  resolvedPresetVersion
  effectiveConfiguration
  configurationFingerprint
  commissionedModelFingerprint?
  armedCalibrationSnapshot
}
profileSwitchTransaction? {
  transactionId
  oldArmedSessionId
  targetProfile
  phase
}
runtimeObservationGeneration
```

While disarmed, the editable effective configuration is `current preset + current profile overrides`. Future preset improvements may update untouched settings, but must not overwrite an explicit customer override.

While armed, only `armedProfileSnapshot` is authoritative. Its effective configuration and armed calibration snapshot are immutable until controlled disarm and prevent a Settings edit, preset migration, process callback, or late sensor sample from silently changing the active policy. `runtimeObservationGeneration` is separate, volatile registration state: it changes whenever listeners are replaced or a process/device restarts and does not mutate the frozen armed snapshot. `selectedProfile` identifies the profile the owner is preparing; it is not proof that a matching runtime is armed.

### 3.4 Changing the use of the phone later

The dashboard and Settings each expose a clearly labeled **Change use** action, so the same phone can move between a vehicle, a door, and a powered location without losing the customer's work.

- While disarmed, the owner selects another profile, reviews its current recommended/customized values, completes that profile's readiness check, and can arm it immediately.
- While armed, the action remains available but opens a confirmation sheet: **Keep current protection** or **Stop protection and change use**. Choosing **Keep current protection** leaves the armed snapshot unchanged.
- Confirming **Stop protection and change use** first persists a `profileSwitchTransaction` in `STOP_REQUESTED` before any detector or remote side effect. Recovery always converges an unfinished transaction toward stopped/disarmed; it must never restore the old armed profile after this durable owner intent exists.
- The transaction then advances durably through `OLD_RUNTIME_QUIESCED`, `SNAPSHOT_CLEARED`, and `NEW_PROFILE_SELECTED`: invalidate the old observation generation, stop accepting old-profile observations, settle queued old-profile delivery, and issue the final best-effort Live Map stop with no later location update admitted. It records the owner action with `transactionId` deduplication, clears the old armed snapshot, then selects and prepares the new profile.
- The new profile runs its own setup/calibration and requires an explicit Arm action afterward. The app must never auto-arm a newly selected profile.
- If the owner cancels or the new setup fails after the old profile has been stopped, the final state is `Disarmed / selected profile not ready`. The UI must not show the new profile as armed, and the old runtime must not remain active in the background. The owner may retry setup or select the previous profile again.
- A crash or reboot at any transaction phase resumes from the last durable phase and reaches the same truthful final state without rearming the old profile, duplicating the owner-action record, or admitting old-generation callbacks.
- If an incident is active, the sheet requires a second explicit confirmation. The incident is retained in history as stopped by the owner during a profile change; it must not be reported as an automatically resolved incident.
- A single persistent profile label and readable health summary identify what the phone is protecting now. Status is conveyed by text and icon as well as color; normal tap targets are at least 48 dp.

### 3.5 Automatic recovery after Android boot

Settings exposes one shared switch named **กลับมาป้องกันอัตโนมัติเมื่อเครื่องเปิดอีกครั้ง**. The name deliberately avoids **เปิดเครื่องอัตโนมัติ** because the app cannot turn on a fully powered-off phone. The switch controls what happens after Android has booted successfully and is available to the app.

- The switch is offered during first setup and in **Settings > System continuity**. It becomes enabled only after a confirmation sheet requires the owner to accept `ฉันเข้าใจว่าการไม่มีรหัสล็อกหน้าจอลดการป้องกันข้อมูลในโทรศัพท์ และเหมาะกับโทรศัพท์เฉพาะงานเท่านั้น`; merely installing or updating the app does not arm protection.
- Automatic recovery applies only when durable owner intent is `RUNNING + ARMED` with a valid `armedProfileSnapshot`. A durable owner Stop or Disarm always wins over boot, sticky service recreation, watchdog, or package-replacement triggers.
- The switch gates restoration after a full Android boot only. Ordinary same-boot process/service recreation and a supported package replacement continue protecting a current `RUNNING + ARMED` session even when boot recovery is off.
- This release intentionally does not implement Direct Boot. Telegram credentials, TOTP material, SMS keys, allowlists, and other secrets remain in credential-protected encrypted storage.
- The app checks `KeyguardManager.isDeviceSecure` when the switch is enabled, before every Arm, whenever the app returns to the foreground, and during continuity-readiness reconciliation. `false` means no secure PIN/Pattern/Password is configured; it does not prove OEM boot permission or hardware automatic power-on.
- If a PIN, Pattern, or Password exists when the owner enables the switch, the switch remains off. If it exists while automatic recovery is enabled and the owner tries to Arm, final Arm is blocked until the owner chooses **เปิดการตั้งค่าล็อกหน้าจอ** or **ป้องกันต่อโดยไม่เปิดการกู้คืนหลังรีบูต**. The app can open only the general Android Security settings; it cannot remove the device credential itself.
- If a secure credential is detected after the phone is already armed, current sensor protection does not silently disarm. The state becomes `Armed Degraded`, with the scoped hero text `กำลังปกป้องอยู่ · การกลับมาหลังรีบูตยังไม่พร้อม`; one deduplicated continuity-health episode is opened, and Telegram receives at most one warning for that episode.
- After the owner removes a secure credential, the app rechecks the encrypted credential store and Telegram configuration in addition to `isDeviceSecure`. Only stable fresh success closes the same health episode and restores full boot readiness. A key-store/configuration failure marks the `Telegram after boot` row `Blocked — ต้องตรวจสอบการเชื่อมต่อ Telegram หรือจับคู่ใหม่`, without exposing any secret; a valid local armed snapshot may continue as `Armed Degraded` rather than losing sensor protection.
- If Telegram credentials are unavailable, the blocked state is still persisted and shown locally, but the app must not claim that its Telegram warning was sent. Delivery can resume only after the credential path is valid again.
- No-PIN readiness does not replace normal app security. The UI states that this choice lowers local device-data protection and is not recommended for a personal phone.

The always-visible short explanation below the switch is:

> หากเปิดการกู้คืนหลังบูต โทรศัพท์เครื่องนี้ต้องไม่มีรหัสล็อกหน้าจอ (PIN, รูปแบบ หรือรหัสผ่าน) ไม่เช่นนั้นระบบจะรอให้ปลดล็อกก่อน จึงจะกลับมาป้องกันและส่ง Telegram ได้

An expandable **รายละเอียดความปลอดภัย** note adds:

> เหมาะสำหรับโทรศัพท์เฉพาะงานและไม่แนะนำกับโทรศัพท์ส่วนตัว การไม่ตั้งรหัสล็อกเครื่องลดการป้องกันข้อมูลในตัวเครื่อง ควรติดตั้งเครื่องไว้ในจุดที่ควบคุมได้

Continuity readiness is shown as separate truthful rows:

| Readiness row | Meaning |
|---|---|
| App/service recovery | The durable owner intent and armed snapshot can be restored after a process/service restart |
| Android boot recovery | The app is eligible to resume after normal Android boot |
| Screen-lock requirement | `Ready: no PIN/Pattern/Password` or a specific action required |
| OEM auto-start | Configured, unverified, failed, or verified on this device |
| Telegram after boot | Credential and delivery path are available after normal boot |
| Hardware automatic power-on | A separate per-model result; never inferred from an Android reboot test |

`Android boot recovery` becomes **Verified on this device** only after a controlled reboot proves that the same device, OS build, app version/recovery schema, and current no-PIN configuration resume protection and produce the exact expected Telegram count. A failed test, a newly detected secure credential, or a relevant OS/app recovery-schema change invalidates the result. A successful reboot test does not prove that the phone will power itself on after battery exhaustion or thermal shutdown; that hardware/OEM behavior is tested and labeled separately.

### 3.6 Paper-light interface system

The redesign uses a fixed, calm paper-light visual system for this release. Android dynamic color is disabled so status meaning and contrast do not change by wallpaper.

- Keep three bottom destinations: **ปกป้อง · เหตุการณ์ · ตั้งค่า**. Vehicle, Entry, and Power are protection contexts, not additional navigation tabs.
- **ปกป้อง** shows the active profile chip and **เปลี่ยนการใช้งาน**, one authoritative status card, one primary Arm/Disarm action, a profile-specific summary, an expandable system-readiness section, and a persistent incident/recovery banner when applicable.
- The hero always separates current detection from future continuity. For example, `กำลังปกป้องอยู่ · การกลับมาหลังรีบูตยังไม่พร้อม` means sensors are still active now while boot recovery needs action; it must not be shortened to a vague `การป้องกันจำกัด`.
- Vehicle summary shows movement readiness and Live Map only after confirmed movement. Entry summary shows the calibrated closed position and selected door angle, for example `ประตูปิด · 0°` and `แจ้งเมื่อเกิน 15°`. Power summary always shows charging and witness-light state as two independent rows.
- **เหตุการณ์** uses a chronological lifecycle: opened, ongoing/updated, evidence interrupted, recovered, owner stopped, and delivery state. Each item identifies its profile and stable Event ID. Clear history is placed in an overflow/danger area rather than as a primary action.
- **ตั้งค่า** is grouped in this order: current use/profile, profile detection, notifications, system continuity, then advanced diagnostics. Recommended/customized values remain editable per profile.
- The runtime source of truth is the `ProtectionAppScreen` flow and its coordinator-backed read model. The redesign must not revive a disconnected legacy dashboard or fabricate profile, door-angle, witness-light, or readiness values that are absent from the domain state.
- Show at most one persistent banner using this precedence: active incident; recovery blocked/degraded; system recovered; none. Events and TalkBack use distinct terms: `ระบบกลับมาทำงาน` for service/boot recovery, `เหตุการณ์สิ้นสุด` for an incident close, and `ไฟเลี้ยงกลับมาคงที่` for Power Guard settlement. Only the banner owns the assertive live-region announcement; the hero exposes its current semantics without announcing the same transition twice.

Visual and accessibility tokens:

| Token | Value |
|---|---|
| Paper background | `#FCFBF8` |
| Card | `#FFFFFF` |
| Primary / secondary ink | `#171717` / `#4B4B4B` |
| Outline | `#767676` |
| Primary action | `#171717` with white text |
| Primary action pressed / focus | `#303030` / a 2 dp `#145DA0` focus indicator |
| Disabled surface / ink | `#EEEEEC` / `#5C5C5C` |
| Dialog scrim | `#000000` at 32% |
| Snackbar | `#2B2B2B` with `#FFFFFF` text |
| Armed | `#146C43` on `#E4F4EB` |
| Degraded | `#8A4B00` on `#FFF1D6` |
| Incident | `#A81818` on `#FCE8E6` |
| Recovery | `#145DA0` on `#E9F2FB` |
| Offline | `#5C5C5C` on `#EEEEEC` |

Use the Android system sans/Roboto/Noto fallback with Thai support: body `16 sp / 24 sp`, title `20 sp`, and headline `28 sp`. Use a 4/8 dp spacing system, 16–20 dp page inset, 24 dp section spacing, and white cards with a 12 dp radius and 1 dp border. Status/navigation bars use light system bars with dark icons. The outline token is for boundaries, not body copy. Avoid gradients, glass effects, heavy shadows, decorative emoji, and text smaller than the accessible scale. Use consistent vector icons, minimum 48 dp touch targets, text plus icon plus color for state, large-font reflow through at least 200% font scale rather than aggressive shrinking, and TalkBack announcements only on meaningful state transitions.

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
- Initial commissioning shows the live relative angle, starting near `0°` while the door is closed. The commissioned mount only becomes healthy after two consistent open/close cycles reach the selected angle and return below the close threshold.
- The label always means “angle opened from the calibrated closed position,” not a compass bearing or a magnetic-field strength. This makes the value meaningful even when the door faces a different direction after installation.
- Changing this control while disarmed takes effect for the next arm. While armed, the app asks the owner to make the controlled disarm/calibrate/re-arm transition so that a changed threshold cannot create a surprise alert from an old baseline.

#### Detection contract

- Initial commissioning begins with the door closed and the phone still for five seconds, then learns the expected hinge-axis motion from the two guided open/close cycles.
- On every later arm, the customer again closes the door and keeps the phone still for a five-second armed-session calibration. The detector records a fresh relative orientation baseline, validates the saved hinge-axis model and source quality, then computes `door_angle_delta_deg` from the phone's orientation change.
- The commissioned hinge-model fingerprint includes the phone sensor identity, mount/orientation signature, learned hinge axis and allowed opening direction, selected orientation-source/fusion policy, commissioning algorithm version, and continuity of Entry use. Changing only the alert angle, confirmation time, or notification preference does not invalidate that model.
- Leaving Entry for another profile, moving/remounting the phone, changing an orientation-source/fusion field, changing the relevant sensor identity, or upgrading the commissioning algorithm invalidates the fingerprint and requires the two-cycle commissioning again before Entry can be `Healthy`.
- The angle calculation uses a normalized relative rotation (quaternion or rotation matrix), decomposes it into twist around the learned hinge axis plus cross-axis swing/residual, and defines `door_angle_delta_deg` as the absolute hinge-axis twist from 0°–180°. It must not reuse total geodesic rotation, an approximate generic vector delta, or raw magnetic-field strength as the door angle.
- Quaternion sign-equivalent samples (`q` and `-q`) represent the same orientation and must not create an angle jump. Door-angle logic has no 0°/360° heading wrap.
- Default trigger: angle is at least **15°** for **750 ms**.
- Advanced range: **5°–90°**, confirmation time **250 ms–3 s**.
- Closing threshold uses hysteresis: below **3°** and stable for **5 s** marks the door closed and resolves the event.
- Rotation/gyro are the main angle sources. Raw magnetic-field change is supporting evidence only because metal frames and nearby magnets can distort it.
- The closed-position baseline and learned hinge axis are frozen for the complete armed session. The detector must never auto-rebaseline from slow or sustained movement while armed.
- Evaluation order is strict: validate source freshness/quality, hinge-axis residual, and allowed opening direction before applying the customer angle threshold. A sample that fails the axis/direction gate cannot produce `DOOR_OPEN`, even if its total rotation or twist exceeds the selected angle.
- A stale/unavailable orientation source creates or updates a deduplicated `ENTRY_SOURCE_UNAVAILABLE` health episode and changes Entry readiness to `Degraded`. It makes no claim that the phone or mount moved. The health episode recovers after fresh compatible source/quality evidence is continuous for 5 seconds.
- Rotation that materially deviates from the learned hinge axis or allowed direction, or loss of mount/fingerprint consistency, creates or updates `ENTRY_MOUNT_MOVED` and changes Entry readiness to `Degraded` until controlled recommissioning. `ENTRY_MOUNT_MOVED` has priority over `DOOR_OPEN`; off-axis/opposite-direction movement is reported as `โทรศัพท์หรือขายึดถูกขยับ`, not as proof that the door opened.
- Vibration, light, proximity, and audio may be enabled as supporting evidence, but should not be described as proof that the door is open.
- GPS and Live Map are off by default and unavailable in this profile.

The event taxonomy must add dedicated `DOOR_OPEN`/`DOOR_CLOSED`, `ENTRY_SOURCE_UNAVAILABLE`, and `ENTRY_MOUNT_MOVED` paths. One physical opening creates one door episode: subsequent angle samples update the same event and message rather than creating repeated alerts. One source-unavailable episode remains deduplicated until fresh compatible evidence is stable for 5 seconds. One mount-movement episode remains open and deduplicated until controlled disarm/recommissioning; repeated off-axis samples do not enqueue repeated Telegram transitions. Orientation or magnetic evidence must not be reported to the customer as generic vehicle vibration.

If orientation becomes stale, off-axis, or mount-invalid while a door episode is open, mark the door episode `EVIDENCE_INTERRUPTED` and retain it unresolved. Invalid evidence must never synthesize `DOOR_CLOSED` or send `ประตูปิดแล้ว`. Open or update `ENTRY_SOURCE_UNAVAILABLE` for source dropout, or `ENTRY_MOUNT_MOVED` for axis/direction/fingerprint failure. The door episode may resume and close only after the same compatible mount model produces fresh valid below-3° evidence for 5 seconds; if the mount fingerprint was invalidated, owner disarm/recommissioning stops it with an explicit `หยุดการเฝ้าระวัง—หลักฐานตำแหน่งประตูขาดหาย`, not an automatic door-closed result.

Suggested Telegram messages:

- `ประตูเปิด 18° จากตำแหน่งปิด`
- `ประตูปิดและนิ่งแล้ว`
- `ข้อมูลมุมประตูขาดหาย กำลังรอเซนเซอร์กลับมาทำงาน`
- `โทรศัพท์หรือขายึดถูกขยับ กรุณาตรวจสอบและปรับเทียบใหม่`
- `ตรวจพบแรงกระแทกที่ประตู` when impact evidence is independently confirmed

#### Installation constraints

- The phone must be firmly mounted to the moving door or the protected surface; a loose phone cannot provide a trustworthy angle.
- A customer must complete two consistent test openings and closings before the profile is called healthy.
- The screen explains that large metal doors, magnets, or moving the phone itself can degrade compass-derived evidence.

### 4.3 Power Guard

**Customer outcome:** distinguish a cable/charger problem from an outage at the monitored charging setup, then report a stable recovery without avoidable duplicate messages.

Power Guard uses two independent signals:

1. **Charging path** — Android reports whether external power is connected to the phone. It uses plug/power state, not battery percentage or instantaneous current, so a full phone that remains plugged in is still connected.
2. **Power-witness light** — a small, low-heat LED lamp is plugged into the same monitored outlet or power strip as the phone charger and shines directly onto the phone's ambient-light sensor.

#### Physical setup and readiness

- The witness lamp and phone charger must share the same monitored outlet/power strip. The app must not assume that nearby sockets are on the same circuit.
- The lamp may be hung in front of the phone, but it must be held in a small opaque hood/clip that directs its light to the ambient sensor and blocks daylight and room lighting. The hood must not cover the phone's cooling path or prevent secure mounting.
- Commissioning keeps the charger connected while guiding the customer to switch the witness lamp off and back on once. The app records separate dark and lit sample windows and accepts the setup only when their ranges are stable and separated by a safe guard band; a bright value alone is not proof that the phone sees the witness lamp.
- At every arm, full `Healthy` readiness requires a short lamp off/on integrity challenge while the charger remains connected. The app rechecks the commissioned dark/lit separation and verifies both `Charging connected` and `Witness light detected`.
- A successful commissioning challenge may satisfy the immediately following first Arm when it occurred within 10 minutes in the same uninterrupted setup flow and the sensor identity, configuration fingerprint, phone mount, lamp, and hood have not changed. It is not repeated twice in that case; every later Arm requires a new integrity challenge.
- The owner may skip the per-arm lamp challenge for convenience, but the result is `Armed Degraded / witness placement not revalidated`; the app cannot claim that an unchanged lit value proves the hood has not shifted. If either signal is unavailable, arming is also allowed only as `Armed Degraded`; no initial outage incident is created.
- This is a dedicated calibrated light path. It must not reuse the generic light-intrusion threshold, because Power Guard looks for a sustained drop from this lamp's own baseline, not an increase in ambient room light.
- The commissioned light thresholds and hysteresis are frozen for the armed session. They must not adapt downward to a dimming lamp or shifted hood. Excessive variance, values inside the guard band, or stale light samples produce `Witness light unavailable/Degraded` with a setup recovery instruction rather than a false outage conclusion.

#### Decision contract

| Observed state for 10 s after a healthy baseline | Customer-visible result | Incident semantics |
|---|---|---|
| Charging connected + witness light detected | Power monitoring ready | No incident |
| Charging disconnected + witness light detected | `การชาร์จโทรศัพท์หยุด` — check the cable, charger, or phone charging port | A charging-path health alert; never call this a power outage |
| Charging connected + witness light dark | `ไฟยืนยันไม่พบ` — check the lamp, its placement, and its power path | A witness-light health alert; never call this a power outage |
| Charging disconnected + witness light dark | `ยืนยันไฟเลี้ยงขาดในจุดที่เฝ้าระวัง` | Open `PHONE_POWER_LOST` only in this dual-signal state |

When both signals are healthy again for **30 s**, close the confirmed `PHONE_POWER_LOST` incident and send `ไฟเลี้ยงที่จุดเฝ้าระวังกลับมาคงที่แล้ว`. Each composite state owns its own continuous debounce window: dual-signal loss must itself remain continuous for **10 s** and never inherits elapsed time from a preceding one-signal condition. A one-signal health alert opens only after that one-signal state remains continuous for **10 s**, and recovers only after both signals have been healthy for **30 s**; it has no continuation spam. An open confirmed outage retains the existing incident progress/continuation policy.

#### Power episode arbitration and idempotency

- The first abnormal charging or witness-light state creates one power episode identified by `armedSessionId + powerEpisodeId`.
- The arbiter evaluates the composite state before delivery, with dual-signal loss taking priority over either one-signal condition. Entering dual loss cancels any not-yet-delivered one-signal timer and starts a new 10-second dual-loss timer from zero; leaving dual loss resets that timer.
- If the first signal has not yet reached its own 10-second opening and the second signal disappears, no preliminary one-signal message is enqueued. The app waits for the new dual state to remain continuous for its full 10 seconds before opening a confirmed outage.
- State changes remain inside that episode until both signals have been healthy for the 30-second close window. `Charging disconnected` followed by `Witness light dark` escalates the existing episode to confirmed power loss; it must not open a second parallel incident.
- Escalation supersedes the earlier one-signal health condition, so that condition does not emit a separate recovery message during the same episode.
- A single-signal crossover, such as charging loss changing to witness-light loss without a 30-second all-healthy close and without 10 seconds of continuous dual loss, remains the same episode. After the new one-signal state is stable for 10 seconds, inspect the episode's durable owner-notification state: enqueue a normal health opening if no earlier semantic state reached the owner, enqueue one `CONDITION_CHANGED(from, to)` update after an accepted earlier opening, or enqueue an uncertainty-referencing update after ambiguous earlier delivery. Do not enqueue an old-condition recovery plus a second opening.
- If only one signal returns during a confirmed outage, the episode remains open as partial recovery. The dashboard and event record update, but no close message is sent.
- Before sending, assign a monotonically increasing durable `transitionOrdinal` whenever the stable semantic state genuinely changes, then write the remote transition to a durable outbox keyed by `armedSessionId + powerEpisodeId + transitionOrdinal + transitionKind`. Repeated samples in the same semantic state do not advance the ordinal. This permits legitimate repeated crossovers while one serialized episode arbiter/delivery owner, the outgoing-text dedup layer, and durable accepted receipts suppress ordinary callback, retry, polling, and known process-recovery duplicates.
- Durable outbox states are `PENDING`, `IN_FLIGHT`, `ACCEPTED`, `DELIVERY_UNCERTAIN`, `SUPERSEDED`, and `FAILED_FINAL`. When the semantic condition changes, mark any not-started obsolete `PENDING` item `SUPERSEDED` before enqueueing current copy; a superseded item must never send later when connectivity returns.
- An `IN_FLIGHT` item cannot be assumed cancelled. The serialized arbiter records the newer stable condition but waits for accepted/uncertain/final-failure resolution before choosing whether its next owner copy is an opening, `CONDITION_CHANGED`, or an uncertainty-referencing update. This prevents an offline stale opening and a current opening from both being delivered as unrelated alerts.
- Telegram does not provide a server-side idempotency key for `sendMessage`. A crash or timeout after Telegram may have accepted a request but before the app stores the response is an ambiguous-delivery window; the app must not claim mathematical exactly-once delivery. Mark the outbox item `DELIVERY_UNCERTAIN`, avoid parallel immediate retries, expose the stable episode/transition ID in Events, and apply the ambiguity policy below.
- Default ambiguity policy is safety-weighted and explicit: do not automatically retry uncertain one-signal health/recovery messages. For a confirmed power-loss opening, wait 30 seconds and permit one retry carrying the same visible episode ID and the prefix `ส่งซ้ำเพื่อยืนยันเหตุเดิม—ผลการส่งครั้งแรกไม่แน่นอน` only if that episode is still in continuous confirmed loss and the opening transition remains current. Partial/complete recovery, semantic supersession, or episode close cancels any not-started safety retry; if the episode becomes healthy after an uncertain opening, use the uncertainty-referencing settlement copy instead of sending a stale outage retry. Any configured SMS fallback continues under its existing approved policy.
- After the complete healthy window, choose settlement from durable owner-notification state. If no opening was accepted or ambiguous, retire a transient episode silently with no orphan recovery. If a one-signal opening was accepted, enqueue its health recovery; if confirmed loss was accepted, enqueue the confirmed close. If opening delivery was uncertain, enqueue one settlement message that references the same episode ID and says the initial delivery result was uncertain rather than assuming the owner saw it. Retire the episode when its durable outbox state is settled; a later abnormality receives a new `powerEpisodeId`.

#### Power-specific recovery behavior

The shared recovery state machine is defined in section 5.2. Power Guard additionally persists the active power episode, transition ordinal, durable owner-notification state, durable outbox, and accepted delivery receipts. It does not persist raw continuous sensor streams or unfinished debounce progress.

- After any runtime-generation change, require fresh charging and light samples before reporting `Healthy` or `Recovered`. Restart every unfinished 10-second/30-second continuous-observation window from zero.
- A compatible stored calibration may provide provisional thresholds only when the phone sensor identity, profile/configuration version, and armed session still match. Missing or incompatible calibration yields `Armed Degraded` and cannot produce a confirmed-outage claim.
- If the last durable state was healthy and fresh dual-signal loss is observed for 10 seconds after recovery, the app may open a confirmed episode but must say that loss was detected after recovery; it must not invent its start time.
- If an episode was already open, recovery resumes the same episode and reconciles known outbox receipts rather than starting another. An ambiguous Telegram acceptance remains `DELIVERY_UNCERTAIN` under the transport policy above.

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

### 5.1 Alert-delivery path readiness

A power outage may also turn off the site's Wi-Fi router. Power Guard therefore projects delivery-path health separately from sensor health:

- `Independent Telegram path ready` — an owner-authorized Telegram test has succeeded over a validated, explicitly cellular-bound route within the last 24 hours and the same cellular transport remains available. A SIM or cellular capability flag without a successful bound-route test is insufficient. The UI shows the tested route and time.
- `Site Wi-Fi only` — Telegram is currently reachable, but the known route depends on local Wi-Fi. Arming remains allowed as `Armed Degraded` with the explanation `ไฟดับอาจทำให้ส่ง Telegram ไม่ได้`.
- `Alert path offline/unknown` — no fresh delivery evidence exists. Arming remains allowed as `Armed Degraded`, and the app presents a user-initiated alert test and recovery guidance.

SMS eligibility is displayed separately as `Critical-outage SMS fallback eligible` or `SMS fallback unavailable/unknown`. It never upgrades ordinary Telegram health/recovery delivery readiness and applies only when the existing approved SMS policy already permits fallback for a confirmed critical outage. This design does not add an SMS test, recipient, permission, or delivery rule.

Capability alone must not be labeled as verified Telegram delivery. Setup offers an explicit, clearly marked Telegram alert test and records its bound route and freshness without sending repeated background test messages. A failed test, loss/change of the tested cellular transport, or age over 24 hours downgrades readiness; a reboot requires fresh passive reachability evidence but does not automatically send another test message. Remote profile switching remains unavailable because Vehicle, Entry, and Power setup each require physical placement and calibration.

### 5.2 Shared automatic-recovery contract

Recovery is orthogonal to the existing top-level protection states; it must not create a second armed-state authority. `ProtectionCoordinator` remains authoritative, while a recovery phase explains how the current truthful state was restored.

```text
continuitySettings {
  autoRecoveryAfterBoot
}
desiredService = RUNNING | STOPPED_BY_OWNER
desiredProtection = DISARMED | ARMED
protectionEpochId
armedSessionId?
runtimeObservationGeneration
recoveryEpisode? {
  recoveryAttemptId
  trigger
  phase
  startedAtWallClockMs
  finishedAtWallClockMs?
  causeEvidence
  evidenceGapId?
  deliveryState
}
```

`STOPPED_BY_OWNER` implies `desiredProtection = DISARMED`. `RUNNING + DISARMED` is valid: the service may remain online for authorized commands without restoring detectors. `RUNNING + ARMED` is the only combination eligible for same-boot process/package recovery; an Android boot additionally requires `autoRecoveryAfterBoot = true`.

The atomic recovery order is:

1. A sticky restart, `BOOT_COMPLETED`, supported package replacement, or watchdog request first loads durable owner intent and the immutable armed snapshot before heartbeat, Telegram, or sensor claims.
2. `STOPPED_BY_OWNER` or `DISARMED` is a no-op for automatic protection recovery. Owner Stop increments/fences the runtime generation, invalidates a pending recovery attempt, cancels its watchdog work, and then stops the service. If Android boots while `autoRecoveryAfterBoot = false`, do not restore detectors: atomically transition the prior armed intent to `DISARMED`, retain any interrupted incident as an evidence-gap history item, and record/send at most one truthful `ไม่ได้กลับมาป้องกันอัตโนมัติ เพราะปิดการตั้งค่านี้` status if its authorized delivery path is available.
3. A valid armed snapshot keeps the same `protectionEpochId` and `armedSessionId` for incident/delivery deduplication, creates a new `runtimeObservationGeneration`, re-registers sources, rejects old-generation callbacks, and begins fresh profile-specific readiness/calibration.
4. Elapsed-realtime timestamps and unfinished confirmation/quiet windows never cross a process death, reboot, observation-generation change, or evidence gap. Wall-clock timestamps are display/history metadata, not continuity evidence.
5. Recovery commits exactly one terminal phase: `RECOVERED_HEALTHY`, `RECOVERED_DEGRADED`, or `RECOVERY_BLOCKED`, before autonomous Telegram or heartbeat delivery begins. Invalid/corrupt/future-schema armed state is blocked and visible; it must not silently become disarmed or claim success. `RECOVERY_BLOCKED` is an orthogonal phase projected through the existing `SETUP_REQUIRED` state when owner action can recover it, not a new top-level protection state.
6. An incident active before the gap remains linked to the same incident and becomes `EVIDENCE_INTERRUPTED`; the app cannot infer what happened while the phone or service was unavailable and must not synthesize an incident close.
7. An owner Disarm or Stop during recovery supersedes the recovery atomically. No late calibration, detector callback, watchdog, or outbox task may re-arm the app afterward.

Terminal phases have one meaning across profiles:

| Recovery phase | Contract |
|---|---|
| `RECOVERED_HEALTHY` | Required profile detectors and the authorized Telegram path are freshly ready |
| `RECOVERED_DEGRADED` | Safe local protection resumed, but a detector, permission, location, thermal, or Telegram capability is unavailable and named explicitly |
| `RECOVERY_BLOCKED` | The valid armed protection runtime itself cannot be restored; project through `SETUP_REQUIRED` and require owner action |

Profile-specific recovery truth:

- **Vehicle Guard:** obtain fresh motion/location readiness. Do not start a new Live Map or movement message merely because recovery occurred; existing movement confirmation and GPS privacy/cadence policies remain unchanged.
- **Entry Guard:** revalidate source freshness, mount fingerprint, hinge model, and closed-position evidence. If the first valid post-recovery observation shows the door open, say `ตรวจพบประตูเปิดหลังระบบกลับมาทำงาน`; do not claim when it opened.
- **Power Guard:** apply the fresh-sample and restarted-window rules in section 4.3. A powered-off interval is an evidence gap, not proof of an outage start time.

Recovery triggers are recorded distinctly: service/process recreation, Android boot, package replacement, manual reopen after force-stop, and watchdog assistance. Android force-stop cannot self-recover until the owner or platform unstops the app. A fully powered-off phone cannot observe sensors or send Telegram.

Process-exit evidence such as `ApplicationExitInfo`, the latest battery state, and the latest thermal status may explain a recovery only when the platform evidence is available and temporally credible. Otherwise the cause is `ไม่สามารถยืนยันสาเหตุได้`. Battery-low, unplugged, or severe thermal observations may open one deduplicated system-health episode before failure, but they must not predict a shutdown. If the device remains thermally critical after boot, recover as degraded and postpone optional high-load supporting sensors until safe evidence is stable.

### 5.3 Recovery and heartbeat delivery

`SystemStatusDeliveryCoordinator` is the sole owner of autonomous heartbeat and recovery-status delivery. Incident text remains owned by `IncidentDeliveryCoordinator`; Vehicle Live Location remains owned by `LivePursuitCoordinator`. A single source observation must never fan out the same semantic text through multiple owners.

- Use one durable recovery event and per-chat outbox identity for each `recoveryAttemptId`. Determinate accepted receipts suppress duplicates across process death, repeated boot broadcasts, watchdog overlap, polling, and callback replay.
- If recovery reaches its terminal phase before any message is sent, send only the terminal summary. If a `กำลังกู้คืน` message was already accepted, edit that same Telegram message where possible; if edit is unavailable, allow at most one terminal follow-up with the same visible Event ID.
- A transport timeout after possible acceptance is `DELIVERY_UNCERTAIN`, not definite failure and not proof of exactly-once delivery. Events exposes the result and stable Event ID.
- If the network is unavailable after boot, keep the terminal recovery item pending in the durable outbox and send it when the authorized path returns. Do not send a second recovery event or an immediate heartbeat in its place.
- Heartbeat cannot start before the terminal recovery state is committed. Its first post-recovery Ping waits one complete configured heartbeat interval, preventing an immediate boot Ping from colliding with the recovery summary.
- A user-issued `/status` command still produces exactly one command reply and does not reset, duplicate, or take ownership of the autonomous recovery message.

Professional Telegram examples, without decorative emoji:

- Healthy: `ระบบกลับมาป้องกันแล้ว เวลา … สถานะปัจจุบัน: พร้อม ช่วงที่เครื่องหรือบริการหยุด ระบบไม่สามารถตรวจจับหรือส่งข้อมูลได้ Event: RCV-…`
- Degraded: `ระบบกลับมาป้องกันแบบจำกัด เวลา … ต้องตรวจสอบ: … Event: RCV-…`
- Blocked: `ไม่สามารถกู้สถานะป้องกันเดิมได้ ระบบยังไม่พร้อมป้องกัน กรุณาเปิดแอปตรวจสอบ Event: RCV-…`
- Secure credential detected while armed: `ตรวจพบ PIN, Pattern หรือ Password การป้องกันยังทำงานอยู่ แต่การกลับมาป้องกันอัตโนมัติหลังบูตจะรอการปลดล็อก กรุณาตรวจสอบการตั้งค่าความต่อเนื่อง Event: SYS-…`

## 6. Reliability and safety boundaries

- A disconnected charger never auto-disarms the app.
- A phone-alone Entry Guard cannot prove forced entry if the phone is removed, poorly mounted, or its orientation is disturbed outside the door movement.
- A dual-signal Power Guard can confirm loss of power only for the configured shared charging outlet/power strip. It cannot claim a building-wide mains outage, a freezer compressor failure, water temperature, or a fish-pond pump state.
- Power Guard must never send a “power outage” message when only charging or only witness light is missing. A failed lamp, displaced hood, cable fault, or charger fault has its own explicit health message.
- A reachable site Wi-Fi connection is not proof that a Telegram path will survive the monitored power loss. Delivery readiness must expose whether the Telegram route is independently cellular-tested, site-dependent, offline, or unknown, while critical SMS fallback eligibility remains a separate existing-policy status.
- The app must state these boundaries in setup text and alert copy; it must not make broader claims.
- Unsupported sensors, missing permissions, weak calibration, or an unavailable charging state make the relevant capability `Degraded`, not silently successful.
- Existing encryption, authorization, Telegram pinning, and SMS fallback boundaries are unchanged.
- The app cannot power on a fully powered-off phone, guarantee OEM automatic start on every model, observe events while the device is off, or recover itself from Android force-stop without a later owner/platform interaction.
- Full unattended boot recovery is not ready while a PIN, Pattern, or Password exists. This release does not bypass the lock screen and does not move Telegram or other secrets into device-protected storage.
- A reboot test proves only the tested device/OS/app configuration. Charger-triggered power-on after battery exhaustion or thermal shutdown is a separate hardware/OEM capability and must not inherit that verified label.

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
- Immutable `ArmedProfileSnapshot`, owned by `ProtectionCoordinator`, containing the armed profile, resolved effective configuration, calibration generation, and armed session ID.
- Coordinator-owned profile-switch transaction that invalidates the old generation, settles old-profile remote work, clears the old snapshot, and leaves a failed/cancelled new setup disarmed and visibly not ready.
- Entry-specific orientation baseline, learned hinge-axis quality, configurable door-angle threshold, and `DOOR_OPEN`/`DOOR_CLOSED`/`ENTRY_SOURCE_UNAVAILABLE`/`ENTRY_MOUNT_MOVED` events.
- Power-specific charging transition detector, commissioned power-witness light detector, durable power-episode arbiter, and `PHONE_POWER_LOST`/`PHONE_POWER_RESTORED` events. The generic ambient-light intrusion detector remains semantically separate.
- `SystemContinuitySettings`, typed `RecoveryReadiness`, and a versioned, atomic `RecoveryEpisode` record that extends the existing protection recovery store/policy rather than creating a second state owner.
- `SystemStatusDeliveryCoordinator` as the single autonomous heartbeat/recovery owner with a durable per-chat outbox; incident text and Live Location retain their existing separate owners.
- A boot/package-replacement receiver that accepts only trusted platform actions, uses an idempotent trigger token, loads durable owner intent before recovery, and does not trust an unverified vendor quick-boot action as equivalent to a protected system broadcast.
- A best-effort watchdog that is scheduled from normal running startup, records readiness/failure, checks the durable owner-stop generation before starting work, and is cancelled on owner Stop. It is not presented as a resurrection guarantee.
- Boot recovery starts the foreground service and its persistent notification; it does not launch or place an Activity over the lock/home screen. The owner opens the UI only when details or corrective action are required.
- Typed sensor-freshness, calibration-quality, restart-recovery, screen-lock readiness, delivery-path health, and Thai message projection.

The protection coordinator remains the authoritative owner of armed state. Sensor sources publish observations and health; they do not send Telegram messages directly.

## 8. Verification plan

### Automated tests

- Profile resolver keeps overrides isolated and restore affects only one profile.
- Arming freezes an immutable effective configuration; Settings edits, preset changes, and late callbacks cannot mutate the active armed snapshot.
- Switching profile while disarmed preserves each profile's custom settings. Switching while armed requires confirmation, invalidates the old generation, settles old-profile remote work, does not auto-arm, and never emits a false incident-resolution message.
- Cancelling or failing the new setup after the old profile stops leaves one truthful disarmed/not-ready state with no legacy runtime still active.
- Existing customers keep their configuration until choosing a profile.
- Entry exposes and persists a customer-selected 5°–90° threshold; the physical relative angle remains 0°–180°, treats `q`/`-q` as equivalent, and never applies compass-heading 0°/360° wrap.
- Entry requires two consistent hinge-axis/direction cycles, freezes its baseline while armed, derives the customer angle only from hinge-axis twist, applies the residual/direction gate before the angle threshold, produces one event per physical opening, and separates recoverable source dropout from recommission-required mount movement. Either invalid-evidence class interrupts rather than closes an open door episode.
- Magnetic interference cannot alone produce a `DOOR_OPEN` event under the recommended preset.
- Power Guard commissioning distinguishes a real lamp on/off cycle, rejects overlapping/noisy ranges, freezes the armed threshold, and degrades on stale/ambiguous light without adapting into darkness.
- Power Guard checks all four charging/witness combinations, 10-second loss debounce, 30-second dual-signal recovery, and distinct Thai notification text.
- Charging-only, witness-only, dual loss, partial recovery, and complete recovery remain one episode with a durable transition ordinal and one outbox key per genuine semantic change. A crossover with no prior owner-visible semantic state becomes an opening; later crossovers become `CONDITION_CHANGED`. Known-result retry and restart paths suppress ordinary duplicates, while ambiguous Telegram acceptance follows `DELIVERY_UNCERTAIN` policy.
- Transient abnormality that never produced an accepted/uncertain opening retires silently after the healthy window; pending obsolete copy is superseded, in-flight ambiguity is reconciled before new copy selection, and no orphan recovery is sent.
- Process restart restores only compatible durable session/calibration metadata, requires fresh samples, resets unfinished continuous-observation windows, resumes open episodes without timer progress, and distinguishes itself from a powered-off phone.
- Device reboot preserves a valid durable armed session/episode identity but creates a new runtime generation and restarts all monotonic confirmation windows from fresh samples.
- Automatic recovery is a no-op after owner Stop/Disarm; owner intent wins races against boot, sticky restart, package replacement, watchdog delivery, calibration completion, and late callbacks.
- With boot recovery disabled, a full Android boot does not restore detectors and settles the prior armed intent to one truthful disarmed state/message, while same-boot process and supported package-replacement recovery remain active.
- Recovery distinguishes service/process recreation, Android boot, package replacement, force-stop/manual reopen, watchdog assistance, and a fully powered-off evidence gap. Invalid/future/corrupt recovery state becomes visible `RECOVERY_BLOCKED`, not a silent disarm.
- No-PIN readiness checks cover enabling the switch, Arm, app resume, and continuity reconciliation. Adding a secure credential while armed produces one degraded health episode; removing it settles that same episode only after the encrypted credential store and Telegram configuration also pass fresh checks.
- Heartbeat is gated until a terminal recovery phase and waits a full interval afterward. Duplicate boot/watchdog triggers share one recovery identity, one per-chat outbox transition, and no duplicate `/status` reply.
- Battery/thermal cause text is emitted only with credible platform evidence; unknown cause remains explicit. Severe thermal recovery delays optional high-load sources without fabricating a healthy state.
- Delivery readiness distinguishes a validated cellular-bound Telegram path from site Wi-Fi-only and offline/unknown states without blocking degraded arming; critical SMS fallback eligibility is tested as a separate existing-policy projection.
- A power episode enqueues at most one durable outbox item for each semantic transition: one-signal health opening, condition change, confirmed-outage opening/escalation, one-signal recovery when no escalation occurred, and final confirmed close. Repeated samples and callback ordering cannot enqueue another item; ambiguous transport may create the single labeled safety retry defined above. Only a confirmed outage uses incident progress/continuation delivery.
- An uncertain confirmed opening that partially or fully recovers before the 30-second safety-retry deadline supersedes that retry and emits only the appropriate uncertainty-referencing update/settlement for the current episode state.
- Disconnected charging sets degraded health but does not disarm Vehicle Guard or Entry Guard.
- Under determinate transport outcomes, each event enqueues one intended owner-message transition and accepted receipts suppress retry duplicates. Tests inject the post-accept/pre-receipt ambiguity separately and expect `DELIVERY_UNCERTAIN` plus the documented policy, not an impossible exactly-once assertion. Progress edits never alter a Live Map message.

### Device acceptance

- Vehicle: arm, calibration, real movement, map start, and clean closure.
- Entry: two-cycle commissioning followed by 20 repeated open/close cycles, slow opening, off-axis phone/mount movement, orientation-source loss before/during an open episode, 5-second valid source recovery without a false mount claim, forced recommissioning only for mount/fingerprint failure, and a metal-frame location. Verify that invalid evidence never produces a false door-closed message.
- Power: verify lamp off/on commissioning, immediate first Arm reuse within the 10-minute uninterrupted-flow rule, a later per-arm integrity challenge, skipped challenge yielding `Armed Degraded`, a stable hood baseline, gradual dimming, a shifted hood, stale sensor samples, charger-only loss, witness-only loss, dual loss, single-signal crossover, transient abnormality with no orphan recovery, partial recovery, and complete recovery.
- Delivery/recovery: repeat all three profiles with process kill, sticky null intent, duplicate boot/watchdog triggers, package replacement, owner Stop/Disarm races, offline-at-boot then online, healthy/degraded/open-episode states, and post-accept/pre-receipt ambiguity. Check exact recovery, heartbeat, `/status`, incident-text, and Live Location counts by their separate owners.
- Huawei no-PIN reboot: with a valid armed snapshot, reboot the real device and verify automatic resume, one terminal recovery Telegram message, no immediate duplicate heartbeat, and one current protection status. Repeat with network unavailable at boot and restored later.
- Screen-lock transition: add a PIN/Pattern/Password while armed, refresh readiness, verify current detection remains active with scoped `Armed Degraded` copy and one warning; remove it, verify encrypted-store/Telegram readiness, settle the same health episode, and re-verify the next controlled reboot. Inject key invalidation and expect visible recovery blocking/re-pair guidance rather than a false Ready state.
- Force-stop is reported truthfully as not self-recovering. A controlled power-off/manual or verified OEM power-on test is recorded separately from a normal Android reboot and never inferred from it.
- Battery-low and thermal paths use safe OS overrides or injected fakes where possible; acceptance must not deliberately overheat hardware or require destructive battery exhaustion.
- Paper-light UI is checked at small/large display sizes, portrait/landscape, 200% font scale, and TalkBack for profile identity, scoped current-versus-boot status, informed no-PIN consent, secure-lock actions, single-banner/live-region precedence, semantic event terminology, complete pressed/focus/disabled/system-bar states, and 48 dp targets.
- Battery, thermal state, background survival, reboot recovery, OEM auto-start, and permission-denied evidence are recorded separately from host test results.

## 9. Out of scope for this release

- External control boxes, USB data transport, BLE/Wi-Fi sensors, smart plugs, temperature probes, water-level sensors, and pump control.
- Claiming a building-wide mains outage, freezer temperature, pond water condition, or compressor/pump state from the phone and witness lamp.
- Changing current Telegram authorization, encryption, SMS policy, or GPS privacy boundaries.
- Bypassing PIN/Pattern/Password, running full sensor/Telegram protection with Direct Boot before credential unlock, or moving bot tokens/TOTP/SMS keys into device-protected storage.
- Guaranteeing that a powered-off phone will start from charger insertion, an RTC schedule, battery recovery, or thermal recovery on every Android/OEM model.

## 10. Approval checklist

Before implementation, confirm:

1. Product names: `Vehicle Guard`, `Entry Guard`, and `Power Guard`.
2. Entry control: default 15°, quick choices 5°/15°/30°, exact range 5°–90°; open for 750 ms and close below 3° for 5 s.
3. The owner may always select another use; an armed switch is confirmed, disarms, preserves all profile-specific settings, calibrates, and requires explicit re-arm.
4. Power default: both charging and witness light absent for 10 s; both stable again for 30 s.
5. The witness lamp is a low-heat LED on the same monitored outlet/power strip as the phone charger, placed in a shaded hood over the phone light sensor.
6. Entry and Power profiles have no Live Map by default.
7. Power Guard may confirm loss only for the monitored shared charging setup, never a wider building, freezer, or pond condition.
8. Armed profile/configuration/calibration are immutable until controlled disarm; failed profile setup leaves the app disarmed and not ready.
9. Entry uses two-cycle hinge-axis/direction commissioning, a frozen 0°–180° relative baseline, recoverable source-unavailable semantics, and separate recommission-required mount-movement semantics.
10. One power abnormality owns one durable episode and durable outbox transition sequence through escalation, partial recovery, and close; Telegram's ambiguous-acceptance window is surfaced honestly and uses at most one labeled safety retry for confirmed loss.
11. Power Guard exposes whether Telegram was validated over a bound cellular route, site-Wi-Fi-only, offline, or unknown; critical SMS fallback eligibility is separate, and non-independent Telegram delivery is `Armed Degraded`, not a blocked arm.
12. Full Power readiness requires a lamp off/on challenge for each Arm. The successful commissioning challenge may satisfy only the immediately following first Arm within 10 minutes and the same uninterrupted setup; skipping a later challenge is allowed as `Armed Degraded`.
13. Independent Telegram readiness expires after 24 hours or a tested cellular-transport change/failure. Testing is explicit and route-bound; the app does not send repeated background test messages.
14. Automatic recovery is shared by all three profiles and resumes durable `RUNNING + ARMED` owner intent after Android boots successfully only when the boot-recovery switch is enabled; owner Stop/Disarm always wins, while same-boot process/package recovery remains a baseline.
15. Full unattended recovery in this release requires no PIN, Pattern, or Password. The UI shows the approved short explanation and security trade-off; Direct Boot and secret migration are out of scope.
16. The setting is named **กลับมาป้องกันอัตโนมัติเมื่อเครื่องเปิดอีกครั้ง** and never claims the app can power on a fully powered-off phone. Reboot verification and hardware/OEM power-on verification remain separate.
17. Recovery has one durable event/outbox identity per chat, blocks heartbeat until terminal state, waits a full heartbeat interval afterward, and preserves the separate owners of incident text and Vehicle Live Location.
18. The main UI uses the approved paper-light hierarchy and complete state palette, keeps **ปกป้อง · เหตุการณ์ · ตั้งค่า**, supports profile-specific summaries, separates current protection from boot readiness, and passes 200% font scale, landscape, TalkBack, single-live-region, and 48 dp acceptance.
