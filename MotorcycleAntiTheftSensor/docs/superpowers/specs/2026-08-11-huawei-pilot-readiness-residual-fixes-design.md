# Huawei Pilot Readiness Residual Fixes Design

Date: 2026-08-11
Status: Approved for implementation

## Goal

Close the two remaining safety-critical ordering races and four status-truth defects so the application can proceed to controlled pilot acceptance on the Huawei test device. This work preserves `ProtectionCoordinator` as the single authoritative state owner and does not broaden the product into a production/customer release.

## Scope

This design covers:

1. Recovery supersession when an explicit owner command arrives.
2. Ordered, revision-aware persistence of protection state.
3. Accurate health after recovery from `OFFLINE`.
4. Accurate microphone readiness and runtime health.
5. Separation of battery status from thermal incident evidence.
6. Source-specific persistence degradation and recovery.
7. Host verification and controlled Huawei pilot acceptance.

It does not introduce a backend, add third-party dependencies, restructure unrelated modules, send a real SMS, or implement emergency calling.

## Authoritative ordering design

### Protection revisions

Every authoritative transition published by `ProtectionCoordinator` carries a monotonically increasing revision. A later revision is always more authoritative than an earlier revision, independent of coroutine completion order.

Health-only timestamp refreshes may reuse the current transition revision when they do not change protection meaning. State, blocker, degradation, channel, sensor-health, incident, and delivery changes that affect persisted truth receive a new revision.

### Single persistence arbiter

All protection-state persistence goes through one process-wide arbiter shared by `ProtectionCoordinator` and `SensorService`. No caller writes `ProtectionSnapshotStore` or the compatibility armed flag directly.

The arbiter:

- serializes writes with one coroutine-safe critical section;
- remembers the highest successfully committed revision;
- rejects a request whose revision is older than the highest committed revision;
- writes the compatibility armed flag and recovery snapshot in the same serialized operation;
- reports success only after synchronous persistence completes;
- reports failure without advancing the committed revision.

An ordinary service projection may request persistence asynchronously. A Disarm command submits its snapshot and awaits a durable barrier for that exact revision before returning `APPLIED`. Therefore an older Armed write cannot finish after and invalidate an acknowledged Disarm.

The recovery snapshot remains the authoritative restart input. The compatibility armed flag exists only for legacy consumers and must not override the recovery snapshot.

## Recovery supersession design

Recovery captures a generation token before processing persisted hints. Every explicit local or Telegram protection command invalidates the current recovery generation immediately.

Recovery Arm or Disarm submits the captured token to `ProtectionCoordinator`. The coordinator checks the token inside the same command critical section that begins the transition. A stale token is rejected before detector startup or state publication.

Owner commands always outrank recovery:

- Disarm invalidates recovery before waiting for any command lock.
- Arm invalidates recovery before readiness evaluation.
- A recovery operation already in the arming grace period rechecks its generation token before final publication; if stale, it stops detectors and returns to Disarmed.
- A recovery operation that has not entered the coordinator cannot pass a stale-token check later.

This closes the gap between a service-level `shouldApplyRecovery()` check and a later coordinator call.

## Truthful health and evidence

### Offline recovery

Freshness evaluation first resolves the intended live state saved before `OFFLINE`, then calculates channel and sensor degradations against that live state. If the restored state is armed and Telegram is stale, the first recovered snapshot is `ARMED_DEGRADED`; it may not transiently publish `ARMED_HEALTHY`.

### Microphone

Readiness distinguishes hardware/permission availability from detector startup. A present microphone with granted permission is available and is not pre-labelled as requiring a user-visible start. Detector startup reports whether audio capture actually began. After startup, missing or stale samples may degrade health; a successful start plus a fresh valid sample clears the microphone degradation.

### Battery and temperature

Battery percentage is status telemetry only. It updates `ProtectionSnapshot.batteryLevelPercent` but never enters `IncidentEngine` as `POWER_THERMAL` evidence.

Temperature and charger connection changes remain typed protection observations. Only a `temperature_celsius` diagnostic can be formatted as degrees Celsius. A battery percentage can never be rendered as temperature evidence.

### Persistence sources

Persistence health tracks at least two independent sources:

- `SNAPSHOT`: protection recovery snapshot and compatibility armed flag;
- `INCIDENT_HISTORY`: incident lifecycle and delivery history.

A successful snapshot write clears only `SNAPSHOT`. A successful incident-history write clears only `INCIDENT_HISTORY`. The authoritative degradation remains present while either source is unavailable.

If Disarm stops detectors and durably saves the Disarmed snapshot but incident-close history cannot be saved, the command returns an unknown/partial result stating that protection stopped but incident history was not confirmed. It must not claim complete success.

## Failure handling

- Stale recovery tokens are rejected without starting detectors.
- Stale persistence revisions are skipped and treated as superseded, not as failures.
- A failed durable Disarm write keeps detectors stopped, exposes snapshot persistence degradation, and returns `UNKNOWN`.
- An incident-history failure cannot be cleared by snapshot activity.
- Cancellation propagates through suspend boundaries; it is not converted into a persistence or detector failure.
- Logs use categorized messages without Telegram tokens, OTP values, SMS destinations, or raw request URLs.
- Monitoring may remain active through non-critical history failure when safe, but status must name the degraded storage source.

## TDD and host verification

Each behavior is implemented with a deterministic RED/GREEN cycle. Required regressions include:

1. Explicit Disarm supersedes recovery after token capture but before recovery Arm enters the coordinator.
2. Explicit Arm also invalidates an older recovery token.
3. An older Armed persistence request cannot overwrite a later acknowledged Disarmed revision.
4. Disarm waits for both compatibility-state and recovery-snapshot persistence through the shared arbiter.
5. Armed `OFFLINE` recovery with stale Telegram immediately yields `ARMED_DEGRADED`.
6. Successful microphone startup does not retain the pre-start degradation; failed startup does.
7. Battery percentage updates status without entering incident evidence or temperature formatting.
8. Snapshot recovery cannot clear an incident-history failure, and vice versa.
9. Incident-close persistence failure prevents a complete-success Disarm result.

After focused tests, run the complete low-memory host gate with Android Studio JBR, no daemon, one worker, and in-process Kotlin compilation:

- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- `:app:compileDebugAndroidTestKotlin`
- `git diff --check`
- credential-shaped secret scan and raw token-bearing logging scan

Host success authorizes preparation for device acceptance; it is not device evidence.

## Huawei pilot acceptance

Device acceptance begins only after the host gate and scoped review pass and the user separately authorizes the connected-device run.

The Huawei test boundary is:

- real Telegram messages may be sent only to the paired owner;
- `SEND_SMS` remains denied or revoked for the acceptance run;
- no real SMS is sent;
- no emergency-call path is invoked;
- no credential value is printed, captured in screenshots, or written to the checkpoint.

The matrix covers:

1. Fresh install/upgrade launch and permission presentation.
2. Pairing, mandatory TOTP readiness, and masked token presentation.
3. Local Arm, 10-second grace, cancellation during grace, and Disarm.
4. Telegram `/status`, `/arm`, and authorized `/disarm` with matching UI state.
5. Restart during Disarmed and Armed states, plus controlled process recovery.
6. `OFFLINE` to online recovery with truthful Telegram degradation.
7. Controlled vibration, light/tamper correlation, charger, temperature, microphone, and battery status evidence.
8. Populated Events history and delivery truth.
9. Foreground notification and TalkBack labels matching authoritative state.
10. Confirmation that no SMS attempt or emergency call occurs.

## Acceptance criteria

- No stale recovery operation can Arm after a newer explicit owner command.
- No older persistence write can overwrite an acknowledged Disarm.
- Local UI, notification, persisted recovery state, and Telegram report the same authoritative protection state.
- Telegram and microphone health do not transiently claim a healthier state than evidence supports.
- Battery percentage is never formatted as temperature or incident evidence.
- Snapshot and incident-history failures recover independently.
- All required host checks pass from a fresh invocation.
- The controlled Huawei matrix passes with paired-owner Telegram evidence and zero SMS/call attempts.
- The final handoff separates host evidence from device evidence and contains no credentials.
