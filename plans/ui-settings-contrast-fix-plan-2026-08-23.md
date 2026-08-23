# UI Fix Plan — Settings screen dark-palette mismatch (2026-08-23)

Skill used: `ui-ux-pro-max` (review-existing-UI workflow + ux domain search on
"text contrast minimum readable low vision").

## Symptom (owner report)

Settings page renders with a **dark background and faint white text** that is hard to
read, while the rest of the app is paper-light.

## Root cause (verified in code)

The app's design system is **paper-light monochrome**:

- [`theme/Theme.kt`](../MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/theme/Theme.kt)
  — `OledSecurityColorScheme` is a `lightColorScheme` (white background, `#171717` ink).
- [`ui/ProtectionAppScreen.kt`](../MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt)
  — wraps all three destinations in `ProtectionMonochromeColorScheme` (white surfaces,
  black ink). Protection and Events screens follow it.

But [`theme/Color.kt`](../MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/theme/Color.kt)
still carries the **legacy "OLED High-Contrast Dark Slate" palette**, and
[`ui/settings/SettingsScreen.kt`](../MotorcycleAntiTheftSensor/app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt)
is the **only file in the app** still importing it:

| Legacy token | Value | Used as |
|---|---|---|
| `DarkSurface` | `#0E1726` | card backgrounds |
| `DarkSurfaceVariant` | `#1E293B` | inner rows/chips |
| `DarkBorder` | `#334155` | card borders |
| `TextHighEmphasis` | `#F8FAFC` (near-white) | headings/body on those dark cards |
| `TextMediumEmphasis` | `#CBD5E1` | secondary text |
| `TextMuted` | `#94A3B8` | captions |

Result: dark slate cards with white-ish text sitting inside a white app — low perceived
readability, jarring inconsistency, and several pairings fall below the WCAG AA 4.5:1
guideline returned by the skill search (e.g. muted slate text on slate surfaces).

Skill guidance applied: minimum 4.5:1 text contrast; darker text on light backgrounds;
body text ≥16sp; keep semantic status colors distinguishable.

## Fix strategy

**Chosen: migrate SettingsScreen onto the existing paper-light theme** (matches the
design-system direction already used by Protection/Events and mandated by the
three-profiles spec section 3.6 "paper-light tokens"). No new dependency, no theme
file changes required.

Token mapping (all via `MaterialTheme.colorScheme` so future re-themes stay coherent):

| Legacy (dark) | Replacement (paper-light) |
|---|---|
| `DarkSurface` (card bg) | `colorScheme.surface` (white) + `surfaceVariant` `#EEEEEC` container tint |
| `DarkSurfaceVariant` (rows) | `colorScheme.surfaceVariant` |
| `DarkBorder` | `colorScheme.outlineVariant` (`#EEEEEC`) or `outline` (`#767676`) for emphasis |
| `TextHighEmphasis` | `colorScheme.onSurface` (`#171717`, ≈16:1 on white) |
| `TextMediumEmphasis` | `colorScheme.onSurfaceVariant` (`#4B4B4B`, ≈9:1) |
| `TextMuted` | `colorScheme.outline` (`#767676`, ≈4.6:1 — captions only) |

Status accents stay semantic but switch to their **light-background-safe variants**
for text/icons on white (≥4.5:1):

| Accent | On-dark today | On-light replacement |
|---|---|---|
| `TrustBlue` `#3B82F6` | links/buttons | `TrustBlueDark` `#1D4ED8` for text; button containers use theme primary |
| `WarningAmber` `#F59E0B` | warning text | `#B45309` (amber-700) for text; amber fill allowed for chips w/ dark ink |
| `AlertRed` `#EF4444` | error text | `AlertRedDark` `#B91C1C` for text; red fills keep white ink |
| `ArmedGreen` `#10B981` | armed text | `ArmedGreenDark` `#047857` for text |
| `CyanAccent` `#06B6D4` | progress/highlights | decorative only (progress track/spinner); never body text |

Inputs: `OutlinedTextFieldDefaults.colors(...)` drop custom dark overrides → default
themed colors. Sliders: `SliderDefaults.colors(...)` → theme primary track/thumb.
Buttons: remove explicit dark container colors → theme defaults (primary = black ink).

## Tasks

1. **Task A — token swap in SettingsScreen.kt** (single file): replace the seven dark
   imports and per-section usages with the mapping above, section by section
   (persistent-guidance, sensor fusion, telegram, sms, permissions, keep-alive,
   diagnostics, audio AI, dialogs).
2. **Task B — accent audit**: every remaining literal accent color must be either a
   light-safe variant (table above) or decorative-only; no body text below 4.5:1.
3. **Task C — cleanup**: after swap, verify nothing else references
   `DarkBackground/DarkSurface/DarkBorder/Text*Emphasis/TextMuted`; if unused, mark the
   legacy block in `theme/Color.kt` deprecated (do not delete yet — slice 4 may want a
   real dark theme later).
4. **Task D — verification**:
   - `compileDebugKotlin` + full host gate (`testDebugUnitTest`,
     `compileDebugAndroidTestKotlin`) — catches any androidTest color assertions.
   - Build + install `-r` on `JUCDU18811013149`, screenshot Settings top-to-bottom
     (all 8 sections) under `plans/uifix-*.png`; owner eyeballs readability.
5. **Task E — commit + sync**: one commit
   `fix(ui): migrate settings screen to paper-light theme tokens`, ff-merge into
   `feature/motorcycle-guard-protection`.

Estimated effort: 1.5–2 h including visual pass.

## Explicitly out of scope

- No dark-mode support in this fix (slice 4 candidate; legacy tokens kept for it).
- No layout/touch-target changes (already 48 dp compliant).
- No changes to Protection/Events screens (already paper-light).
