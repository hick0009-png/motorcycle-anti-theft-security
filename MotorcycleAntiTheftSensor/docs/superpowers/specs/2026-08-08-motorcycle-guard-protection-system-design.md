# Motorcycle Guard Protection System Design

Date: 2026-08-08
Status: Approved design pending written-spec review

## Goal

Turn the existing Android application into a dependable motorcycle protection controller that remains reachable through Telegram while disarmed, reports its real operating condition, produces useful low-noise alerts, and includes a safe customer demonstration mode.

## Scope and delivery order

The work will preserve the existing Android, foreground-service, encrypted-preference, sensor, Telegram, and SMS components. It will be delivered incrementally in this order:

1. Establish one authoritative protection state and command-result model.
2. Make local and Telegram arm, disarm, and status operations use that model.
3. Add arming readiness checks, a 10-second grace/calibration period, and live sensor health.
4. Normalize sensor observations and correlate them into incidents.
5. Persist incident history and delivery outcomes.
6. Add Demo Mode and then simplify the application screens around the new model.

This design does not replace the application wholesale, introduce a backend server, add new third-party dependencies, or claim hardware capabilities the phone cannot perform.

## Product principles

- The app must never report that protection is armed before required checks pass and monitoring starts.
- UI, service notifications, and Telegram must describe the same authoritative state.
- Disarming stops theft sensing but keeps the foreground remote-control service and Telegram polling available.
- Every remote command returns whether it was applied or rejected, the resulting state, and a concise reason.
- Sensor labels describe measured or inferred values accurately. Relative microphone amplitude is not presented as calibrated physical sound pressure.
- Alerts describe actions actually taken. The app must not claim power cutoff, live GPS, SMS fallback, or successful delivery without evidence.
- Demo events are visibly marked as demonstrations and cannot trigger SMS or emergency calls.

## Authoritative state model

`ProtectionCoordinator` owns transitions and exposes an immutable `ProtectionSnapshot` to the service, UI, notification renderer, and Telegram command handler.

### States

- `SETUP_REQUIRED`: Telegram ownership, required Android permissions, or minimum required sensors are unavailable. Arming is rejected with named blockers.
- `DISARMED_ONLINE`: the foreground service and Telegram remote control are running; theft detectors are stopped.
- `ARMING`: readiness has passed and the system is collecting baselines during a 10-second grace period. Theft alerts are suppressed during this period.
- `ARMED_HEALTHY`: required detectors are sampling recently and the primary notification channel is reachable.
- `ARMED_DEGRADED`: protection remains active, but one or more optional sensors or delivery channels are unavailable. The snapshot lists every degradation.
- `ALERT_ACTIVE`: an incident is open and includes severity, evidence, start time, update time, and delivery status. Protection remains active.
- `OFFLINE`: the service heartbeat or Telegram connectivity is stale. A persisted snapshot may expose this state after the UI reconnects; it must not imply that live monitoring is healthy.

`ALERT_ACTIVE` returns to `ARMED_HEALTHY` or `ARMED_DEGRADED` when the incident closes. Disarm is valid from `ARMING`, either armed state, or `ALERT_ACTIVE` and results in `DISARMED_ONLINE`.

### Snapshot fields

The snapshot contains:

- state and last transition timestamp;
- whether the service and Telegram polling are running;
- Telegram reachability and last successful contact time;
- named permission blockers;
- health for vibration, light, power/thermal, microphone, and location;
- battery level and temperature when available;
- last incident summary and delivery result;
- whether Demo Mode is enabled.

A sensor is `HEALTHY` only after a valid sample has arrived within its defined freshness window. Hardware presence or provider enablement alone is `AVAILABLE`, not `HEALTHY`.

## Arming and disarming

An arm request has a unique command identifier and follows one path regardless of whether it originates from the UI or Telegram:

1. Validate pairing/configuration and required Android permissions.
2. Validate that the minimum vibration detector is available. Missing optional sensors produce degraded operation rather than rejection.
3. Enter `ARMING`, start detectors, and collect a 10-second baseline.
4. Suppress theft alerts during the grace period while still recording health observations.
5. Enter `ARMED_HEALTHY` if all configured sensors and Telegram are healthy, otherwise `ARMED_DEGRADED` with named reasons.
6. Publish the resulting snapshot to every output channel.

If required checks fail or sensor startup throws, the request is rejected, detectors that started are stopped, and the state becomes `SETUP_REQUIRED` or `DISARMED_ONLINE` according to whether setup is incomplete.

Disarm cancels an in-progress arming timer, closes any active incident as owner-disarmed, stops all theft detectors, retains Telegram polling, persists the new state, and publishes `DISARMED_ONLINE`.

Changing sensitivity while armed restarts or updates the active vibration detector immediately through the coordinator. The response states the applied level.

## Sensor observation and incident pipeline

Detectors emit typed `SensorObservation` values instead of sending alerts directly. Each observation contains sensor type, monotonic/event timestamps, normalized value, baseline-relative delta, validity, and optional diagnostic text.

The processing pipeline is:

1. Reject invalid or stale samples.
2. Compare the sample with the arming baseline and configured sensitivity.
3. Debounce short spikes within each sensor.
4. Correlate related observations inside a bounded time window.
5. Create or update one incident with severity and evidence.
6. Enforce incident-level cooldown and escalation.
7. Persist the incident before attempting external delivery.
8. Send Telegram and record the confirmed outcome; use SMS only for eligible real critical incidents when configured.

Initial incident rules are intentionally limited:

- Vibration sustained across the debounce window creates a warning incident.
- Vibration combined with a significant under-seat light change within 15 seconds creates a critical tamper incident.
- Charger disconnection while armed creates a critical power incident.
- Thermal readings above the existing safe limit create a warning and report the measured temperature; no power-cutoff claim is made.
- Relative audio peaks can add supporting evidence but cannot create a critical incident by themselves.
- Location is included only when a fix has accuracy and age values within configured limits; otherwise the message says that no recent fix is available.

Repeated evidence updates an open incident instead of creating pop-ups or independent Telegram messages. Severity escalation sends one update immediately. Otherwise, the existing 30-second type cooldown is replaced by incident-level suppression, with a concise summary when the incident closes.

## Alerts and delivery

Every stored `SecurityIncident` contains an identifier, real/demo source, severity, lifecycle state, evidence list, timestamps, protection state, and delivery attempts.

A Telegram alert includes:

- an explicit `DEMO` marker when applicable;
- incident type and severity;
- local time;
- concise sensor evidence;
- current protection state;
- location with fix age/accuracy, or a clear unavailable reason;
- delivery-independent incident identifier.

Delivery states are `PENDING`, `SENT`, and `FAILED`. `SENT` is recorded only after a successful Telegram API response. SMS fallback is attempted only when all of these are true: the incident is real, severity is critical, Telegram delivery failed, and an SMS destination and encryption key are configured. Emergency calls are outside this implementation scope.

No blocking network or sensor work runs on the main thread.

## Telegram commands

Supported owner commands use the authoritative coordinator:

- `/arm`: returns `received`, then a final `applied` or `rejected` result after readiness and the arming grace period.
- `/disarm <OTP>`: returns the confirmed resulting state after existing authorization succeeds.
- `/status`: reports protection state, service, Telegram freshness, permission blockers, compact sensor health, battery, last incident, and last delivery outcome.
- `/sensitivity <1-10>`: validates, persists, and immediately applies the new level when armed.
- `/demo <vibration|tamper|power|audio>`: creates a marked demo observation only when Demo Mode is enabled.
- `/help`: lists available commands and indicates which commands require authorization.

Unauthorized chats receive no operational details. Command parse errors show usage without changing state. A command timeout reports that the outcome is unknown rather than claiming success.

## Demo Mode

Demo Mode is enabled and disabled locally in Settings. It is shown persistently on the Home screen and in the foreground notification while active.

Demo events pass through the same observation, correlation, incident history, UI, and Telegram formatting paths as real events. They are tagged `DEMO` from origin through persistence and delivery. Demo Mode may send Telegram messages to the paired owner, but it never sends SMS, initiates a call, changes real sensor baselines, or claims a real theft event.

The local Demo panel provides four actions: vibration, under-seat tamper, power disconnect, and audio evidence. The Telegram `/demo` command is accepted only from the paired owner while the local Demo Mode switch is enabled.

## User experience

The application will use three primary destinations:

- **Protection:** one dominant state, one arm/disarm action, arming countdown, blocking issues, compact sensor/channel health, Demo banner, and latest incident.
- **Events:** chronological real and demo incidents with severity, evidence, lifecycle, and delivery outcome.
- **Settings:** Telegram pairing and masked token management, permissions, sensitivity, SMS fallback configuration, diagnostics, security controls, and the local Demo Mode switch.

Protection status must use text and icons in addition to color. Pending, healthy, degraded, failed, and demo states have distinct labels. The removed red modal remains removed; active incidents appear as a persistent, non-blocking status card and in history.

The bot token is masked by default with an explicit reveal action and a reset/re-pair flow. The currently exposed token must be revoked and replaced outside the application before customer use.

## Persistence and recovery

The existing encrypted preferences remain the source for secrets and simple configuration. Protection snapshot and incident history use a bounded local persistence layer. History retains the most recent 200 incidents; deleting history does not delete Telegram ownership or configuration.

After process restart or boot:

- if the persisted state was disarmed, start remote-control-only mode;
- if it was armed, start in `ARMING`, re-check readiness, rebuild baselines, and then reach a healthy or degraded state;
- never restore `ALERT_ACTIVE` without marking the previous incident interrupted;
- publish stale/offline health honestly until fresh samples and Telegram contact arrive.

## Failure handling

- Missing required permissions reject arm with named remediation.
- Missing optional sensors produce `ARMED_DEGRADED` and do not crash the service.
- Telegram failures are retained as delivery attempts and surfaced in status.
- Detector exceptions isolate that detector, update its health, and re-evaluate healthy versus degraded state.
- Persistence failures keep monitoring active when safe but surface a degraded reason and avoid claiming that history was saved.
- Repeated identical failures are rate-limited to avoid notification loops.

## Testing and verification

Implementation follows test-driven development. Unit tests cover state transitions, permission/readiness decisions, arming cancellation, sensor freshness, observation validation, correlation, escalation, cooldown, delivery policy, Demo isolation, command formatting, and restart recovery.

Integration tests cover coordinator-to-service behavior, Telegram command acknowledgement, immediate sensitivity application, persistence, and real-versus-demo fallback rules. Compose tests cover the primary state, arming countdown, degraded reasons, Demo marking, token masking, and event history.

Release verification requires:

1. `testDebugUnitTest` and `assembleDebug` to pass from a clean invocation.
2. Installation on the connected Android device.
3. Manual local and Telegram arm/disarm/status checks.
4. Verification that real sensors are suppressed during the grace period.
5. One Demo event for each type with Telegram receipt and no SMS/call attempt.
6. One controlled real sensor event with observed correlation and cooldown behavior.
7. Process restart and device reboot checks for remote-only and armed recovery.

## Acceptance criteria

- Local UI and Telegram show the same resulting protection state after every command.
- `/arm` cannot report success when required readiness checks fail.
- Disarming stops theft detectors while Telegram remote control remains responsive.
- `/status` reports real service, permission, sensor, battery, incident, and delivery information.
- Sensitivity changes affect the active vibration detector without requiring service restart.
- A single physical scenario produces one incident thread rather than independent alert spam.
- Demo Mode produces clearly marked UI/history/Telegram events and never invokes SMS or calls.
- The app contains no blocking red alarm pop-up and masks the bot token by default.
- Restart recovery re-validates protection instead of trusting stale armed state.
- Automated and device verification evidence is recorded without exposing credentials.
