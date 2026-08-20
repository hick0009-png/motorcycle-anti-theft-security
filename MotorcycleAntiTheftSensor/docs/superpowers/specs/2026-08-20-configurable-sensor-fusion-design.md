# Configurable Multi-Sensor Fusion Design

Date: 2026-08-20  
Status: Approved in chat; pending written-spec review  
Project: MotorcycleAntiTheftSensor

## 1. Goal

Extend the existing authoritative protection system so every supported Android sensor is connected to a real runtime path and can be configured from Settings. The user can disable a sensor, use it only as supporting evidence, or allow it to initiate an incident. The system must apply changes to real listeners, report effective runtime health, calibrate safely, minimize battery use, and never claim protection when no trigger-capable sensor is operating.

This design replaces the current global vibration-only sensitivity model with typed, per-sensor configuration while preserving `ProtectionCoordinator` as the authoritative state owner.

## 2. Approved product decisions

The user approved these decisions:

1. Every supported sensor can be assigned one of three roles: `OFF`, `SUPPORTING`, or `PRIMARY`.
2. Settings uses a mixed layout: simple capability-group controls on the main page and per-Android-sensor controls on an advanced page.
3. Arming requires at least one configured `PRIMARY` sensor that is present, registered, and ready. Otherwise the arm request is rejected.
4. A missing or failed `SUPPORTING` sensor does not block arming, but produces `ARMED_DEGRADED`.
5. The default profile is `BALANCED`:
   - movement: primary;
   - rotation: supporting;
   - magnetic field: supporting;
   - ambient light: primary;
   - proximity: supporting.
6. Sensitivity is adjustable from 1 to 10 per capability group. Advanced Settings exposes bounded threshold, debounce, correlation-window, and sampling controls.
7. Configuration changes made while armed take effect immediately. Only affected sensor groups restart and recalibrate; unaffected groups keep protecting.
8. Every arm operation performs a 10-second calibration/readiness phase for all enabled sensors. A live configuration change recalibrates only the affected group.
9. Sampling is adaptive: low-power wake/monitor sensors run during steady state, and higher-rate motion/rotation/magnetic sensors activate temporarily to confirm candidate events.
10. Continuous sensors use baseline-relative detection with absolute sanity limits. Trigger and virtual sensors use the readiness validation appropriate to their Android API.

## 3. Non-goals

This work will not:

- introduce a backend service or a new third-party sensor library;
- replace `ProtectionCoordinator`, the incident pipeline, or the foreground-service architecture;
- expose unbounded raw sensor parameters that can disable debounce or cause an alert loop;
- use deprecated `Sensor.TYPE_ORIENTATION`; orientation angles will be derived from supported rotation-vector sensors;
- claim that requested sampling rate equals hardware-delivered sampling rate;
- treat hardware presence as proof that a listener is registered or producing fresh samples;
- persist calibration baselines across disarm, process restart, reboot, or a new armed session;
- send independent Telegram/SMS messages directly from sensor listeners;
- allow a supporting sensor to create a critical incident by itself;
- weaken existing Demo Mode, SMS, Telegram ownership, or incident-delivery safety boundaries.

## 4. Sensor model

### 4.1 Capability groups

The product presents five user-facing groups:

| Capability group | Android sensor sources | Primary purpose |
| --- | --- | --- |
| Movement | `TYPE_SIGNIFICANT_MOTION`, `TYPE_ACCELEROMETER`, `TYPE_LINEAR_ACCELERATION` | Wake-up, vibration, translation, towing or lifting candidate |
| Rotation | `TYPE_GYROSCOPE`, `TYPE_ROTATION_VECTOR`, `TYPE_GAME_ROTATION_VECTOR` | Angular velocity and orientation change |
| Magnetic | `TYPE_MAGNETIC_FIELD`, `TYPE_GEOMAGNETIC_ROTATION_VECTOR` | Magnetic disturbance and north-referenced orientation change |
| Light | `TYPE_LIGHT` | Under-seat or enclosure opening |
| Proximity | `TYPE_PROXIMITY` | Near/far state change around the installed phone |

`TYPE_ORIENTATION` is deliberately excluded because Android deprecates it. When azimuth, pitch, or roll is needed, the runtime derives those values from rotation-vector data.

### 4.2 Canonical role

Every individual Android sensor source has a persisted `SensorRole`:

- `OFF`: the runtime does not register or request the sensor. It is not a degradation reason.
- `SUPPORTING`: the sensor contributes evidence, confidence, or severity to an existing candidate/incident, but cannot initiate an incident.
- `PRIMARY`: the sensor can initiate a candidate after its own validity, threshold, duration, and debounce rules pass.

The individual source role is canonical. The capability-group card is a simple bulk control and summary:

- selecting a group preset applies the approved bundle of source roles for that group;
- if advanced per-source roles differ from the bundle, the group displays `CUSTOM`;
- changing a group role never silently overwrites advanced values without showing the resulting source-role summary before Save/Apply.

### 4.3 Default `BALANCED` profile

The default source-role bundle is:

| Source | Default role | Notes |
| --- | --- | --- |
| Significant Motion | `SUPPORTING` | Low-power one-shot wake trigger; re-request after every trigger |
| Accelerometer | `PRIMARY` | Required only when it is the sole configured primary source |
| Linear Acceleration | `SUPPORTING` | Confirms translation while reducing gravity influence |
| Gyroscope | `SUPPORTING` | Confirms rapid rotation |
| Rotation Vector | `SUPPORTING` | Confirms 3D orientation change |
| Game Rotation Vector | `SUPPORTING` | Fallback for relative rotation without north reference |
| Magnetic Field | `SUPPORTING` | Detects baseline-relative field disturbance |
| Geomagnetic Rotation Vector | `SUPPORTING` | Low-power orientation source when available |
| Ambient Light | `PRIMARY` | Detects a sustained baseline-relative light increase |
| Proximity | `SUPPORTING` | Confirms enclosure/phone exposure through stable near/far transition |

Unavailable sources retain their configured role but expose unavailable effective state. A user selection is desired configuration; runtime health is the source of truth for what is actually operating.

## 5. Configuration model

The implementation introduces versioned, immutable configuration models conceptually equivalent to:

- `SensorFusionConfiguration`
  - schema version;
  - selected preset: `BATTERY_SAVER`, `BALANCED`, `MAXIMUM_PROTECTION`, or `CUSTOM`;
  - per-capability configuration;
  - adaptive sampling profile;
  - last successful update timestamp.
- `SensorCapabilityConfiguration`
  - capability identifier;
  - sensitivity from 1 through 10;
  - per-source configuration;
  - correlation window;
  - confirmation duration;
  - whether advanced overrides are active.
- `SensorSourceConfiguration`
  - Android sensor type;
  - role;
  - optional bounded threshold override;
  - optional bounded debounce override;
  - sampling policy.

All values are validated through one pure `SensorConfigurationPolicy`. UI, persistence, Telegram, and runtime must use the same policy. The UI must not implement a second set of ranges.

### 5.1 Sensitivity mapping

The 1-10 value is an input to a deterministic policy, not a decorative label:

- lower sensitivity increases the required baseline delta and/or confirmation duration;
- higher sensitivity decreases the required delta within a safe lower bound;
- no level removes finite-value checks, accuracy checks, debounce, cooldown, or incident deduplication;
- an advanced override replaces only the named policy value and leaves other safety rules active;
- Reset restores the active preset's policy values and clears overrides.

Thresholds remain type-safe and unit-labelled: acceleration in `m/s²`, angular velocity in `rad/s`, orientation in degrees, magnetic field in `µT`, light in `lux` or baseline-relative ratio, and proximity as a normalized near/far transition.

### 5.2 Persistence and migration

The versioned configuration is stored through the existing encrypted preferences boundary. The current single `sensor_sensitivity_level` value migrates as follows:

1. If no new configuration exists, create the `BALANCED` profile.
2. Copy the legacy 1-10 sensitivity into the Movement and Light group sensitivities.
3. Use approved default sensitivity for Rotation, Magnetic, and Proximity.
4. Persist the new schema only after validation succeeds.
5. Keep the legacy value readable for one migration version but stop writing it after migration.
6. If parsing or validation fails, use `BALANCED`, record a non-secret diagnostic code, and show that defaults were restored.

Calibration samples and baselines are never persisted.

## 6. Architecture

### 6.1 Components

The design adds or evolves these bounded components:

1. `AndroidSensorCatalog`
   - queries `SensorManager` for every supported source;
   - reports sensor type, name, vendor, reporting mode, wake-up capability, min/max delay, range, resolution, and power;
   - contains no listeners and produces no protection claims;
   - replaces the unused `SensorScanner` discovery responsibility.

2. `SensorConfigurationRepository`
   - loads, validates, migrates, and atomically stores versioned configuration;
   - does not own runtime listener state.

3. `SensorConfigurationPolicy`
   - maps presets and sensitivity to bounded technical parameters;
   - validates advanced overrides;
   - evaluates whether desired configuration contains at least one `PRIMARY` source.

4. `SensorCapabilityController`
   - owns the effective sensor configuration and detector lifecycle;
   - applies configuration diffs under one lifecycle lock;
   - starts, stops, and recalibrates only affected capability groups;
   - reports desired versus effective configuration and structured apply results;
   - never sends alerts directly.

5. Sensor adapters
   - continuous listener adapters for accelerometer, linear acceleration, gyroscope, rotation vectors, magnetic field, light, and proximity;
   - a trigger adapter using `requestTriggerSensor`/`cancelTriggerSensor` for Significant Motion;
   - each adapter converts Android events into typed raw samples and isolates registration failure.

6. `SensorCalibrationManager`
   - owns armed-session and per-group calibration generations;
   - builds baselines from valid samples;
   - rejects samples from previous generations;
   - publishes progress, completion, instability, timeout, and failure.

7. `SensorObservationNormalizer`
   - validates finite/ranged samples;
   - calculates baseline-relative deltas and derived orientation safely;
   - produces typed `SensorObservation` values with source metadata.

8. Existing `SensorObservationProcessor` and `IncidentEngine`
   - consume normalized observations;
   - apply source role, threshold, debounce, correlation, confidence, severity, and deduplication rules;
   - create or update one incident through the existing authoritative delivery path.

9. Existing `ProtectionCoordinator`
   - remains the only owner of protection state transitions;
   - validates arm eligibility against effective primary readiness;
   - exposes configuration apply results and unified sensor health to UI, service notification, and Telegram status.

### 6.2 Dependency direction

The dependency direction is:

`Settings UI -> ProtectionViewModel -> ProtectionCoordinator -> ProtectionRuntime -> SensorCapabilityController -> Sensor adapters`

Samples return through:

`Sensor adapters -> Calibration/Normalizer -> SensorObservationProcessor -> IncidentEngine -> ProtectionCoordinator snapshot/history/delivery`

Settings never creates a `SensorManager` listener. Sensor adapters never mutate UI state or call Telegram/SMS directly.

### 6.3 Replacing floating declarations

`SensorScanner.scanHardwareSensors()` is not retained as an uncalled helper. Its useful discovery behavior moves into `AndroidSensorCatalog`, which is injected into both readiness evaluation and Settings projection. Every sensor displayed as enabled has a traceable path to:

1. persisted desired configuration;
2. catalog availability;
3. adapter creation;
4. listener/trigger registration;
5. calibration/readiness;
6. fresh health and observation publication;
7. incident evidence when policy permits.

A source shown as `ACTIVE` must have registration evidence and, for continuous sensors, a fresh valid sample.

## 7. Runtime lifecycle

### 7.1 Arm sequence

Every local, Telegram, recovery, or reboot arm request follows the same sequence:

1. Load and validate desired sensor configuration.
2. Discover effective hardware availability.
3. Confirm at least one configured `PRIMARY` source is available for registration.
4. Enter `ARMING` and create a new armed-session calibration generation.
5. Register enabled sources according to adaptive sampling policy.
6. Calibrate or validate every enabled source for 10 seconds.
7. Suppress incident creation from a group while that group is calibrating; health samples still flow.
8. At the deadline, evaluate effective primary readiness.
9. Reject/roll back arm if no `PRIMARY` source is registered and ready.
10. Enter `ARMED_HEALTHY` when all enabled sources are healthy, or `ARMED_DEGRADED` when at least one primary is ready but an enabled source is unavailable, failed, stale, or uncalibrated.

An `OFF` source never causes degradation.

### 7.2 Calibration semantics by source

The UI uses the common phrase “กำลังปรับเทียบ”, but internal readiness differs:

- Accelerometer: establish a stable gravity/vector magnitude and noise envelope.
- Linear acceleration: establish the zero/noise envelope.
- Gyroscope: establish stationary angular-rate bias and noise envelope.
- Rotation vectors: capture a stable reference quaternion and acceptable accuracy.
- Magnetic field: capture vector magnitude/direction baseline and reject unreliable accuracy.
- Ambient light: capture a robust baseline suitable for dark and non-dark installations.
- Proximity: require a stable near/far state before accepting transitions.
- Significant Motion: verify trigger availability and successful request; it has no continuous baseline.

Continuous baselines use robust statistics so one spike does not define the baseline. If valid stable input is insufficient at the 10-second deadline, that source fails calibration. The system does not extend `ARMING` indefinitely.

### 7.3 Live configuration changes

Configuration changes while armed use a two-phase operation:

1. Validate the complete proposed configuration and calculate the affected groups.
2. Show a confirmation if the desired change can leave no effective primary source.
3. Persist the validated desired configuration atomically.
4. Apply a runtime diff under the detector lifecycle lock.
5. Stop removed adapters and invalidate their calibration generation.
6. Start or update affected adapters.
7. Create a new calibration generation only for affected groups.
8. Keep unaffected groups active and eligible to create incidents.
9. Publish a structured `APPLIED`, `APPLIED_DEGRADED`, or `REJECTED` result.

If the final effective configuration has no ready primary source, the coordinator must not remain in an armed state. It stops theft detectors, transitions to the appropriate non-armed/setup state, and displays a persistent reason. It must never silently preserve an obsolete listener that the user turned off.

### 7.4 Stop, disarm, restart, and reboot

- Disarm cancels triggers, unregisters all theft-sensor listeners, invalidates calibration generations, and leaves Telegram polling/service behavior unchanged.
- Process restart or reboot reloads desired configuration but discards baselines.
- Recovery from a persisted armed state re-enters `ARMING` and repeats the full 10-second calibration.
- No stale sample from a previous process, armed session, or per-group reconfiguration can create an incident.

## 8. Adaptive sampling

### 8.1 States

Each capability controller can be in:

- `OFF`;
- `WATCHING`;
- `CONFIRMING`;
- `CALIBRATING`;
- `FAILED`.

During steady-state protection:

- Significant Motion is requested when supported and enabled.
- Accelerometer/geomagnetic/light/proximity sources run at their bounded watch policy when their role requires continuous observation.
- Gyroscope and higher-rate rotation/magnetic confirmation sources remain stopped or at the lowest viable watch rate unless their own `PRIMARY` role requires continuous data.

A valid primary candidate or Significant Motion trigger starts a bounded confirmation window. Required confirmation adapters move to a higher requested rate on a background handler thread. When the window closes, they return to watch/off state. Significant Motion is requested again after every trigger and after every failed confirmation path.

### 8.2 Sampling profiles

The user-facing profiles are:

- `BATTERY_SAVER`;
- `BALANCED`;
- `RESPONSIVE`.

They map to bounded sampling-period and confirmation-window policies. The UI reports them as requested profiles, not guaranteed Hz. Actual event cadence is observed for diagnostics. The initial product does not expose unrestricted 250 Hz operation; a future change requires measured device evidence for battery, heat, and event-processing capacity.

No sensor callback performs network I/O, disk I/O, JSON serialization, or blocking work. Samples are reduced and forwarded through bounded background processing.

## 9. Observation and fusion rules

Every normalized observation includes:

- capability group;
- Android source type;
- source family identifying shared/derived physical data;
- role at observation time;
- monotonic event timestamp and wall-clock timestamp;
- armed-session identifier;
- calibration generation;
- normalized value and unit;
- baseline delta;
- accuracy/reliability;
- validity and bounded diagnostic code;
- candidate/correlation identifier when applicable.

### 9.1 Source-specific candidate behavior

- Movement primary: sustained acceleration/linear-acceleration delta creates a movement candidate.
- Significant Motion primary, if explicitly selected: the trigger creates a movement candidate but still passes cooldown and correlation policy.
- Rotation primary: sustained angular-rate or reference-orientation delta creates a rotation candidate.
- Magnetic primary: sustained baseline-relative field/orientation disturbance creates a magnetic candidate; unreliable accuracy cannot initiate one.
- Light primary: sustained baseline-relative increase or bounded absolute condition creates a light-tamper candidate.
- Proximity primary: a stable baseline-to-opposite-state transition creates a proximity candidate.

A single-source primary event initially produces at most a warning unless an existing explicitly approved incident rule assigns a higher severity. Supporting evidence within the correlation window can increase confidence or severity. Existing critical power-disconnect rules remain independent.

### 9.2 Deduplication

Accelerometer, linear acceleration, rotation-vector, and geomagnetic observations may derive from overlapping physical inputs. The processor groups observations by armed session, calibration generation, time window, and source family. One physical action updates one incident instead of producing parallel alerts.

Severity escalation may send one immediate update. Repeated non-escalating evidence updates the stored incident without alert spam. Existing Demo isolation and incident-level cooldown remain enforced.

## 10. Health and authoritative state

The health model gains explicit states sufficient to distinguish configuration and runtime:

- `DISABLED`: user selected `OFF`;
- `UNAVAILABLE`: configured but hardware/API support is absent;
- `AVAILABLE`: hardware exists but is not currently required or not yet producing ready data;
- `CALIBRATING`: registered/requested and gathering baseline/readiness evidence;
- `HEALTHY`: registered/requested, calibrated/ready, and fresh;
- `STALE`: expected data or trigger readiness is no longer fresh;
- `FAILED`: registration, calibration, or processing failed.

Health is reported per Android source and summarized per capability group. The summary cannot hide a failed primary behind a healthy supporting sensor.

Arm eligibility requires at least one source satisfying all of:

- configured as `PRIMARY`;
- hardware/API available;
- listener or trigger request succeeded;
- calibration/readiness completed;
- current sample/readiness freshness policy passes.

An enabled supporting source in `UNAVAILABLE`, `STALE`, or `FAILED` adds a named degradation. An `OFF` source does not.

## 11. Settings experience

### 11.1 Main sensor settings

The existing Settings destination gains a “เซ็นเซอร์ป้องกัน” section containing:

- preset selector: Battery Saver, Balanced, Maximum Protection, Custom;
- one card per capability group;
- aggregate role and 1-10 sensitivity;
- hardware/source count;
- effective status and last sample time;
- calibration progress;
- concise degradation reason;
- link to Advanced Settings.

Changing a value shows `กำลังใช้การตั้งค่า` until the coordinator returns an apply result. Success, degradation, and rejection use the existing immediate Snackbar plus persistent status-card pattern. A transient popup alone is insufficient.

### 11.2 Advanced sensor settings

Each capability section lists the supported Android sources and displays:

- hardware name and vendor when available;
- physical, virtual, wake-up, or trigger classification;
- role selector;
- effective listener/trigger state;
- sensitivity-derived threshold;
- bounded threshold override;
- debounce and confirmation duration;
- correlation window;
- sampling profile;
- latest valid reading and age;
- Reset group action.

Unavailable sensors remain visible but disabled with a reason. The UI must never imply that a source is monitoring merely because its switch is selected.

### 11.3 Dangerous changes

While armed, a change that can remove the last effective primary shows a confirmation explaining that protection will stop if no replacement primary becomes ready. If applied and no primary is ready, the resulting non-armed state and reason persist on Protection and Settings screens and are included in Telegram `/status`.

## 12. Error handling and concurrency

- All listener registration, trigger requests, and unregistration are idempotent.
- Lifecycle changes are serialized by a dedicated detector/configuration lock.
- Sensor callbacks run on dedicated background handlers and never block the main thread.
- Configuration generations reject callbacks racing with stop/restart.
- One adapter failure is isolated; other groups continue operating.
- Partial apply results name every failed source and show desired versus effective state.
- Persistence failure rejects a settings change before presenting it as saved.
- Runtime apply failure retains the validated desired configuration for retry but reports effective failure and reevaluates armed eligibility.
- Invalid or non-finite sensor values are dropped and counted through non-secret diagnostics.
- Repeated failures and stale-state notices are rate-limited.
- No raw sensor stream is written to logs, incident history, or Telegram; only bounded evidence summaries are retained.

## 13. Existing-code integration boundaries

The implementation will preserve and evolve these paths rather than create a parallel protection system:

- `protection/ProtectionCoordinator.kt`: authoritative state and commands;
- `protection/ProtectionRuntime.kt`: configuration-aware runtime interface;
- `protection/AndroidProtectionRuntime.kt`: Android wiring, narrowed through the new controller;
- `protection/ProtectionModels.kt`: capability/source configuration and health projection;
- `protection/SensorObservation.kt`: typed source and generation metadata;
- `protection/SensorObservationProcessor.kt`: role-aware candidate and fusion processing;
- `protection/IncidentEngine.kt`: deduplicated incident lifecycle;
- `sensor/VibrationDetector.kt` and `sensor/LightIntrusionDetector.kt`: migrate into or adapt to the common listener contract;
- `sensor/SensorScanner.kt`: replace with injected catalog usage, then remove only after every caller/test migrates;
- `data/EncryptedPrefsManager.kt`: versioned configuration persistence boundary;
- `ui/ProtectionViewModel.kt` and UI models: desired/effective configuration and operation state;
- `ui/settings/SettingsScreen.kt`: main group controls and navigation to advanced controls;
- Telegram status formatting: read the same authoritative sensor health and never a separate scan.

No public API is renamed or removed until compile-time callers and tests migrate in a bounded TDD task.

## 14. Delivery sequence

Implementation must be split into small TDD tasks in this order:

1. Pure configuration, role, preset, validation, and migration models.
2. Android sensor catalog and source metadata.
3. Configuration repository and legacy-sensitivity migration.
4. Common detector adapter contract and continuous/trigger test doubles.
5. Calibration generation and per-source readiness policies.
6. Movement adapters, including Significant Motion and existing accelerometer migration.
7. Rotation adapters.
8. Magnetic adapters.
9. Light and proximity adapters, including existing light migration.
10. Capability controller and adaptive sampling state machine.
11. Observation metadata, role-aware processing, fusion, and deduplication.
12. Runtime/coordinator configuration application and arm eligibility.
13. Health/state projection and Telegram status truth path.
14. Settings main-group controls.
15. Advanced per-source settings.
16. Persistence/restart/reboot recovery.
17. Full host verification and controlled real-device acceptance.

The later implementation plan must name exact files, RED tests, minimal GREEN production changes, focused commands, full gates, and rollback boundaries for every task.

## 15. Testing strategy

### 15.1 Pure unit tests

Cover:

- all role and preset mappings;
- group summary and `CUSTOM` detection;
- sensitivity-to-threshold monotonicity and safe bounds;
- advanced override validation;
- legacy migration and corrupt-data fallback;
- arm eligibility with every combination of role and health;
- baseline calculation, instability, timeout, and generation rejection;
- source-family deduplication;
- supporting sensors never initiating incidents;
- primary-source warning candidates and multi-source escalation;
- adaptive sampling state transitions;
- Significant Motion re-request behavior;
- partial configuration apply results;
- last-primary removal behavior;
- disabled sources not causing degradation.

### 15.2 Android/integration tests

Cover:

- catalog projection from fake and device `SensorManager` data;
- listener/trigger registration and idempotent cleanup;
- callbacks dispatched off the main thread;
- configuration diff starts/stops only affected adapters;
- unaffected groups remain active during per-group recalibration;
- process recovery discards baselines and re-enters `ARMING`;
- coordinator, service, Settings, notification, and Telegram consume the same snapshot;
- unavailable sensors remain visible with accurate reasons.

### 15.3 Compose tests

Cover:

- Balanced defaults;
- group bulk roles and custom summary;
- sensitivity sliders and accessibility semantics;
- advanced per-source roles and bounded inputs;
- calibration and live-apply progress;
- disabled/unavailable/failed distinctions;
- dangerous last-primary confirmation;
- Snackbar plus persistent result/degradation presentation;
- state restoration without leaking secrets or raw samples.

### 15.4 Real-device acceptance

Host tests and a successful APK build do not prove sensor behavior. Device acceptance requires a controlled test matrix on the target phone:

1. Record the discovered hardware name/vendor/type/reporting mode for every supported source without exposing secrets.
2. Verify each enabled source registers and produces fresh samples.
3. Verify every `OFF` source is unregistered and does not affect health or incidents.
4. Verify `SUPPORTING` sources cannot create an incident alone.
5. Verify each available `PRIMARY` source can create exactly one bounded candidate/incident under a controlled stimulus.
6. Verify 10-second arm calibration and affected-group-only live recalibration.
7. Verify adaptive watch-to-confirm-to-watch transitions and Significant Motion re-request.
8. Measure actual event cadence, battery impact, and temperature for each sampling profile.
9. Verify process restart and device reboot repeat calibration before claiming healthy protection.
10. Verify local UI and Telegram `/status` report identical desired/effective roles and health.
11. Verify one controlled multi-sensor event creates one incident thread rather than duplicate alerts.

Hardware not present on the target device remains explicitly unaccepted until tested on a device that provides it.

## 16. Acceptance criteria

The design is implemented only when all of the following are true:

- Every sensor shown in Settings is backed by catalog discovery and a runtime adapter path.
- Every source can be configured as `OFF`, `SUPPORTING`, or `PRIMARY`.
- Main Settings provides group controls; Advanced Settings provides per-source controls.
- Sensitivity 1-10 changes real bounded detector parameters per group.
- Advanced threshold, debounce, correlation, and sampling settings are validated and applied.
- Arming succeeds only after at least one primary source becomes registered and ready.
- Missing/failed supporting sources produce honest degradation; disabled sources do not.
- Every arm performs 10-second calibration/readiness validation.
- Live changes restart and recalibrate only affected groups while unaffected protection continues.
- Adaptive sampling starts and stops confirmation sensors through measured runtime state.
- Significant Motion is handled as a one-shot trigger and is re-requested.
- Rotation-vector sources replace deprecated orientation-sensor usage.
- Overlapping physical/virtual sensor observations do not create duplicate incidents.
- Settings, Protection, service notification, Events, and Telegram report the same authoritative state.
- No sensor listener sends an alert directly or blocks the main thread.
- Desired configuration, effective listener state, calibration state, and fresh health remain distinguishable.
- Focused tests, full unit tests, APK assembly, installation, and controlled real-device checks are recorded separately.

## 17. Risks and safeguards

- **Battery and heat:** use adaptive sampling, bounded profiles, background processing, and device measurement before promotion.
- **False alarms:** use stable baseline validation, per-source debounce, supporting roles, source-family deduplication, and incident-level cooldown.
- **Virtual-sensor double counting:** preserve source-family metadata and correlate within one armed-session/generation window.
- **Hardware fragmentation:** catalog capabilities dynamically and display unavailable sources honestly.
- **Race conditions during live changes:** serialize lifecycle changes and reject stale generation callbacks.
- **Unsafe configuration:** validate through one policy and confirm changes that can remove the last primary.
- **Stale restored protection:** discard baselines and repeat calibration after process restart or reboot.
- **Dirty-worktree collision:** implement in narrowly scoped TDD tasks, inspect diffs before each edit, and never reset or overwrite unrelated changes.

## 18. Written-spec review gate

This document captures the approved conversational design. Production implementation must not begin until the user reviews this written file and explicitly approves it. After approval, create a separate detailed implementation plan and agent handoff with exact paths, task dependencies, RED-GREEN-REFACTOR order, verification commands, device acceptance, and security boundaries.
