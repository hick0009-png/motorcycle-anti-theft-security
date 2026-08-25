# Moto Guard Navy UI visual design

**Status:** Owner-approved visual direction; implementation pending task-by-task execution

**Date:** 2026-08-26

**Scope:** Presentation-only refresh of the reachable Android Compose application.

**Builds on:** `2026-08-23-profile-aware-thai-ux-terminology-design.md` and its implementation plan. That approved terminology, profile, security, and accessibility contract remains authoritative. This addendum defines the visual system selected from the Moto Guard mockups.

## 1. Goal

Present Moto Guard as a calm, trustworthy protection application: deep navy creates authority, white cards make information easy to scan, and green is reserved for a readable confirmed healthy/protected state. The result must not look like an e-commerce, social, or generic dashboard application.

This is a visual and information-hierarchy change only. `ProtectionCoordinator`, `ProtectionViewModel`, `ProtectionUiState`, gateways, persistence, authorization, encryption, incident logic, delivery policy, and profile policy remain unchanged.

## 2. Visual system

| Token | Value | Purpose |
|---|---:|---|
| `brandNavy` | `#16324F` | header, selected navigation, primary action |
| `brandNavyRaised` | `#245B85` | restrained header tonal variation only |
| `canvas` | `#F8FAFC` | application background |
| `surface` | `#FFFFFF` | cards, dialogs, bottom navigation |
| `ink` | `#1C2530` | primary text and destructive-neutral controls |
| `mutedInk` | `#667085` | supporting text |
| `healthy` | `#168A63` | confirmed healthy/protected state, always with text/icon |
| `warning` | accessible amber derived from existing settings token | action-needed state, always with text/icon |
| `critical` | existing accessible error red | confirmed critical/error state only |

The only decorative tonal treatment is the top navy header. Cards have modest elevation, a single rounded-corner scale, and 4/8 dp spacing. Use one rounded outline vector-icon family. Do not use emoji, pictogram text, mixed icon weights, shopping/finance metaphors, or brand marks from the reference image.

## 3. Shell and navigation

The production route remains `Navigation.kt -> ProtectionAppScreen` with exactly three bottom destinations:

1. `ปกป้อง`
2. `เหตุการณ์`
3. `ตั้งค่า`

The selected destination uses navy plus the existing selected semantics; it must not be communicated by color alone. The navigation remains a fixed safe-area-aware bar and all targets remain at least 48 dp. No legacy `DashboardScreen` or `ui/main/MainScreen` work is permitted.

## 4. Screen design

### 4.1 ปกป้อง

Use a compact navy header and a primary white status card. The first information is the authoritative outcome and next action, not diagnostics:

- confirmed healthy/protected: readable healthy label plus a short explanation;
- not armed/setup required: the real blocker and the existing real action;
- warning/degraded/incident: state title, evidence already supplied by the read model, and the existing next action;
- only one primary arm/disarm action per state.

Profile selection, Entry commissioning, and Power commissioning retain their existing actions and state. Profile promises wrap naturally. Entry continues to show calibrated closed-position angle choices; Power continues to show charging and witness-light independently. Do not invent a generic slider, a site-wide outage, or a theft/forced-entry conclusion.

Runtime, sensor, battery, and delivery internals stay behind the existing advanced disclosure. The UI may restyle the disclosure but never infer health from its text.

### 4.2 เหตุการณ์

Use a navy title header and a vertical list of readable event cards/timeline. Each item renders the existing event title, timestamp, severity, lifecycle, and delivery facts through the presentation catalog. Loading, error/retry, empty, populated, and clear-history confirmation states are mandatory.

The mockup's filter chips are not in scope unless an authoritative filter action and data model are introduced in a separately approved change. The redesign must not add inert controls or fabricate a profile field on persisted incidents.

### 4.3 ตั้งค่า

Settings remains a real editable surface, not a fake overview. Group existing controls into visually clear cards in this order:

1. current profile and profile-specific detection setup;
2. event delivery and notification settings;
3. continuity: required permissions and battery/OEM keep-alive guidance;
4. advanced diagnostics and sensor configuration behind the existing disclosure.

No setting row gains a chevron, toggle, or button unless it already has a real action. Telegram pairing-code reveal remains initially masked and continues to set `FLAG_SECURE`; token and SMS-key fields remain password-transformed and are cleared after saving; pairing reset retains its confirmation. Direct Boot remains invisible local-only behavior: no remote services, keys, chat IDs, or encrypted incident data are added to the pre-unlock UI.

## 5. Content and accessibility

All normal copy is outcome-first Thai. Do not expose raw enums, diagnostic identifiers, unexplained units, secrets, or invented capability claims. Technical sensor names and units appear only in advanced diagnostics when the existing presentation model supplies them.

Every interactive element needs a Thai accessible name, role, current state, and useful hint. Preserve the single live-region precedence. Support 200% font scale, small width, portrait, landscape, and wrapping/vertical stacking rather than truncating Thai text. Color never carries status alone.

## 6. Execution slices

Work is intentionally split so it can stop at a clean review checkpoint:

1. baseline and shared navy visual tokens plus root shell/navigation;
2. protection presentation refresh;
3. events presentation refresh, including all existing state variants;
4. settings profile/setup visual refresh;
5. settings delivery/security visual refresh;
6. settings continuity/advanced diagnostic visual refresh;
7. contract, accessibility, build, and device-acceptance gates.

Each slice must add or update focused tests first, retain stable state ownership and security behavior, run one Gradle invocation at a time using `--no-daemon --max-workers=1`, and stop after its verification checkpoint. Slices that share `SettingsScreen.kt` execute sequentially, never in parallel.

## 7. Acceptance

Acceptance requires the three reachable screens to follow this design, no user-facing emoji in the reachable route, navigation/controls to remain accessible, all existing security-sensitive behavior to remain intact, focused tests plus full host/Android-test compilation/APK build to pass, and a fresh device review before visual or device-accessibility claims are made.

## 8. Explicit non-goals

- No new product feature, dependency, navigation destination, fake filter, or redesign of disconnected legacy screens.
- No protection algorithm, sensor threshold, incident, authorization, encryption, Telegram, SMS, GPS, or Direct Boot behavior change.
- No claim that a sensor or one signal proves theft, forced entry, or site-wide power failure.
