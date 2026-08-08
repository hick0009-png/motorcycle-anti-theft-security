# Demo-Free Authoritative UI Design

Date: 2026-08-08
Status: Approved
Scope: Motorcycle Guard Protection System UI and presentation state

## Decision

The production application will not contain Demo Mode. Demo is removed from the UI and ViewModel contract rather than hidden behind a flag. There will be no demo switch, banner, trigger button, command action, fake event state, placeholder, or dormant navigation entry.

This document supersedes only the Demo Mode and Task 10 UI portions of `2026-08-08-motorcycle-guard-protection-system-design.md` and `2026-08-08-motorcycle-guard-protection-system.md`. The already implemented authoritative protection runtime, incident pipeline, delivery behavior, service integration, and Telegram command behavior remain unchanged.

## Product structure

The application has exactly three primary destinations:

1. **Protection** — the current authoritative state, one Arm/Disarm action, arming countdown, named blockers and degradations, sensor/channel health, and the active or latest incident.
2. **Events** — newest-first incident history with source, severity, lifecycle, evidence, and delivery outcome. Empty and failure states are explicit.
3. **Settings** — Telegram pairing, masked credential management, permissions, sensitivity, SMS fallback, diagnostics, and security controls.

Protection status always uses text and iconography in addition to color. An active incident appears as a persistent non-blocking card, never a blocking alarm modal.

## Architecture

`ProtectionViewModel` is the only presentation-state owner for these destinations. It observes `ProtectionRuntimeGraph.coordinator` snapshots and the incident repository, then maps only production fields into immutable UI state. The raw snapshot is not exposed because it still contains a legacy Demo-only field outside this UI scope. The ViewModel does not maintain a second armed boolean and does not write armed state directly to preferences.

The UI sends intents to the ViewModel. Arm and disarm intents call the authoritative coordinator and render its confirmed result. Navigation owns only the selected destination and does not duplicate protection state.

The migration uses a thin-wrapper approach:

- introduce `ProtectionAppScreen` as the three-destination shell;
- add focused `ProtectionScreen`, `EventsScreen`, and `SettingsScreen` composables;
- reduce the existing dashboard integration to a compatibility boundary while routes are moved to the new shell;
- preserve unrelated working-tree changes and existing visual tokens/components where they are still correct.

No new dependency is introduced.

## UI state and actions

The top-level state contains:

- selected destination;
- current `ProtectionSnapshot` presentation;
- arming countdown derived from the authoritative transition deadline;
- blockers and degradations as readable items;
- compact health rows for sensors, service, Telegram, and delivery;
- active/latest incident summary;
- bounded newest-first event rows;
- settings presentation state and validation messages;
- operation progress and one-shot result/error messages.

The action contract contains only production actions: select destination, arm, disarm, retry, clear event history after confirmation, update sensitivity, request required permissions, pair/re-pair Telegram, edit or reset the masked token, configure SMS fallback, and invoke diagnostics/security controls that already exist.

There are no Demo-related fields, event types, callbacks, semantics tags, or test fixtures in the UI/ViewModel layer.

## Screen behavior

### Protection

- Shows one dominant, human-readable protection state.
- Shows exactly one primary Arm or Disarm action appropriate to the confirmed state.
- During `ARMING`, shows the remaining grace/calibration time and permits cancellation through Disarm.
- Rejected arm attempts show named blockers and remediation without claiming that monitoring started.
- Degraded operation lists the unavailable capability while keeping the real armed state visible.
- Sensor and channel health distinguish available, healthy, stale, unavailable, and failed states.
- Active incidents remain visible without blocking navigation or disarm.

### Events

- Displays incidents newest first using stable incident identifiers.
- Each row exposes source, severity, lifecycle, evidence summary, timestamp, and delivery outcome without relying on color alone.
- The empty state explains that no protection incidents have been recorded.
- History loading/persistence failure is visible and retryable.
- Clear history requires confirmation and does not delete pairing or configuration.

### Settings

- Shows pairing state without exposing operational details to an unauthorized identity.
- Never loads the stored Telegram bot token into general UI state. Replacing it uses a blank password field, so the configured secret remains masked and absent from logs, saved-state handles, screenshots, and accessibility descriptions.
- Replace/re-pair is deliberate and confirmed. It uses the existing encrypted preference operations without changing the security-critical `EncryptedPrefsManager` implementation.
- Permission rows state why a permission is needed and whether it blocks arming or only degrades protection.
- Sensitivity accepts only the supported range and reports the confirmed applied value.
- SMS fallback clearly states its eligibility: configured real critical incidents after confirmed Telegram failure.
- Diagnostics and security controls report actual results instead of optimistic success.

## Loading, error, and concurrency behavior

The first frame displays a stable loading state until an authoritative snapshot is available. Commands disable only conflicting actions while in flight. Rapid repeated arm/disarm requests are serialized by the coordinator and the UI displays the confirmed final state.

Errors are scoped to the affected operation. A repository failure does not imply that protection stopped; a coordinator rejection does not become a generic crash message. One-shot messages are consumed once and do not reappear after recomposition or destination changes.

No sensor, persistence, cryptographic, or network work runs on the main thread.

## Accessibility and visual quality

- Primary controls meet a minimum 48 dp touch target.
- State, severity, health, and delivery outcome have descriptive text and semantics in addition to color.
- Focus order follows the visual hierarchy and dynamic messages are announced without repeatedly interrupting the user.
- Text supports Android font scaling without clipping critical actions or values.
- Long evidence, blocker, and diagnostic text wraps or truncates with an accessible full description.
- Insets, keyboard visibility, scroll behavior, and the target 1080 x 2340 device layout are checked on all three destinations.
- Destructive confirmation and credential replacement controls are unambiguous and not adjacent to the primary protection action.

## Verification

Implementation follows TDD:

1. ViewModel tests prove authoritative state mapping, arm/disarm command outcomes, countdown behavior, event ordering, settings validation, one-shot messages, and absence of Demo presentation state/actions.
2. Compose tests prove three-destination navigation, one primary protection action, blocker/degradation text, event empty/history states, token masking, clear confirmation, semantics labels, and the absence of Demo UI.
3. `testDebugUnitTest` and `assembleDebug` run from a fresh invocation.
4. The built app is installed on a connected device when available.
5. Current-run screenshots are captured for Protection, Events, and Settings at the target device size and inspected for clipping, spacing, hierarchy, contrast, touch targets, text scaling risks, and state clarity.
6. Critical and important audit findings are fixed and re-captured before handoff. Screenshot inspection supports a UI audit but is not represented as full assistive-technology compliance.

## Acceptance criteria

- Searching production UI and ViewModel sources finds no Demo Mode state, actions, labels, controls, or navigation.
- UI, service notification, and Telegram derive protection status from the same authoritative snapshot.
- The application exposes exactly Protection, Events, and Settings as primary destinations.
- Arm/Disarm is singular, state-correct, and based on confirmed coordinator outcomes.
- Protection blockers, degradations, health, and incidents remain understandable without color.
- Events and Settings include complete loading, empty, error, confirmation, and validation behavior where applicable.
- Credentials remain masked and excluded from broad presentation state and accessibility output.
- Automated verification passes, and current-run UI evidence records any remaining device or accessibility limitations honestly.
