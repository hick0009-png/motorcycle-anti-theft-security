# Anti-Theft Security Remediation Design

## Goal

Make the motorcycle anti-theft app reject unauthorized remote control, deliver sensor alerts while its UI is not open, and fail safely when required security or runtime prerequisites are missing.

## Scope

This work fixes the audit findings in the Android app only. It does not add external services, new dependencies, a backend, or hardware power-control integration.

## Architecture

### Pairing and authorization

The app will generate a cryptographically random, single-use pairing code on the physical device. A Telegram chat becomes an owner only after sending `/pair <code>` before the code expires. The first incoming chat must never be auto-authorized.

The owner set remains in encrypted preferences. `/disarm` requires both an authorized chat and a valid TOTP code. Missing TOTP configuration is a rejection, never a disarm. The persisted pairing-code state includes the code, expiry, and whether it has been consumed.

### Remote commands and service lifecycle

`SensorService` owns security-state changes while it runs. A small command handler converts authorized Telegram commands into explicit service actions. UI state observes persisted state and may start or stop the service, but remote commands do not depend on a composable receiver or implicit app broadcasts.

The service owns sensor lifecycle transitions: arm starts eligible sensors, disarm stops them, and service restart restores the persisted armed state. Internal actions are explicit package-scoped intents and receivers are not exported to other apps.

### Alert delivery

`AlertDispatcher` is invoked directly by `SensorService` for each sensor event. It sends Telegram alerts to every paired owner. When Telegram delivery fails, it invokes the configured SMS fallback; direct-call escalation remains disabled unless an explicit configured phone number and runtime permission are available. Alert delivery runs off the main thread and records a local result suitable for UI diagnostics.

No code claims an electrical thermal power cut-off. A thermal event is reported as an alert only.

### Permissions and crypto

Before arming, the UI must require microphone permission for the audio detector and notification permission where applicable. Sensors requiring unavailable permissions are not started; the armed status shows the protection as degraded rather than active.

SMS payloads use AES-GCM with a new random 96-bit nonce for every message. The encoded message includes version, nonce, and ciphertext/tag. Invalid or altered payloads return no plaintext. Existing CBC payloads are not decrypted.

## Error handling

- Invalid, expired, or already-used pairing codes do not modify ownership.
- Telegram, SMS, crypto, and permission failures do not crash the foreground service.
- Missing required configuration causes a clear rejected/degraded result, never a silent success.
- A failed Telegram request may fall back to SMS only when a destination and key have been configured.

## Testing

Add JVM tests for pairing-code validation, authorization, mandatory TOTP, command-to-service action mapping, and authenticated SMS encryption. Add tests for alert-dispatch fallback selection with fake transports. Extend Android tests only for permission and UI state behavior that cannot run on the JVM.

## Constraints

- Keep the existing Android/Kotlin architecture and dependencies.
- Preserve encrypted preference storage for secrets and owner identifiers.
- Do not auto-pair any Telegram chat.
- Do not expose implicit arm, disarm, or alarm broadcasts to other apps.
- Do not claim real-device operation without device evidence.
