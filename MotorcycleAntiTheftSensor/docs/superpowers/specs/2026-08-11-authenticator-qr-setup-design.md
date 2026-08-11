# Authenticator QR Setup Design

**Date:** 2026-08-11  
**Status:** Approved design, pending written-spec review

## Goal

Let an owner set up TOTP by displaying a standards-compatible QR code on the Huawei device and scanning it with an authenticator app on a second phone. Manual secret entry remains available as a fallback.

## Scope

This change affects only the existing authenticator setup dialog and local QR generation. It does not change TOTP generation or verification, Telegram command authorization, pairing, SMS behavior, or protection state management.

## User Flow

1. The owner selects `Set up authenticator` or `Replace authenticator` in Settings.
2. The app creates the existing transient authenticator setup candidate.
3. The dialog initially hides both the secret and QR code.
4. The owner selects `Show QR code`.
5. The app generates and displays a QR code locally from the candidate's existing `otpauth://` URI.
6. The owner scans the QR code with an authenticator app on a second phone.
7. The owner enters the generated six-digit code on the Huawei device and selects `Verify`.
8. A successful verification activates the candidate and closes the dialog. Cancel, dismissal, replacement, or successful verification clears all QR UI state with the candidate.

`Reveal secret` remains available for manual setup. Showing or hiding the QR code does not reveal or hide the manual secret, and the two controls remain independent.

## Components and Data Flow

### Local QR encoder

A small QR encoder component accepts the already-created `otpauth://` URI and produces an in-memory bitmap. It uses ZXing Core and does not perform network access. Its output uses a high-contrast black-on-white QR code with sufficient quiet zone and a fixed logical size that Compose can scale without cropping.

The encoder has no access to application preferences, logs, files, the clipboard, or Android networking. It receives only the URI required for one encoding operation.

### Authenticator setup dialog

The existing dialog owns the `Show QR code` state. The QR is absent from composition until the owner explicitly selects the control. The displayed image has a non-secret accessibility description such as `Authenticator setup QR code`; neither the URI nor secret is placed in semantics.

The existing `FLAG_SECURE` protection remains active for the entire setup dialog, including when the QR is visible. The QR and its visibility state are discarded whenever the existing setup candidate is cleared.

### Existing setup candidate

The existing `AuthenticatorSetupDetails.uri` remains the single source for QR contents. No second URI builder or duplicate secret transformation is introduced. Existing candidate verification and activation remain unchanged.

## Dependency Decision

Add ZXing Core as the only new dependency. It is used locally for both production encoding and behavioral decoding in tests. A remote QR service is prohibited because it would disclose the authenticator secret. A custom QR implementation is rejected because of unnecessary correctness and maintenance risk.

The implementation plan must select a pinned ZXing Core version compatible with the current Android minimum SDK and build toolchain.

## Security Requirements

- Generate the QR entirely on device.
- Never send the `otpauth://` URI or TOTP secret over the network.
- Never write the URI, secret, or QR bitmap to logs, files, screenshots, saved instance state, clipboard, UI messages, or accessibility semantics.
- Keep `FLAG_SECURE` active while the setup dialog exists.
- Keep setup details transient and outside `ProtectionUiState`.
- Clear the QR visibility state and release the in-memory image when setup is cancelled, dismissed, superseded, or verified.
- Do not change the device's `SEND_SMS: deny` testing boundary.

## Error Handling

If QR generation fails, the dialog remains usable and shows a non-sensitive message directing the owner to use `Reveal secret`. The app must not crash, activate the candidate, or expose the URI in the error message. Retrying `Show QR code` may attempt generation again.

## Accessibility and Layout

- Give the QR image a non-secret content description.
- Provide a minimum 48 dp touch target for show/hide controls.
- Keep the QR fully visible without horizontal clipping on the Huawei 1080 x 2340 device.
- Keep the verification field and dialog actions reachable by scrolling when necessary.
- Use explicit `Show QR code` and `Hide QR code` labels so visibility does not depend on color or iconography.

## Test Strategy

### Unit tests

- Encode a literal test-only `otpauth://` URI, decode the resulting QR with the real ZXing decoder, and assert exact URI equality.
- Verify invalid or failed encoding returns a controlled failure rather than a crash or partial image.

### Compose UI tests

- Confirm no QR exists before `Show QR code` is selected.
- Confirm the QR appears after selection and has a non-secret accessibility description.
- Confirm the URI and secret never appear in semantics while the QR is visible.
- Confirm hiding or cancelling removes the QR.
- Confirm `FLAG_SECURE` remains set during the setup flow.
- Preserve the existing tests for transient setup state, reveal/hide secret, cancellation, and verification.

### Full verification

- Run the complete unit suite and complete connected UI suite.
- Assemble a fresh debug APK and install it on Huawei device `JUCDU18811013149`.
- Re-apply and verify `SEND_SMS: deny` after installation.
- Display the QR on the Huawei device, scan it with an authenticator app on a second phone, and verify a live six-digit TOTP.
- Do not claim real-device acceptance until that scan and verification succeed.

## Acceptance Criteria

- QR content decodes exactly to the existing setup candidate URI.
- The QR is created locally and appears only after explicit user action.
- A second phone can scan the Huawei display and generate a valid six-digit code.
- Entering that code activates authenticator setup successfully.
- Manual secret setup remains available.
- Secrets are absent from persistence, logs, files, screenshots, clipboard, saved state, and accessibility output.
- Existing app behavior and full automated suites remain passing.
- SMS remains disabled throughout device testing.
