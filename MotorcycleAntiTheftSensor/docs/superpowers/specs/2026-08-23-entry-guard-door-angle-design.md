# Entry Guard Door-Angle Detection Design

**Status:** Approved for staged TDD implementation on 2026-08-23
**Date:** 2026-08-23
**Parent spec:** `docs/superpowers/specs/2026-08-22-mobile-only-three-protection-profiles-design.md` (section 4.2)
**Predecessor:** Three Protection Profiles Foundation (commits `0adff63` … `a51c907`, foundation checkpoint `plans/checkpoint-2026-08-22-three-profiles-foundation.md`)
**Scope:** Implement the Entry Guard detector slice only: hinge-axis commissioning, door-angle math, door episode lifecycle, Entry health episodes, Entry-specific delivery text, commissioning UI, and device acceptance. Power Guard, recovery/readiness, and paper-light completion remain in their own later plans.

## 1. Goal

Flip `ProtectionProfile.ENTRY` from the truthful foundation placeholder (`SETUP_REQUIRED`) to a real detector that can reach `READY` and arm:

> Alert when a mounted phone detects that the protected door has opened beyond a chosen angle or has sustained a disturbance.

Everything in this plan is derived from parent-spec section 4.2; no behavior is invented here.

## 2. Customer-facing terminology

- The card/feature name is **เข็มทิศประตู** (door compass).
- The primary control reads **แจ้งเมื่อประตูเปิดเกิน N° จากตำแหน่งปิด**.
- The angle always means "angle opened from the calibrated closed position" — never a compass bearing and never magnetic-field strength in microtesla.
- Magnetic-field evidence stays supporting-only; it can never alone produce `DOOR_OPEN`.

## 3. Domain additions

All new types live beside the existing profile models in `protection/`. They extend — never replace — the foundation aggregate.

```text
EntryOrientationSample        timestamped rotation source reading + quality
EntryHingeModel               learned axis (unit vector), allowed opening direction,
                              residual tolerance, algorithm version, sensor identity,
                              mount/orientation signature
EntryCommissioningState       idle → still-check(5 s) → cycle1 → cycle2 → commissioned
EntryDoorEpisode              openedAtMs, peakAngleDegrees, lifecycle state
EntryHealthEpisodeKind        SOURCE_UNAVAILABLE | MOUNT_MOVED
EntryDetectionVerdict         DOOR_OPENED(angle) | DOOR_STILL_OPEN(angle) |
                              DOOR_CLOSED_CONFIRMED | SOURCE_UNAVAILABLE |
                              MOUNT_MOVED | EVIDENCE_REJECTED(reason)
```

Existing types consumed unchanged: `EntryProfileSettings`, `EntryProfileOverrides`, `EntryArmedCalibrationSnapshot(generation, modelFingerprint)`, `ProfileSetupState`, `StoredProfileConfiguration`, `ResolvedProfileConfiguration`, `ArmedProfileSnapshot`.

## 4. Door-angle mathematics (pure domain)

- Orientation sources publish quaternions `(w, x, y, z)` with timestamps and a freshness/quality flag.
- Normalize every quaternion; `q` and `-q` are sign-equivalent and must map to the same orientation (canonicalize by forcing `w >= 0` before comparison).
- Relative rotation from baseline: `q_rel = q_baseline⁻¹ ⊗ q_current`.
- Decompose `q_rel` into twist around the learned hinge axis `a` plus swing/residual:
  - projection component `p = (q_rel · a_axis_part) a_axis_part`; twist angle = `2·atan2(|p|, q_rel.w)` signed by direction;
  - `door_angle_delta_deg = |twist|` clamped to `0°–180°`.
- Cross-axis swing/residual is computed separately and gated before thresholds (section 6).
- No heading wrap exists: there is no 0°/360° discontinuity anywhere in this math.
- Floating-point comparisons use small epsilon tolerances; tests pin exact expected values for known rotations.

## 5. Commissioning contract

Initial commissioning (and any recommissioning after fingerprint invalidation):

1. **Still check:** door closed, phone still for five continuous seconds. Movement during the window restarts it.
2. **Cycle 1:** guided open to at least the selected alert angle, then close below the close threshold.
3. **Cycle 2:** repeat. Both cycles must agree on hinge axis (angle between axes within tolerance), allowed opening direction, and closed-position return.
4. On success, persist one `EntryHingeModel` whose **model fingerprint** covers: phone sensor identity, mount/orientation signature, learned axis + allowed direction, orientation-source/fusion policy, commissioning algorithm version, and continuity of Entry use.
5. Store the model inside the credential-protected profile store as part of the Entry configuration (never device-protected storage).

Fingerprint invalidation requires recommissioning before Entry can be `READY` again:

- leaving Entry for another profile then returning;
- moving/remounting the phone (mount signature change);
- changing an orientation-source/fusion field or relevant sensor identity;
- upgrading the commissioning algorithm version.

Changing only the alert angle, confirmation time, or notification preference does **not** invalidate the model.

## 6. Detection contract (armed session)

On every Arm with Entry selected:

1. Owner closes the door; app holds a five-second armed-session calibration (same UX as Vehicle's calibration window).
2. The detector records a fresh relative-orientation baseline, validates the saved hinge model and source quality, then evaluates samples.
3. Baseline and learned axis are frozen for the whole armed session. **No auto-rebaseline** from slow or sustained movement while armed.

Strict evaluation order per sample — a sample failing an earlier gate cannot produce `DOOR_OPEN` regardless of its total rotation:

1. Source freshness/quality gate (stale/unavailable → `SOURCE_UNAVAILABLE` path).
2. Hinge-axis residual gate (swing above tolerance → `MOUNT_MOVED` path).
3. Allowed-opening-direction gate (opposite-direction motion → `MOUNT_MOVED` path).
4. Customer angle threshold + confirmation time.

Thresholds (from `EntryProfileSettings`, defaults preserved):

| Parameter | Default | Range |
|---|---|---|
| Open trigger angle | 15° | 5°–90°, 1° steps |
| Open confirmation time | 750 ms | 250 ms–3 s |
| Close threshold | 3° | fixed hysteresis |
| Close confirmation | 5 s | fixed |

## 7. Episode lifecycle

### 7.1 Door episode

- One physical opening creates exactly one `DOOR_OPEN` episode keyed by `armedSessionId + doorEpisodeId`. Later angle samples update the same event/message; they never enqueue repeated alerts.
- Close condition: below 3° and stable for 5 s → `DOOR_CLOSED` / `ประตูปิดและนิ่งแล้ว`, episode resolved.
- If orientation becomes stale, off-axis, or mount-invalid while a door episode is open, mark it `EVIDENCE_INTERRUPTED` and retain it unresolved. Invalid evidence must never synthesize `DOOR_CLOSED` or send `ประตูปิดแล้ว`.
- A door episode may resume and close only when the same compatible mount model produces fresh valid below-3° evidence continuously for 5 s.
- If the mount fingerprint was invalidated, owner disarm/recommissioning stops the episode with the explicit owner-stop copy `หยุดการเฝ้าระวัง—หลักฐานตำแหน่งประตูขาดหาย` — not an automatic door-closed result.

### 7.2 Health episodes (deduplicated)

| Kind | Trigger | Readiness effect | Recovery |
|---|---|---|---|
| `ENTRY_SOURCE_UNAVAILABLE` | stale/unavailable orientation source | Entry readiness `Degraded`; no claim that phone/mount moved | fresh compatible source/quality evidence continuous for 5 s |
| `ENTRY_MOUNT_MOVED` | off-axis/opposite-direction motion or mount/fingerprint inconsistency | Entry readiness `Degraded` until controlled recommissioning | controlled disarm + recommissioning only |

- `ENTRY_MOUNT_MOVED` has priority over `DOOR_OPEN`: off-axis movement reports `โทรศัพท์หรือขายึดถูกขยับ`, never proof of an open door.
- Repeated samples inside one episode never enqueue repeated Telegram transitions.

## 8. Delivery text (Thai, professional, no decorative emoji)

| Situation | Message |
|---|---|
| Door opened | `ประตูเปิด {N}° จากตำแหน่งปิด` |
| Door closed | `ประตูปิดและนิ่งแล้ว` |
| Source dropout | `ข้อมูลมุมประตูขาดหาย กำลังรอเซนเซอร์กลับมาทำงาน` |
| Mount moved | `โทรศัพท์หรือขายึดถูกขยับ กรุณาตรวจสอบและปรับเทียบใหม่` |
| Confirmed impact (supporting evidence independently confirmed) | `ตรวจพบแรงกระแทกที่ประตู` |
| Evidence-interrupted stop by owner | `หยุดการเฝ้าระวัง—หลักฐานตำแหน่งประตูขาดหาย` |

Delivery ownership follows the existing incident pipeline (`IncidentEngine` → `IncidentMessageFormatter`); Entry adds typed message rendering but creates no second delivery owner.

## 9. UI contract

- Commissioning screen shows the live relative angle starting near `0°` while closed, guides the two cycles, and states installation constraints (firm mount; large metal doors, magnets, or moving the phone degrade compass-derived evidence).
- Normal Entry settings always show **มุมเปิดประตูที่ต้องการแจ้งเตือน** (not hidden in Advanced): quick choices 5°/15°/30°, slider + numeric entry 5°–90° in 1° steps.
- Changing the angle while disarmed takes effect next arm. While armed, the app directs the owner through controlled disarm/calibrate/re-arm so a changed threshold cannot surprise-alert from an old baseline.
- Protection summary for Entry shows the calibrated closed position and selected angle, e.g. `ประตูปิด · 0°` and `แจ้งเมื่อเกิน 15°`.
- GPS/Live Map stay off and unavailable in this profile. Vibration/light/proximity/audio may be enabled as supporting evidence but are never described as proof the door is open.
- Paper-light tokens, 48 dp targets, TalkBack semantics, and single-banner precedence follow parent spec section 3.6 unchanged.

## 10. Persistence boundaries

- Commissioned hinge model, per-profile overrides, setup state, and armed snapshots remain in credential-protected storage via the existing profile store codec (schema evolves additively; old payloads migrate).
- Device-protected Direct Boot storage keeps only the existing two booleans; this plan does not expand it.
- Raw orientation streams and unfinished debounce progress are never persisted.

## 11. Verification plan

Host (JUnit, pure domain first):

- Quaternion normalization/sign-equivalence; relative rotation; twist decomposition returns expected angles for pinned rotations; result always 0–180; no heading wrap.
- Two-cycle commissioning accepts consistent cycles and rejects mismatched axis/direction/closed-return; fingerprint covers all required fields; invalidation matrix behaves as section 5.
- Evaluation order: stale source, off-axis swing, wrong direction each block `DOOR_OPEN` even with large total rotation; magnetic-only interference cannot produce `DOOR_OPEN`.
- Threshold/hysteresis: open at ≥ angle for confirmation ms; close below 3° stable 5 s; one episode per physical opening; updates mutate one event.
- Evidence interruption: invalid evidence mid-open marks `EVIDENCE_INTERRUPTED`, never synthesizes close; valid same-model recovery closes after 5 s; fingerprint-invalidated stop emits owner-stop copy.
- Health episodes deduplicate; `MOUNT_MOVED` outranks `DOOR_OPEN`; 5-second recovery windows restart from zero after generation change.
- Armed snapshot freezes baseline/model; Settings edits and late callbacks cannot mutate it; disarm/re-arm re-baselines.

Device acceptance (Huawei INE-LX2, owner-approved):

1. Two-cycle commissioning end-to-end, then 20 repeated open/close cycles with correct counts.
2. Slow opening below threshold does not alert; crossing threshold alerts once.
3. Off-axis phone/mount movement reports mount-moved, not door-open.
4. Orientation-source loss before/during an open episode; 5-second valid recovery without false mount claim; forced recommissioning only for mount failure.
5. Metal-frame location smoke test.
6. Invalid evidence never produces a false door-closed message.
7. Full host suite green; APK built, installed, hash recorded; screenshots under `plans/entrytask*-*.png`.

## 12. Out of scope for this plan

- Power Guard detectors/commissioning/outbox (own plan).
- Recovery/readiness truth, delivery-path readiness, TalkBack/large-font/device matrix completion (own plan).
- Profile-scoped Settings grouping reorder (deferred by Task 7 decision).
- Any Telegram/SMS authorization change; GPS/Live Map for Entry; remote profile switching.
