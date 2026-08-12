# Telegram TLS Pin Refresh Design

**Date:** 2026-08-12  
**Status:** Approved design, pending written-spec review

## Goal

Restore secure Telegram Bot API connectivity without weakening HTTPS validation or exposing a bot token. The affected flows are bot-token verification (`getMe`), long polling (`getUpdates`), and outbound messages (`sendMessage`).

## Confirmed Cause

The application-wide Android Network Security Config pins `api.telegram.org` to one stale SHA-256 Subject Public Key Info (SPKI) digest. A live TLS handshake on 2026-08-12 returned a different leaf SPKI digest. The Android client therefore rejects the HTTPS connection before Telegram can evaluate the bot token. Device log reproduction recorded the app's redacted `Bot token verification failed` failure path.

## Scope

- Replace the stale Telegram pin configuration with a verified primary/backup pin set derived from the live, valid `api.telegram.org` certificate chain.
- Preserve HTTPS-only traffic and system trust anchors.
- Preserve token redaction: no token, URL containing a token, response body, or raw TLS exception may be surfaced in UI or logs.
- Give the settings flow a stable, non-secret connection failure result that distinguishes TLS transport failure from an invalid bot token only when the transport layer can establish that distinction safely.
- Add regression coverage for the pin resource and verification-result mapping.

This does not change pairing rules, TOTP requirements, command dispatch, SMS behavior, or the QR work.

## Design

### Pin configuration

`network_security_config.xml` remains the authoritative Android network policy for `api.telegram.org`. It will retain `cleartextTrafficPermitted="false"` and system trust anchors.

The pin set will contain two independently verified SPKI SHA-256 pins:

1. a primary pin matching the live Telegram certificate chain verified during implementation;
2. a backup pin from the same trusted chain that remains valid if Telegram rotates the leaf certificate.

The implementation must capture each pin from a live TLS handshake immediately before editing, record only the public certificate subject, issuer, validity interval, and SPKI digest in the test fixture or implementation notes, and verify that the Android TLS stack accepts the chain. A single leaf-only pin is prohibited because it caused this outage. The pin-set expiry must be reviewed and set before the relevant chain expires.

### Failure handling

`TelegramBotClient` continues to avoid raw exception logging. Its verification boundary will provide a small internal, token-free result type for success, invalid Telegram response, and transport/security failure. Settings maps this to a concise message such as `Telegram connection could not be established`; it must not claim the token is invalid when no authenticated Telegram response was received.

The token entry remains cleared after a save attempt to avoid retaining a secret in the UI. A failed verification must not persist or replace the previously saved token, and polling must continue to use the last verified token.

### Polling and messages

No fallback to cleartext, unpinned client, alternate host, or insecure retry is allowed. After a verified token is saved, the existing foreground-service refresh boundary restarts polling. `getUpdates` and `sendMessage` remain on the same secure client and retain their redacted failure handling.

## Test Strategy

### Unit and resource tests

- Parse the Telegram network-security resource and assert one Telegram domain configuration, cleartext disabled, exactly two SHA-256 pins, and a future expiry.
- Assert the stale digest is absent.
- Test bot verification maps a valid `ok=true` Telegram response to success.
- Test invalid Telegram responses and TLS/transport failures are mapped to separate token-free outcomes.
- Test a failed replacement leaves the existing stored token unchanged and does not request a polling refresh.

### Device acceptance

1. Install a freshly assembled APK on Huawei `JUCDU18811013149`.
2. Revoke `SEND_SMS` and set its app-op to `deny`; verify both states.
3. Enter the bot token, save it, and verify the UI reports a connected bot identity.
4. Generate a pairing code on the device, send `/pair <code>` from the owner account, and verify the pairing reply.
5. Send an allowed non-destructive command or test notification and verify it is received.
6. Do not capture screenshots, logs, clipboard content, or UI hierarchies containing the token, pairing code, or authenticator data.

## Acceptance Criteria

- `getMe`, polling, and outbound Telegram messages establish TLS successfully on Huawei with the verified pin set.
- Invalid tokens are rejected only after Telegram returns an authenticated invalid-token response.
- A TLS/security failure is reported as a connection failure without secret disclosure.
- The previous verified token is retained after a failed replacement.
- HTTPS-only traffic and certificate pinning remain enabled with a primary and backup pin.
- Relevant unit tests and connected device checks pass; `SEND_SMS` remains denied.
