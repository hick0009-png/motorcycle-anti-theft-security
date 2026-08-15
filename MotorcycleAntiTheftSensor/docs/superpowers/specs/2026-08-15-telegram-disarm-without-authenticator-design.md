# Telegram Disarm Without Authenticator Design

**Date:** 2026-08-15

**Status:** Approved design, pending written-spec review

## Goal

Remove the Authenticator, QR, and TOTP system completely. A paired Telegram owner can disarm protection by sending the exact command `/disarm` with no code or additional confirmation.

## Product Decision

Telegram owner pairing is the only authorization boundary for remote disarm. The app continues to accept commands only from a chat ID already stored in the allowed-owner set.

This deliberately favors operational simplicity over a second authentication factor. It does not add a password, challenge, confirmation button, recovery code, or replacement authenticator flow.

## Scope

### In scope

- Make `/disarm` a parameterless command.
- Send authorized disarm directly to `ProtectionCoordinator`.
- Remove Authenticator setup, replacement, QR, manual secret, verification, and status UI.
- Remove TOTP generation, verification, lockout, setup-candidate, and secret-storage APIs.
- Remove the local QR encoder and ZXing dependency if no other production feature uses it.
- Remove obsolete Authenticator/TOTP guidance, models, callbacks, tests, and accessibility semantics.
- Delete the previously stored TOTP seed during upgrade without changing other settings.

### Out of scope

- Telegram `/pair <code>` behavior and paired-owner storage.
- Telegram Bot Token verification, TLS pins, or polling behavior.
- `/arm`, `/status`, `/sensitivity`, and other supported commands.
- `ProtectionCoordinator` state transitions and persistence semantics.
- GPS pursuit, notifications, sensors, incident history, SMS, and calls.
- Local screen-lock policy or Android device authentication.

## User Flow

1. The user pairs a Telegram account with `/pair <code>` as before.
2. The app stores the owner's Telegram chat ID in the existing allowed-owner set.
3. The paired owner sends the exact command `/disarm`.
4. `TelegramBotClient` confirms that the sender's chat ID is allowed.
5. The command is delegated to `TelegramCommandHandler` and then to `ProtectionCoordinator` with `CommandOrigin.TELEGRAM`.
6. The coordinator performs the existing authoritative disarm transition and stops the relevant protection detectors.
7. Telegram replies with the existing success message:

   `✅ ปลดการป้องกันสำเร็จ`

An unpaired chat remains unauthorized and cannot reach `ProtectionCoordinator`.

## Command Contract

`RemoteCommand.Disarm` becomes a parameterless command object.

| Input | Parse result | Effect |
|---|---|---|
| `/disarm` | `RemoteCommand.Disarm` | Authorized owner is delegated to disarm |
| `/DISARM` | `RemoteCommand.Disarm` | Same case-insensitive behavior as current commands |
| `/disarm 123456` | `RemoteCommand.Unknown` | Existing generic unknown-command reply; no state change |
| `/disarm anything` | `RemoteCommand.Unknown` | Existing generic unknown-command reply; no state change |
| `/disarm` from an unpaired chat | Parsed but rejected by existing owner authorization | No state change |

There is no legacy parser branch, ignored argument, migration message, or special reply for the old `/disarm <TOTP>` syntax.

## Architecture and Component Changes

### Telegram command path

`TelegramBotClient` no longer receives or creates a `TotpAuthenticator`. After the existing allowed-chat check succeeds, `RemoteCommand.Disarm` follows the same delegation path as other authorized operational commands.

`TelegramCommandHandler` retains responsibility for invoking `ProtectionCoordinator.disarm` and formatting its result. It does not perform authentication or parse command arguments.

### Application wiring

`Navigation` and `SensorService` stop constructing or passing `TotpAuthenticator`. `ProtectionRuntimeGraph` also removes any unused authenticator construction. The same `ProtectionCoordinator` remains authoritative for local and Telegram disarm.

### Settings and UI state

Remove all Authenticator-related UI and state:

- configured/not-configured status;
- `Set up authenticator` and `Replace authenticator` actions;
- setup dialog, QR image, manual secret, reveal/hide controls, and verification field;
- `AuthenticatorSetupDetails` and pending-request callbacks;
- Authenticator properties from `ProtectionSettingsSummary`, `ProtectionUiState`, and `ProtectionAppActions`;
- ViewModel begin, cancel, and verify operations;
- Authenticator methods from `ProtectionSettingsGateway` and `AndroidProtectionSettingsOperations`.

Removing these fields must not change the layout or behavior of Telegram token, pairing, sensitivity, permission, SMS, or diagnostics settings.

### Authenticator implementation and dependency

Delete `TotpAuthenticator.kt` and `AuthenticatorQrCodeEncoder.kt`. Remove their production and test call sites. Remove ZXing Core from `app/build.gradle.kts` after confirming it has no remaining production or test consumer.

### Stored-data cleanup

An app-upgrade migration removes only the legacy encrypted TOTP seed key. It must preserve:

- Telegram Bot Token;
- allowed owner chat IDs and active pairing state;
- sensitivity and permission-related settings;
- SMS fallback settings;
- protection snapshot, incident history, and GPS pursuit state.

The migration is idempotent: running it more than once has no additional effect. No code path reads or writes a TOTP seed after migration. Failure to delete an unreachable legacy value must not block Telegram polling or protection startup, but it must be covered by a controlled, non-secret diagnostic result.

## Error Handling

- An unpaired chat receives the existing unauthorized/pairing response and causes no state change.
- A command containing an argument is handled as the existing generic unknown command and causes no state change.
- If the command handler is unavailable, retain the existing offline response.
- If `ProtectionCoordinator.disarm` rejects, times out, or returns a partial/unknown result, preserve its current truthful result mapping; do not report success prematurely.
- Never echo command arguments, chat IDs, tokens, or removed TOTP data in logs, UI messages, diagnostics, or Telegram replies.

## Removal Inventory

The implementation plan must search for and remove or update every production/test reference to:

- `TotpAuthenticator` and `VerificationResult`;
- `AuthenticatorQrCodeEncoder`;
- `AuthenticatorSetupDetails` and pending setup state;
- `authenticatorConfigured`, `beginAuthenticatorSetup`, `cancelAuthenticatorSetup`, and `verifyAuthenticator`;
- `getTotpSeed`, `saveTotpSeed`, and the legacy seed key outside the isolated migration;
- `otpauth://`, QR setup semantics, and Authenticator guidance;
- `TOTP_SETUP_*`, `TOTP_CONFIGURED`, `TOTP_NOT_CONFIGURED`, `TOTP_REQUIRED`, `TOTP_INVALID`, `TOTP_LOCKED`, and `TOTP_REMOTE_RECOVERY_REQUIRED`;
- help text that documents `/disarm <TOTP>`;
- ZXing dependency declarations used only by Authenticator QR.

Do not remove pairing-code rate limits or unrelated command protections while performing this cleanup.

## Test Strategy

### Parser and authorization tests

- Exact `/disarm` parses as the parameterless disarm command.
- Case-insensitive `/DISARM` remains supported.
- `/disarm 123456` and any other argument parse as unknown.
- An allowed owner reaches the command handler exactly once.
- An unpaired chat never reaches the command handler or coordinator.
- No TOTP seed is required for an allowed owner to disarm.

### Coordinator integration tests

- Authorized `/disarm` delegates with `CommandOrigin.TELEGRAM`.
- Successful disarm returns `✅ ปลดการป้องกันสำเร็จ`.
- Disarm while already disarmed remains safe and follows the coordinator's existing idempotent result.
- Disarm during an active alert stops protection using the existing coordinator transition and does not bypass its persistence result.
- Unavailable or rejected coordinator outcomes remain truthful.

### Settings and migration tests

- Settings no longer render Authenticator status, controls, dialog, QR, secret, or verification input.
- Settings models and actions have no Authenticator fields or callbacks.
- Upgrade cleanup removes the legacy TOTP seed and preserves every unrelated preference in the migration fixture.
- Cleanup is idempotent.

### Removal contract tests

- Production source contains no `TotpAuthenticator`, Authenticator setup, or QR encoder call sites.
- No `otpauth://` URI or TOTP guidance remains in production resources.
- ZXing is absent if repository-wide search confirms no other consumer.
- Telegram help documents `/disarm`, not `/disarm <TOTP>`.

### Verification gates

- Run focused Telegram parser, authorization, handler, Settings, ViewModel, migration, and coordinator tests.
- Run the complete host unit suite.
- Assemble a fresh debug APK and compile Android tests.
- Install on the Huawei device without clearing application data so upgrade cleanup is exercised.
- Confirm the paired owner can send `/disarm` successfully without opening Settings.
- Confirm an unpaired account cannot disarm.
- Confirm the installed Settings screen contains no Authenticator or QR controls.
- Confirm `/arm`, `/status`, `/sensitivity`, pairing, Telegram polling, GPS, and foreground service behavior still work.

## Acceptance Criteria

- The exact paired-owner command `/disarm` requires no code and reaches the authoritative coordinator once.
- Unpaired chats and commands with arguments cannot change protection state.
- Successful disarm returns `✅ ปลดการป้องกันสำเร็จ` only after the coordinator reports success.
- Authenticator, QR, TOTP verification, setup, lockout, UI, guidance, and unused dependency code are removed.
- The legacy encrypted TOTP seed is deleted on upgrade while all unrelated configuration remains intact.
- No TOTP or Authenticator object is constructed by the app, service, runtime graph, Telegram client, Settings gateway, or ViewModel.
- Focused tests, full host tests, APK build, Android-test compilation, Huawei upgrade validation, and Telegram owner/unpaired acceptance pass before implementation is called complete.

## Implementation Boundary

This document approves the design only. Production changes begin only after the written spec is reviewed and a separate TDD implementation plan is approved.
