# Human-Readable Alerts and Guidance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Protection, Snackbar, Android notification, Events, Telegram incident/status, and SMS fallback present the same truthful incident and protection meaning in readable Thai without exposing raw enums, diagnostics, exceptions, secrets, or unsupported delivery claims.

**Architecture:** `ProtectionSnapshot` and `SecurityIncident` remain authoritative. Pure factories convert typed domain data into immutable `ProtectionMessagePresentation` and `IncidentMessagePresentation`; channel formatters accept only those presentations. Delivery records remain typed and per-channel, while persistent UI is reconstructed from typed snapshot/history/recovery state rather than stored localized strings.

**Tech Stack:** Kotlin, Android/Jetpack Compose, `StateFlow`, JUnit4 host tests behind pure/injected boundaries, AndroidX Compose UI tests, encrypted preferences boundary, app-private incident repository, Gradle/JBR, ADB, Telegram and controlled SMS acceptance.

**Spec:** `docs/superpowers/specs/2026-08-20-configurable-sensor-fusion-design.md`, especially section 12, “Human-readable alerts and guidance.”

## Global Constraints

- Work from the owner-approved clean worktree; read `AGENTS.md` and the complete approved spec before editing. `AI_WORKFLOW.md` was absent during plan preparation; do not invent its contents.
- The checkout is intentionally dirty. Preserve all pre-existing tracked and untracked work. Never use `git reset`, `git clean`, `git checkout --`, broad staging, file deletion, or a commit that captures unrelated hunks.
- Do not start production edits in the current dirty checkout. First obtain an owner-approved integration baseline and use `superpowers:using-git-worktrees` to create a clean isolated worktree from that exact content. If any task target is dirty at task start, stop; neither exact-path staging nor patch staging proves ownership of pre-existing hunks. Inspect `git diff --cached` before every commit.
- Do not add a dependency, Android permission, Telegram command, network endpoint, or new independent alert path.
- `ProtectionCoordinator` remains the state owner. Presentation code is pure and cannot arm/disarm, start listeners, send messages, mutate configuration, or create incidents.
- Sensor adapters and listeners never format or send UI, Telegram, notification, or SMS copy.
- The companion multi-sensor plan owns the canonical `SensorCapability` values `MOVEMENT`, `ROTATION`, `MAGNETIC`, `LIGHT`, and `PROXIMITY`, and the structured `SensorConfigurationApplyResult`. This plan consumes those typed values and must not create a second capability or apply-result model.
- Use exactly this neutral guidance for every real incident: `ตรวจสอบสถานการณ์และดำเนินการตามความเหมาะสม`.
- SMS contains severity, plain-language event, time, compact protection state, and incident ID. SMS never contains coordinates, a map URL, location label, accuracy, fix age, destination number, key, token, or Chat ID.
- Telegram incident location is limited to already validated presentation fields: human-readable label, fix age, and accuracy. A formatter never receives raw latitude/longitude or raw location diagnostics.
- Channel formatters cannot accept `SecurityIncident`, `ProtectionSnapshot`, `SensorObservation`, raw exception, raw diagnostic, raw degradation string, or secret-bearing settings objects.
- No user-facing fallback may return `Enum.name`, lowercase/prettified enum text, raw reason text, exception text, implementation instructions, unresolved `{placeholder}`, unexplained delta, or symbol/color-only meaning.
- `ALERT_ACTIVE`, `ARMED_DEGRADED`, `OFFLINE`, setup blockers, and failed configuration apply results are persistent until resolved or explicitly acknowledged by their typed policy. Snackbar remains immediate acknowledgement only.
- Repeated evidence updates update the same incident and persistent card/history record. Only open, severity escalation, close/material protection change, or an existing configured reminder may emit another external notification.
- Delivery wording is bounded by evidence: Telegram `SENT` means the Telegram API accepted the send; SMS `SENT` means Android confirmed all message parts were sent from the device. Neither means the human recipient read or received it.
- Persist domain codes, timestamps, incident IDs, transition types, and delivery attempts only. Never persist localized titles, bodies, actions, formatted timestamps, emoji, or channel-ready strings.
- Preserve Demo isolation. A typed demo presentation must show `DEMO`; Demo can never become SMS-eligible. Do not add a synthetic Demo producer in this plan.
- All timestamps shown to the user use the device locale and time zone. Freshness calculations continue to use monotonic elapsed time where the source provides it.
- Use UTF-8 Thai text. Units are `m/s²`, `rad/s`, `°`, `µT`, `lux`, `dB`, `%`, `°C`, seconds, and meters as appropriate.
- Android notification remains an ongoing foreground-service notification unless a separately approved design adds another channel. This plan improves copy and expanded content without adding sound, vibration, or alert spam.
- Tests and source must not contain real bot tokens, Chat IDs, phone numbers, TOTP secrets, AES keys, precise real locations, or user data. Use obviously synthetic fixtures only.

Run Task 0 repository commands from `D:\security`. After the approved clean worktree is selected, run every `app/...` search and Gradle command from its `MotorcycleAntiTheftSensor` directory:

```powershell
$approvedProjectRoot = Read-Host 'Enter the approved clean worktree MotorcycleAntiTheftSensor path'
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
Set-Location -LiteralPath $approvedProjectRoot
git status --short
```

## Reviewed Starting Evidence

- `protection/IncidentMessageFormatter.kt:19,39-45,77,94-102` exposes raw incident/sensor enum names, diagnostics, and unexplained deltas.
- `protection/UserGuidance.kt:191-192,247-257,273,289,329,337,345,353,410-415` contains internal copy, generic placeholders, and raw enum resolution.
- `protection/IncidentMessageFormatter.kt:13-16` incorrectly treats `IncidentLifecycle.INTERRUPTED` as an escalation; escalation is an `IncidentUpdate.Escalated`, not a lifecycle value.
- `protection/ProtectionRuntimeGraph.kt:81-89` discards the formatted SMS argument and sends coordinates through `SmsFallbackManager`; the live SMS path therefore violates the approved contract even though formatter unit tests pass.
- `protection/IncidentDeliveryCoordinator.kt:89-92` collapses Telegram or SMS success into one aggregate state; `telegram/ProtectionStatusFormatter.kt:70` then labels that aggregate as Telegram.
- `service/SensorService.kt:461-473` emits English notification text and raw permission/degradation reasons.
- `ui/ProtectionUiModels.kt:191-200` hard-codes only setup/offline/permission persistent guidance and ignores the catalog persistence flag.
- `ui/events/EventsScreen.kt:127-140,208-211` formats domain enums directly and omits incident ID, real evidence, chronology, protection outcome, and per-channel delivery truth.
- `ui/ProtectionViewModel.kt:403-447` can expose repository/settings exception messages.
- `protection/FileIncidentRepository.kt:122-174` persists typed domain data plus internal diagnostics and exact location. Presentation must allowlist fields and never render persisted diagnostic/detail text.
- `service/AlertDispatcher.kt` has tests but no production call site. It is a legacy parallel formatter/transport and must not survive as a second alert truth path after migration.
- `telegram/ProtectionStatusProjection.kt` and related tests were untracked during plan preparation. Treat them as user work and integrate carefully.

## File Responsibility Map

### New production files

- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/MessagePresentations.kt` — immutable channel-safe presentation models and typed presentation trigger/persistence policy.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/PresentationTextCatalog.kt` — exhaustive Thai labels, safe issue/action policy, and device-locale timestamp formatting.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessagePresentationFactory.kt` — pure incident/evidence/location/delivery projection.
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionMessagePresentationFactory.kt` — pure status, persistent-card, capability-impact, and last-incident projection.
- `app/src/main/java/com/example/motorcycleantitheftsensor/service/ForegroundNotificationPresenter.kt` — pure title/collapsed/expanded notification copy and fingerprint input.

### Existing production files to modify

- `protection/UserGuidance.kt` — retain typed command/system guidance while removing internal placeholders and raw enum substitution.
- `protection/SecurityIncident.kt` — typed presentation trigger history and channel delivery summary input; no localized strings.
- `protection/IncidentEngine.kt` — append bounded typed incident transitions at the authoritative update points.
- `protection/FileIncidentRepository.kt` — schema v5 migration for typed transitions only.
- `protection/IncidentUpdateDeliveryPolicy.kt` — expose explicit delivery/presentation trigger without changing deduplication policy.
- `protection/IncidentMessageFormatter.kt` — format channel-safe presentations only.
- `protection/IncidentDeliveryCoordinator.kt` — build one canonical presentation, format channels, and record per-channel attempts using an injected clock.
- `protection/ProtectionRuntimeGraph.kt` — forward explicit trigger and the actual formatted SMS payload; remove location from the SMS boundary.
- `protection/ProtectionModels.kt` — retain per-channel last-incident delivery summary and typed persistent notice in authoritative state.
- `protection/ProtectionCoordinator.kt` — publish/acknowledge/clear typed persistent notices and retain per-channel delivery attempts in `IncidentSummary`.
- `protection/ProtectionSnapshotStore.kt` — process-recovery persistence for typed notice codes/IDs/timestamps only.
- `protection/ProtectionStateTelegramNotifier.kt` — consume shared protection presentation; never echo raw reasons.
- `telegram/ProtectionStatusProjection.kt` — delegate reusable truth projection to the shared factory.
- `telegram/ProtectionStatusFormatter.kt` — format `ProtectionMessagePresentation` with action/outcome first.
- `telephony/SmsFallbackManager.kt` — encrypt and send the supplied SMS presentation without constructing `LOC:`.
- `telephony/EncryptedSmsCodec.kt` — update stale GPS-specific contract comments only; cryptographic format and implementation remain unchanged.
- `service/SensorService.kt` — consume `ForegroundNotificationPresenter` and render collapsed/expanded copy.
- `service/ForegroundNotificationPolicy.kt` — fingerprint all visible notification fields.
- `ui/ProtectionUiModels.kt` — carry shared status/incident/event presentations rather than raw enums/details.
- `ui/ProtectionViewModel.kt` — build presentations, sanitize failures, and expose acknowledge/navigation actions.
- `ui/ProtectionAppScreen.kt` — one-shot Snackbar acknowledgement with stable presentation identity.
- `ui/protection/ProtectionScreen.kt` — persistent status/incident card with action/details and accessible text hierarchy.
- `ui/events/EventsScreen.kt` — Thai chronology/evidence/delivery rendering.
- `ui/settings/SettingsScreen.kt` — render failed-apply persistent notice and acknowledgement without raw apply diagnostics.
- `service/AlertDispatcher.kt` — remove after the authoritative path is verified and staged separately.

### Test files

- Create `app/src/test/java/com/example/motorcycleantitheftsensor/protection/PresentationTextCatalogTest.kt`.
- Create `app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentMessagePresentationFactoryTest.kt`.
- Create `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionMessagePresentationFactoryTest.kt`.
- Create `app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentDeliveryTruthPathIntegrationTest.kt`.
- Create `app/src/test/java/com/example/motorcycleantitheftsensor/service/ForegroundNotificationPresenterTest.kt`.
- Modify existing formatter, guidance, incident-engine/repository, delivery, status, SMS, coordinator/store, ViewModel, notification-policy, and Compose tests named in each task.

---

### Task 0: Freeze the Dirty Baseline and Confirm Ownership

**Files:**
- Read only: `AGENTS.md`, approved spec, every path in the File Responsibility Map

**Interfaces:**
- Consumes: current dirty checkout and approved written spec
- Produces: a verified pre-edit inventory; no source or document changes

- [ ] **Step 1: Verify repository identity and preserve current changes**

```powershell
git rev-parse --show-toplevel
git branch --show-current
git rev-parse HEAD
git status --short
git diff --check
```

Expected: repository root resolves to `D:/security`; the implementation agent records branch/HEAD and identifies every pre-existing modification touching this plan. Any unexpected overlap is reviewed before edits.

- [ ] **Step 2: Confirm live and orphan call sites**

```powershell
rg -n --glob '!**/build/**' "IncidentMessageFormatter|IncidentDeliveryCoordinator|sendEncryptedSmsAlert|ProtectionStatusProjection|ProtectionStatusFormatter|notificationText|persistentGuidance|AlertDispatcher" app/src/main app/src/test app/src/androidTest
```

Expected: `IncidentDeliveryCoordinator` is the authoritative live incident path; `AlertDispatcher` has no production caller; the formatted SMS argument is currently discarded in `ProtectionRuntimeGraph`.

- [ ] **Step 3: Run the current focused baseline without claiming feature acceptance**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.IncidentMessageFormatterTest" --tests "com.example.motorcycleantitheftsensor.protection.IncidentDeliveryCoordinatorTest" --tests "com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalogTest" --tests "com.example.motorcycleantitheftsensor.telegram.ProtectionStatusFormatterTest" --tests "com.example.motorcycleantitheftsensor.telephony.SmsFallbackManagerTest" --tests "com.example.motorcycleantitheftsensor.service.ForegroundNotificationPolicyTest"
```

Expected: record actual pass/fail. Passing current tests is baseline evidence only because several assertions currently require unsafe copy.

---

### Task 1: Define Channel-Safe Presentation Models and Exhaustive Thai Text Policy

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/MessagePresentations.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/PresentationTextCatalog.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/UserGuidance.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/PresentationTextCatalogTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/UserGuidanceCatalogTest.kt`

**Interfaces:**
- Consumes: `IncidentType`, `IncidentSeverity`, `ProtectionState`, `DeliveryChannel`, `DeliveryState`, companion-plan `SensorCapability`, `SensorSource`, `SensorRole`, and `SensorConfigurationApplyResult`
- Produces: `IncidentPresentationTrigger`, `MessagePersistence`, `OwnerActionPresentation`, `EvidenceItemPresentation`, `IncidentLocationPresentation`, `ChannelDeliveryPresentation`, `IncidentMessagePresentation`, `ProtectionMessagePresentation`, and exhaustive localized label/policy functions

- [ ] **Step 1: Write RED tests for exhaustive labels, neutral guidance, persistence, and redaction**

```kotlin
@Test
fun realIncidentAlwaysUsesApprovedNeutralGuidance() {
    assertEquals(
        "ตรวจสอบสถานการณ์และดำเนินการตามความเหมาะสม",
        PresentationTextCatalog.realIncidentActionTh,
    )
}

@Test
fun labelsNeverFallBackToEnumNames() {
    IncidentType.entries.forEach { type ->
        val label = PresentationTextCatalog.incidentHeadlineTh(type)
        assertTrue(label.isNotBlank())
        assertFalse(label.contains(type.name, ignoreCase = true))
    }
    ProtectionState.entries.forEach { state ->
        val label = PresentationTextCatalog.protectionOutcomeTh(state)
        assertTrue(label.isNotBlank())
        assertFalse(label.contains(state.name, ignoreCase = true))
    }
    SensorCapability.entries.forEach { capability ->
        assertFalse(PresentationTextCatalog.sensorCapabilityTh(capability).contains(capability.name, ignoreCase = true))
    }
    SensorSource.entries.forEach { source ->
        assertFalse(PresentationTextCatalog.sensorSourceTh(source).contains(source.name, ignoreCase = true))
    }
    SensorRole.entries.forEach { role ->
        assertFalse(PresentationTextCatalog.sensorRoleTh(role).contains(role.name, ignoreCase = true))
    }
}

@Test
fun importantStatesHavePersistentPolicy() {
    listOf(
        ProtectionState.ALERT_ACTIVE,
        ProtectionState.ARMED_DEGRADED,
        ProtectionState.OFFLINE,
        ProtectionState.SETUP_REQUIRED,
    ).forEach { state ->
        assertNotEquals(MessagePersistence.TRANSIENT, PresentationTextCatalog.persistenceFor(state))
    }
}

@Test
fun catalogContainsNoInternalCopyOrUnresolvedPlaceholder() {
    GuidanceCode.entries.forEach { code ->
        val copy = UserGuidanceCatalog.content(code)
        val text = listOfNotNull(copy.titleTh, copy.bodyTh, copy.telegramTh).joinToString(" ")
        assertFalse(text.contains("update status card only", ignoreCase = true))
        assertFalse(text.contains("รวมใน /status"))
        assertFalse(Regex("\\{[A-Za-z][A-Za-z0-9]*}").containsMatchIn(text))
    }
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.PresentationTextCatalogTest" --tests "com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalogTest"
```

Expected: FAIL because the presentation models/catalog do not exist and current guidance contains internal copy/placeholders.

- [ ] **Step 3: Add immutable presentation types**

Implement these exact core types in `MessagePresentations.kt`:

```kotlin
enum class IncidentPresentationTrigger { OPENED, UPDATED, ESCALATED, CLOSED, INTERRUPTED }

enum class MessagePersistence { TRANSIENT, UNTIL_STATE_CHANGES, UNTIL_ACKNOWLEDGED }

data class OwnerActionPresentation(
    val action: GuidanceAction,
    val labelTh: String,
)

data class EvidenceItemPresentation(
    val headlineTh: String,
    val secondaryDetailTh: String?,
    val accessibilityTextTh: String,
)

data class IncidentLocationPresentation(
    val labelTh: String?,
    val fixAgeTh: String?,
    val accuracyTh: String?,
)

data class ChannelDeliveryPresentation(
    val channel: DeliveryChannel,
    val state: DeliveryState,
    val outcomeTh: String,
    val attemptedAtMs: Long?,
)

data class IncidentMessagePresentation(
    val incidentId: String,
    val trigger: IncidentPresentationTrigger,
    val headlineTh: String,
    val summaryTh: String,
    val severity: IncidentSeverity,
    val severityLabelTh: String,
    val occurredAtMs: Long,
    val updatedAtMs: Long,
    val occurredAtTh: String,
    val protectionOutcomeTh: String,
    val action: OwnerActionPresentation,
    val evidence: List<EvidenceItemPresentation>,
    val location: IncidentLocationPresentation?,
    val deliveries: List<ChannelDeliveryPresentation>,
    val persistence: MessagePersistence,
    val isDemo: Boolean,
)

data class IssuePresentation(
    val headlineTh: String,
    val impactTh: String,
    val remediationTh: String?,
    val action: GuidanceAction,
)

data class ProtectionMessagePresentation(
    val identity: String,
    val headlineTh: String,
    val protectionOutcomeTh: String,
    val action: OwnerActionPresentation,
    val severity: GuidanceSeverity,
    val persistence: MessagePersistence,
    val operatingCapabilitiesTh: List<String>,
    val issues: List<IssuePresentation>,
    val serviceSummaryTh: String,
    val telegramSummaryTh: String,
    val batterySummaryTh: String,
    val lastIncident: IncidentMessagePresentation?,
    val snapshotRevision: Long,
)

fun interface PresentationTimeFormatter {
    fun format(epochMs: Long): String
}
```

Do not place domain objects, raw reasons, raw diagnostics, raw coordinates, or exception fields in these types.

- [ ] **Step 4: Implement exhaustive Thai labels and typed guidance policy**

Use exhaustive `when` expressions with no raw fallback. The real-incident constant is exact:

```kotlin
object PresentationTextCatalog {
    const val realIncidentActionTh = "ตรวจสอบสถานการณ์และดำเนินการตามความเหมาะสม"

    fun incidentHeadlineTh(type: IncidentType): String = when (type) {
        IncidentType.VIBRATION -> "รถอาจถูกสั่นหรือขยับ"
        IncidentType.TAMPER -> "รถอาจถูกเคลื่อนย้ายหรืองัดแงะ"
        IncidentType.POWER -> "ระบบไฟของรถเปลี่ยนแปลงผิดปกติ"
        IncidentType.THERMAL -> "ตรวจพบความร้อนผิดปกติ"
        IncidentType.AUDIO -> "ตรวจพบเสียงผิดปกติใกล้รถ"
    }

    fun severityTh(severity: IncidentSeverity): String = when (severity) {
        IncidentSeverity.WARNING -> "เฝ้าระวัง"
        IncidentSeverity.CRITICAL -> "สูง"
    }

    fun protectionOutcomeTh(state: ProtectionState): String = when (state) {
        ProtectionState.SETUP_REQUIRED -> "ระบบยังป้องกันไม่ได้จนกว่าจะตั้งค่าให้ครบ"
        ProtectionState.DISARMED_ONLINE -> "การป้องกันปิดอยู่ แต่การเชื่อมต่อยังทำงาน"
        ProtectionState.ARMING -> "ระบบกำลังปรับเทียบก่อนเริ่มป้องกัน"
        ProtectionState.ARMED_HEALTHY -> "การป้องกันกำลังทำงานปกติ"
        ProtectionState.ARMED_DEGRADED -> "การป้องกันยังทำงาน แต่ความสามารถบางส่วนลดลง"
        ProtectionState.ALERT_ACTIVE -> "การป้องกันยังทำงานและกำลังจัดการเหตุการณ์"
        ProtectionState.OFFLINE -> "บริการป้องกันหยุดทำงาน"
    }
}
```

The same object implements exhaustive `sensorCapabilityTh`, `sensorSourceTh`, and `sensorRoleTh` functions. Settings Task 4 consumes these functions directly; neither Settings nor Telegram may keep a parallel enum-prettification fallback.

Implement `DevicePresentationTimeFormatter` with injected `Locale` and `TimeZone` providers so tests are deterministic and production follows device settings:

```kotlin
class DevicePresentationTimeFormatter(
    private val localeProvider: () -> Locale = Locale::getDefault,
    private val timeZoneProvider: () -> TimeZone = TimeZone::getDefault,
) : PresentationTimeFormatter {
    override fun format(epochMs: Long): String {
        val formatter = DateFormat.getDateTimeInstance(
            DateFormat.MEDIUM,
            DateFormat.SHORT,
            localeProvider(),
        )
        formatter.timeZone = timeZoneProvider()
        return formatter.format(Date(epochMs))
    }
}
```

Replace `GuidanceDetail` raw-enum expansion with typed label calls. Replace internal placeholder copy with localized UI copy or `telegramTh = null` when no Telegram message should be emitted. Never substitute default `Permission` or `Feature`.

- [ ] **Step 5: Run GREEN tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.PresentationTextCatalogTest" --tests "com.example.motorcycleantitheftsensor.protection.UserGuidanceCatalogTest"
```

Expected: PASS with exhaustive Thai labels, exact neutral guidance, persistent state policies, and no internal placeholders.

- [ ] **Step 6: Commit the typed presentation foundation**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/protection/MessagePresentations.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/PresentationTextCatalog.kt
git add -p app/src/main/java/com/example/motorcycleantitheftsensor/protection/UserGuidance.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/UserGuidanceCatalogTest.kt
git add app/src/test/java/com/example/motorcycleantitheftsensor/protection/PresentationTextCatalogTest.kt
git diff --cached --check
git diff --cached
git commit -m "feat: add safe alert presentation contract"
```

Expected commit: only presentation types, catalog/guidance changes, and their tests.

---

### Task 2: Persist Typed Incident Chronology and Explicit Presentation Triggers

This task and Runtime Task 8 are one shared incident-model/persistence task. Execute them in the same review and schema-v5 commit so source-aware evidence and typed chronology cannot define two incompatible version-5 layouts.

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/SecurityIncident.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentEngine.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentUpdateDeliveryPolicy.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepository.kt`
- Modify tests: `IncidentEngineTest.kt`, `IncidentUpdateDeliveryPolicyTest.kt`, `FileIncidentRepositoryTest.kt`

**Interfaces:**
- Consumes: `IncidentUpdate` and existing incident persistence versions 1–4
- Produces: typed `IncidentOrigin`, bounded `IncidentTransition` history, and `IncidentUpdate.presentationTrigger()`; repository schema version 5 stores codes/timestamps only

- [ ] **Step 1: Write RED tests for explicit trigger semantics and chronology**

```kotlin
@Test
fun explicitTriggerDoesNotInferEscalationFromInterruptedLifecycle() {
    assertEquals(IncidentPresentationTrigger.OPENED, IncidentUpdate.Opened(openIncident()).presentationTrigger())
    assertEquals(IncidentPresentationTrigger.UPDATED, IncidentUpdate.Updated(openIncident()).presentationTrigger())
    assertEquals(IncidentPresentationTrigger.ESCALATED, IncidentUpdate.Escalated(openIncident()).presentationTrigger())
    assertEquals(IncidentPresentationTrigger.CLOSED, IncidentUpdate.Closed(closedIncident()).presentationTrigger())
}

@Test
fun repositoryRoundTripPreservesTypedTransitionsWithoutLocalizedCopy() {
    val incident = openIncident().copy(
        transitions = listOf(
            IncidentTransition(IncidentTransitionKind.OPENED, 1_000L),
            IncidentTransition(IncidentTransitionKind.SEVERITY_ESCALATED, 2_000L),
        ),
    )
    repository.upsert(incident)
    assertEquals(incident.transitions, repository.findById(incident.id)?.transitions)
    assertFalse(file.readBytes().toString(Charsets.ISO_8859_1).contains("ตรวจสอบสถานการณ์"))
}

@Test
fun nonEscalatingEvidenceUpdatesRemainOneIncident() {
    val opened = engine.process(primaryObservation()) as IncidentUpdate.Opened
    val updated = engine.process(supportingObservation()) as IncidentUpdate.Updated
    assertEquals(opened.incident.id, updated.incident.id)
    assertEquals(1, updated.incident.transitions.count { it.kind == IncidentTransitionKind.EVIDENCE_UPDATED })
}
```

- [ ] **Step 2: Run the focused tests and verify RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.IncidentEngineTest" --tests "com.example.motorcycleantitheftsensor.protection.IncidentUpdateDeliveryPolicyTest" --tests "com.example.motorcycleantitheftsensor.protection.FileIncidentRepositoryTest"
```

Expected: FAIL because transitions and explicit trigger mapping are absent.

- [ ] **Step 3: Add typed bounded chronology**

```kotlin
enum class IncidentOrigin { REAL, DEMO }

enum class IncidentTransitionKind {
    OPENED,
    EVIDENCE_UPDATED,
    SEVERITY_ESCALATED,
    CLOSED,
    INTERRUPTED,
}

data class IncidentTransition(
    val kind: IncidentTransitionKind,
    val atMs: Long,
)
```

Add `origin: IncidentOrigin = IncidentOrigin.REAL` and `transitions: List<IncidentTransition> = emptyList()` to `SecurityIncident`. `IncidentEngine` appends transitions at its existing authoritative open/update/escalate/close/interruption points and retains at most 64 newest entries. Production detectors create `REAL`; an existing typed Demo producer, when present, must pass `DEMO` explicitly. It never stores formatted copy.

Add the explicit mapping:

```kotlin
fun IncidentUpdate.presentationTrigger(): IncidentPresentationTrigger = when (this) {
    IncidentUpdate.Ignored -> error("Ignored incident updates have no presentation trigger")
    is IncidentUpdate.Opened -> IncidentPresentationTrigger.OPENED
    is IncidentUpdate.Updated -> IncidentPresentationTrigger.UPDATED
    is IncidentUpdate.Escalated -> IncidentPresentationTrigger.ESCALATED
    is IncidentUpdate.Closed -> IncidentPresentationTrigger.CLOSED
}
```

`UPDATED` is persist-only under `IncidentUpdateDeliveryPolicy`; its trigger is used only for reconstructing the active incident presentation, never to send another external message.

- [ ] **Step 4: Add repository schema v5 with truthful legacy migration**

Set version 4 as legacy and version 5 as current. First write the optional source-aware evidence metadata defined by Runtime Task 8, then write origin plus transition count/kind/timestamp after existing lifecycle timestamps. Versions 2–4 migrate to `IncidentOrigin.REAL`; the existing version-1 policy continues to discard legacy Demo records. For versions 1–4 default source-aware fields to null and synthesize only transitions directly supported by stored facts:

```kotlin
private fun SecurityIncident.withLegacyTransitions(): SecurityIncident {
    val recovered = buildList {
        add(IncidentTransition(IncidentTransitionKind.OPENED, openedAtMs))
        when (lifecycle) {
            IncidentLifecycle.OPEN -> Unit
            IncidentLifecycle.CLOSED -> add(
                IncidentTransition(IncidentTransitionKind.CLOSED, closedAtMs ?: updatedAtMs),
            )
            IncidentLifecycle.INTERRUPTED -> add(
                IncidentTransition(IncidentTransitionKind.INTERRUPTED, updatedAtMs),
            )
        }
    }
    return copy(transitions = recovered)
}
```

Do not invent legacy escalation entries because version 4 did not persist that fact.

- [ ] **Step 5: Run GREEN tests and the full repository migration suite**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.IncidentEngineTest" --tests "com.example.motorcycleantitheftsensor.protection.IncidentUpdateDeliveryPolicyTest" --tests "com.example.motorcycleantitheftsensor.protection.FileIncidentRepositoryTest"
```

Expected: PASS for versions 1–5, one incident per correlated event, explicit escalation trigger, and no localized string persistence.

- [ ] **Step 6: Commit typed incident chronology**

```powershell
git add -- app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorObservation.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/SecurityIncident.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/SensorObservationProcessor.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentEngine.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentUpdateDeliveryPolicy.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepository.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/SensorObservationProcessorTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentEngineTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentUpdateDeliveryPolicyTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/FileIncidentRepositoryTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ConfigurableSensorFusionTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/AudioMovementFusionIntegrationTest.kt
git diff --cached --check
git diff --cached
git commit -m "feat: persist fused incident evidence and chronology"
```

---

### Task 3: Build Pure Incident and Protection Presentation Factories

**Files:**
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessagePresentationFactory.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionMessagePresentationFactory.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusProjection.kt`
- Create tests: `IncidentMessagePresentationFactoryTest.kt`, `ProtectionMessagePresentationFactoryTest.kt`
- Modify test: `telegram/ProtectionStatusProjectionTest.kt`

**Interfaces:**
- Consumes: typed incident, trigger, authoritative snapshot, validated `LocationPresentation`, device-locale timestamp formatter, typed capability/apply result
- Produces: channel-safe immutable presentations containing no raw diagnostic, reason, coordinate, or exception field

- [ ] **Step 1: Write RED incident factory tests**

Required tests and assertions:

```kotlin
@Test
fun rawDiagnosticsEnumsAndNonFiniteValuesNeverReachPresentation() {
    val incident = incidentWithEvidence(
        diagnostic = "accelerometer_magnitude_sensitivity_5 secret=forbidden",
        normalizedValue = Double.NaN,
        baselineDelta = Double.POSITIVE_INFINITY,
    )
    val presentation = factory.present(incident, IncidentPresentationTrigger.OPENED, healthySnapshot(), null)
    val text = presentation.flattenForTest()
    assertFalse(text.contains("accelerometer_magnitude_sensitivity_5"))
    assertFalse(text.contains("secret="))
    assertFalse(text.contains("NaN"))
    assertFalse(text.contains("Infinity"))
    assertFalse(text.contains(incident.type.name))
}

@Test
fun presentationUsesPlainLanguageEvidenceWithUnits() {
    val presentation = factory.present(multisensorIncident(), IncidentPresentationTrigger.OPENED, healthySnapshot(), null)
    assertTrue(presentation.evidence.any { it.headlineTh == "ตรวจพบแรงสั่นต่อเนื่อง 2.1 วินาที" })
    assertTrue(presentation.evidence.any { it.headlineTh == "มุมของรถเปลี่ยนประมาณ 18°" })
    assertTrue(presentation.evidence.any { it.headlineTh == "สนามแม่เหล็กรอบรถเปลี่ยนจากค่าตอนเปิดระบบ" })
    assertTrue(presentation.evidence.any { it.headlineTh == "แสงใต้เบาะเพิ่มขึ้นจากค่าตอนเปิดระบบ" })
    assertTrue(presentation.evidence.any { it.headlineTh == "สถานะวัตถุใกล้โทรศัพท์เปลี่ยนจาก ใกล้ เป็น ไกล" })
}

@Test
fun locationProjectionContainsNoCoordinatesOrMapUrl() {
    val presentation = factory.present(incident(), IncidentPresentationTrigger.OPENED, healthySnapshot(), validatedLocation())
    val location = requireNotNull(presentation.location)
    val text = listOfNotNull(location.labelTh, location.fixAgeTh, location.accuracyTh).joinToString(" ")
    assertFalse(Regex("[-+]?\\d{1,3}\\.\\d{4,}").containsMatchIn(text))
    assertFalse(text.contains("maps.google.com"))
}

@Test
fun typedDemoOriginProducesExplicitDemoMarker() {
    val presentation = factory.present(
        incident().copy(origin = IncidentOrigin.DEMO),
        IncidentPresentationTrigger.OPENED,
        healthySnapshot(),
        null,
    )
    assertTrue(presentation.isDemo)
    assertTrue(presentation.headlineTh.contains("DEMO"))
}
```

- [ ] **Step 2: Write RED protection factory tests**

```kotlin
@Test
fun healthyStatusSaysNoActionRequired() {
    val presentation = factory.present(healthySnapshot(), nowWallClockMs, nowElapsedMs)
    assertEquals("ไม่ต้องดำเนินการ", presentation.action.labelTh)
    assertEquals(MessagePersistence.TRANSIENT, presentation.persistence)
}

@Test
fun unknownLegacyReasonBecomesGenericSafeIssue() {
    val snapshot = degradedSnapshot(setOf("private/path token=abc stacktrace"))
    val presentation = factory.present(snapshot, nowWallClockMs, nowElapsedMs)
    val text = presentation.issues.joinToString(" ") { "${it.headlineTh} ${it.impactTh} ${it.remediationTh}" }
    assertTrue(text.contains("ความสามารถบางส่วนไม่พร้อมใช้งาน"))
    assertFalse(text.contains("private/path"))
    assertFalse(text.contains("token="))
    assertFalse(text.contains("stacktrace"))
}
```

- [ ] **Step 3: Run tests and verify RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.IncidentMessagePresentationFactoryTest" --tests "com.example.motorcycleantitheftsensor.protection.ProtectionMessagePresentationFactoryTest" --tests "com.example.motorcycleantitheftsensor.telegram.ProtectionStatusProjectionTest"
```

Expected: FAIL because the factories do not exist and status projection is Telegram-specific.

- [ ] **Step 4: Implement the incident factory as an allowlist**

Constructor and public method:

```kotlin
class IncidentMessagePresentationFactory(
    private val timeFormatter: PresentationTimeFormatter,
) {
    fun present(
        incident: SecurityIncident,
        trigger: IncidentPresentationTrigger,
        snapshot: ProtectionSnapshot,
        location: LocationPresentation?,
    ): IncidentMessagePresentation
}
```

Evidence conversion must switch on typed capability/evidence values. It may read finite normalized values, finite deltas, typed duration/angle/near-far/confidence metadata, and unit labels. It must not read `IncidentEvidence.diagnostic` or `DeliveryAttempt.detail`. Missing typed metadata produces a general human sentence for that capability and omits numeric secondary detail.

Clamp confidence to `0..100`, round angles/lux/meters consistently, drop non-finite values, and cap evidence at five items ordered primary-first then supporting confidence.

Convert `LocationPresentation` immediately into `IncidentLocationPresentation` and discard `mapsUrl`, coordinates, and raw location source.

Set `isDemo = incident.origin == IncidentOrigin.DEMO` and prefix the localized headline with a textual `DEMO` marker only for that typed origin. Never infer Demo from an incident ID, diagnostic, build type, or test flag.

- [ ] **Step 5: Implement the protection factory and reuse status truth logic**

Constructor and method:

```kotlin
class ProtectionMessagePresentationFactory(
    private val incidentFactory: IncidentMessagePresentationFactory,
) {
    fun present(
        snapshot: ProtectionSnapshot,
        nowWallClockMs: Long,
        nowElapsedMs: Long,
        lastIncident: SecurityIncident? = null,
    ): ProtectionMessagePresentation
}
```

Move/reuse freshness, capability-count, battery, service, and Telegram truth logic from `ProtectionStatusProjection`. Known typed issues get explicit impact/remediation. Unknown legacy strings produce one generic safe issue and are never echoed. Identity is deterministic from snapshot revision, state, persistent notice ID, last incident ID/update time, and material delivery states; changing telemetry alone does not change the identity.

Keep `ProtectionStatusProjection.evaluate(...)` as a compatibility adapter during migration, delegating to the shared factory rather than maintaining separate rules.

- [ ] **Step 6: Run GREEN tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.IncidentMessagePresentationFactoryTest" --tests "com.example.motorcycleantitheftsensor.protection.ProtectionMessagePresentationFactoryTest" --tests "com.example.motorcycleantitheftsensor.telegram.ProtectionStatusProjectionTest"
```

Expected: PASS with safe evidence, exact neutral guidance, no raw fallbacks, correct freshness, and compatible status truth.

- [ ] **Step 7: Commit pure presentation factories**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessagePresentationFactory.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionMessagePresentationFactory.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentMessagePresentationFactoryTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionMessagePresentationFactoryTest.kt
git add -p app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusProjection.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusProjectionTest.kt
git diff --cached --check
git diff --cached
git commit -m "feat: project safe incident and protection messages"
```

---

### Task 4: Format Telegram, SMS, Status, and Android Notification from Presentations

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatter.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatter.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionStateTelegramNotifier.kt`
- Create: `app/src/main/java/com/example/motorcycleantitheftsensor/service/ForegroundNotificationPresenter.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/ForegroundNotificationPolicy.kt`
- Modify tests: `IncidentMessageFormatterTest.kt`, `ProtectionStatusFormatterTest.kt`, `ProtectionStateTelegramNotifierTest.kt`, `ForegroundNotificationPolicyTest.kt`
- Create test: `ForegroundNotificationPresenterTest.kt`

**Interfaces:**
- Consumes: only `IncidentMessagePresentation` or `ProtectionMessagePresentation`
- Produces: Telegram incident text, SMS plaintext before encryption, Telegram `/status` text, and `ForegroundNotificationCopy`

- [ ] **Step 1: Write RED tests for hierarchy and channel boundaries**

```kotlin
@Test
fun telegramOrdersHeadlineProtectionActionTimeEvidenceAndId() {
    val output = formatter.formatTelegram(presentation())
    assertOrdered(
        output,
        "รถอาจถูกเคลื่อนย้ายหรืองัดแงะ",
        "สถานะระบบ:",
        "แนะนำ: ตรวจสอบสถานการณ์และดำเนินการตามความเหมาะสม",
        "เวลา:",
        "หลักฐาน:",
        "เหตุการณ์: MG-20260820-0142",
    )
}

@Test
fun smsContainsCanonicalFieldsAndNeverLocation() {
    val output = formatter.formatSms(presentationWithLocation())
    listOf("ระดับความเสี่ยง:", "สถานะระบบ:", "เวลา:", "MG-20260820-0142").forEach {
        assertTrue(output.contains(it))
    }
    listOf("ตำแหน่ง", "พิกัด", "maps.google.com", "13.7563", "100.5018", "±24 เมตร").forEach {
        assertFalse(output.contains(it))
    }
}

@Test
fun notificationCopyIncludesTextualSeverityOutcomeAndAction() {
    val copy = presenter.present(criticalProtectionPresentation())
    assertTrue(copy.titleTh.contains("รถ"))
    assertTrue(copy.contentTh.contains("การป้องกัน"))
    assertTrue(copy.expandedTh.contains("แนะนำ:"))
    assertEquals(MessagePersistence.UNTIL_STATE_CHANGES, copy.persistence)
}
```

- [ ] **Step 2: Run formatter tests and verify RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.IncidentMessageFormatterTest" --tests "com.example.motorcycleantitheftsensor.telegram.ProtectionStatusFormatterTest" --tests "com.example.motorcycleantitheftsensor.protection.ProtectionStateTelegramNotifierTest" --tests "com.example.motorcycleantitheftsensor.service.ForegroundNotificationPresenterTest" --tests "com.example.motorcycleantitheftsensor.service.ForegroundNotificationPolicyTest"
```

Expected: FAIL because formatters accept raw models, status hierarchy is old, and notification copy is private/English.

- [ ] **Step 3: Restrict incident formatter signatures**

The public formatter surface becomes:

```kotlin
class IncidentMessageFormatter {
    fun formatTelegram(presentation: IncidentMessagePresentation): String
    fun formatSms(presentation: IncidentMessagePresentation): String
}
```

Telegram uses the approved hierarchy. SMS is compact and must never branch on or append `presentation.location`. Both use `presentation.occurredAtTh`, never reformat epoch time independently.

Add a test helper that recursively joins every string field and asserts absence of:

```kotlin
val forbidden = listOf(
    "accelerometer_magnitude_sensitivity_5",
    "ambient_lux",
    "SENSOR_INIT_FAILED",
    "Exception",
    "StackTrace",
    "secret=",
    "token=",
    "chat_id",
    "update status card only",
    "รวมใน /status",
)
```

- [ ] **Step 4: Put action/outcome first in Telegram status and state messages**

`ProtectionStatusFormatter.format(...)` accepts `ProtectionMessagePresentation`. Keep a temporary `format(snapshot, nowWallClockMs, nowElapsedMs)` adapter only until all call sites migrate, and make it delegate to the shared factory.

`ProtectionStateTelegramNotifier` accepts previous/current `ProtectionMessagePresentation` or typed snapshots; it never accepts `Set<String>` raw degradation reasons. Unknown issues use generic safe copy. Preserve existing flap suppression.

- [ ] **Step 5: Add pure notification copy and complete fingerprint**

```kotlin
data class ForegroundNotificationCopy(
    val titleTh: String,
    val contentTh: String,
    val expandedTh: String,
    val persistence: MessagePersistence,
    val presentationIdentity: String,
)

data class ForegroundNotificationFingerprint(
    val title: String,
    val content: String,
    val expanded: String,
    val presentationIdentity: String,
    val foregroundTypes: Int,
)
```

The presenter limits collapsed content to the protection outcome and primary action, while expanded content includes at most three evidence/issue lines plus incident ID. It includes text labels such as `ระดับความเสี่ยง: สูง`; emoji is optional decoration only.

- [ ] **Step 6: Run GREEN formatter tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.IncidentMessageFormatterTest" --tests "com.example.motorcycleantitheftsensor.telegram.ProtectionStatusFormatterTest" --tests "com.example.motorcycleantitheftsensor.protection.ProtectionStateTelegramNotifierTest" --tests "com.example.motorcycleantitheftsensor.service.ForegroundNotificationPresenterTest" --tests "com.example.motorcycleantitheftsensor.service.ForegroundNotificationPolicyTest"
```

Expected: PASS with canonical field ordering, no SMS location, safe status copy, and notification fingerprint changes for every visible material change.

- [ ] **Step 7: Commit channel formatters**

```powershell
git add app/src/main/java/com/example/motorcycleantitheftsensor/service/ForegroundNotificationPresenter.kt app/src/test/java/com/example/motorcycleantitheftsensor/service/ForegroundNotificationPresenterTest.kt
git add -p app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatter.kt app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatter.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionStateTelegramNotifier.kt app/src/main/java/com/example/motorcycleantitheftsensor/service/ForegroundNotificationPolicy.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentMessageFormatterTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatterTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionStateTelegramNotifierTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/service/ForegroundNotificationPolicyTest.kt
git diff --cached --check
git diff --cached
git commit -m "feat: format alerts from safe presentations"
```

---

### Task 5: Repair Per-Channel Delivery Truth and the Live SMS Boundary

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentDeliveryCoordinator.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telephony/SmsFallbackManager.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telephony/EncryptedSmsCodec.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Modify tests: `IncidentDeliveryCoordinatorTest.kt`, `SmsFallbackManagerTest.kt`, `ProtectionCoordinatorTest.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentDeliveryTruthPathIntegrationTest.kt`

**Interfaces:**
- Consumes: `IncidentDeliveryRequest`, safe presentation factory/formatter, Telegram transport, SMS transport, injected delivery clock
- Produces: persisted pending-before-send incident, typed per-channel attempts, truthful aggregate result, and actual encrypted SMS presentation with no location

- [ ] **Step 1: Write RED delivery truth tests**

```kotlin
@Test
fun telegramFailureThenSmsSuccessRetainsBothChannelOutcomes() = runTest {
    val delivered = coordinator.deliver(
        IncidentDeliveryRequest(criticalIncident(), IncidentPresentationTrigger.OPENED),
        DeliveryConfiguration(smsConfigured = true),
    )
    assertEquals(DeliveryState.FAILED, delivered.deliveryAttempts.single { it.channel == DeliveryChannel.TELEGRAM }.state)
    assertEquals(DeliveryState.SENT, delivered.deliveryAttempts.single { it.channel == DeliveryChannel.SMS }.state)
}

@Test
fun liveSmsTransportUsesFormattedPayloadWithoutLocation() = runTest {
    val result = harness.deliverCriticalIncidentWithValidLocation()
    val decrypted = EncryptedSmsCodec.decryptSmsPayload(requireNotNull(result.encryptedSms), TEST_KEY)
    assertTrue(decrypted!!.contains(result.incident.id))
    assertFalse(decrypted.contains("LOC:"))
    assertFalse(decrypted.contains("13.7563"))
    assertFalse(decrypted.contains("100.5018"))
    assertFalse(decrypted.contains("maps.google.com"))
}

@Test
fun deliveryAttemptUsesDeliveryClockNotIncidentUpdateTime() = runTest {
    val delivered = coordinatorWithClock(9_999L).deliver(request(updatedAtMs = 1_000L), configured())
    assertTrue(delivered.deliveryAttempts.all { it.attemptedAtMs == 9_999L })
}

@Test
fun demoIncidentIsNeverSmsEligible() = runTest {
    coordinator.deliver(
        IncidentDeliveryRequest(demoIncident(), IncidentPresentationTrigger.OPENED),
        DeliveryConfiguration(smsConfigured = true),
    )
    assertEquals(0, smsTransport.calls)
}
```

- [ ] **Step 2: Run delivery tests and verify RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.IncidentDeliveryCoordinatorTest" --tests "com.example.motorcycleantitheftsensor.telephony.SmsFallbackManagerTest" --tests "com.example.motorcycleantitheftsensor.protection.ProtectionCoordinatorTest" --tests "com.example.motorcycleantitheftsensor.protection.IncidentDeliveryTruthPathIntegrationTest"
```

Expected: FAIL because delivery has no explicit request trigger, SMS drops the formatted argument, and attempts use incident update time.

- [ ] **Step 3: Introduce one delivery request and one presentation per attempt cycle**

```kotlin
data class IncidentDeliveryRequest(
    val incident: SecurityIncident,
    val trigger: IncidentPresentationTrigger,
)

class IncidentDeliveryCoordinator(
    private val repository: IncidentRepository,
    private val presentationFactory: IncidentMessagePresentationFactory,
    private val formatter: IncidentMessageFormatter,
    private val telegram: IncidentTransport,
    private val sms: IncidentTransport,
    private val nowMs: () -> Long,
    private val locationPresentationProvider: suspend (SecurityIncident) -> LocationPresentation?,
)
```

Persist `PENDING` before external I/O. Build one canonical presentation. Format Telegram and SMS from it. Record Telegram attempt first; evaluate SMS eligibility only after confirmed Telegram failure. Demo incidents are never SMS-eligible. Persist final attempts before returning.

- [ ] **Step 4: Change SMS manager to encrypt supplied copy**

```kotlin
suspend fun sendEncryptedSmsAlert(
    destinationNumber: String,
    plainTextPayload: String,
): Boolean {
    val secretPass = smsKeyProvider() ?: return false
    if (destinationNumber.isBlank() || plainTextPayload.isBlank()) return false
    val encryptedBody = EncryptedSmsCodec.encryptSmsPayload(plainTextPayload, secretPass)
    return sendAndAwaitAllParts(destinationNumber, encryptedBody)
}
```

Retain timeout, cancellation, multipart sent callbacks, cooldown, and exception-to-failure behavior. Remove `alertType`, `gpsLocation`, and `LOC:` construction.

Update only the stale KDoc in `EncryptedSmsCodec.kt` from “GPS coordinates + timestamp + alert type” to “channel-safe human-readable alert payload.” Do not change AES-GCM parameters, prefix/version, key derivation, encoding, or decryption behavior.

In `ProtectionRuntimeGraph`, the SMS transport must use its argument:

```kotlin
sms = IncidentTransport { message ->
    val destination = preferences.getSmsDestination()
    if (destination.isNullOrBlank() || preferences.getSmsAesKey().isNullOrBlank()) {
        false
    } else {
        sms.sendEncryptedSmsAlert(destination, message)
    }
}
```

- [ ] **Step 5: Preserve per-channel delivery in authoritative summaries**

Extend `IncidentSummary` with bounded typed attempts or a typed channel map copied from `SecurityIncident.deliveryAttempts`. UI/status must derive Telegram and SMS labels from the matching channel. Aggregate `DeliveryState.SENT` remains usable only as “ส่งผ่านอย่างน้อยหนึ่งช่องทางสำเร็จ” and is never labelled as a specific channel.

- [ ] **Step 6: Run GREEN delivery tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.IncidentDeliveryCoordinatorTest" --tests "com.example.motorcycleantitheftsensor.telephony.SmsFallbackManagerTest" --tests "com.example.motorcycleantitheftsensor.protection.ProtectionCoordinatorTest" --tests "com.example.motorcycleantitheftsensor.protection.IncidentDeliveryTruthPathIntegrationTest"
```

Expected: PASS; Telegram failure plus SMS device-sent is represented truthfully, SMS carries the formatted safe message, and no location reaches the encryption boundary.

- [ ] **Step 7: Commit delivery truth repair**

```powershell
git add app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentDeliveryTruthPathIntegrationTest.kt
git add -p app/src/main/java/com/example/motorcycleantitheftsensor/protection/IncidentDeliveryCoordinator.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt app/src/main/java/com/example/motorcycleantitheftsensor/telephony/SmsFallbackManager.kt app/src/main/java/com/example/motorcycleantitheftsensor/telephony/EncryptedSmsCodec.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentDeliveryCoordinatorTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/telephony/SmsFallbackManagerTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt
git diff --cached --check
git diff --cached
git commit -m "fix: preserve alert delivery truth by channel"
```

---

### Task 6: Render Shared Presentations in Notification, Protection, Events, Settings, and Snackbar

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/events/EventsScreen.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
- Modify tests: `ProtectionViewModelTest.kt`, `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**Interfaces:**
- Consumes: shared `ProtectionMessagePresentation`, incident/event presentations, typed guidance actions, stable presentation identity
- Produces: one persistent card, one-shot Snackbar, expanded foreground notification, complete Thai Events chronology, failed-apply acknowledgement

- [ ] **Step 1: Write RED ViewModel tests for safe UI projection**

```kotlin
@Test
fun repositoryFailureNeverPublishesExceptionText() = runTest {
    val fixture = fixture(repositoryFailure = IllegalStateException("secret=/private/path"))
    fixture.collectUntilEventsError()
    assertEquals("ไม่สามารถโหลดประวัติเหตุการณ์ได้", fixture.viewModel.uiState.value.eventsError)
    assertFalse(fixture.viewModel.uiState.value.eventsError!!.contains("secret="))
}

@Test
fun alertDegradedOfflineAndFailedApplyExposePersistentPresentation() = runTest {
    listOf(alertSnapshot(), degradedSnapshot(), offlineSnapshot(), setupSnapshot()).forEach { snapshot ->
        fixture.coordinator.emit(snapshot)
        assertNotEquals(MessagePersistence.TRANSIENT, fixture.viewModel.uiState.value.protectionPresentation.persistence)
    }
    fixture.publishSnapshotWithSensorOperation(failedApplyOperation())
    assertEquals(MessagePersistence.UNTIL_ACKNOWLEDGED, fixture.viewModel.uiState.value.protectionPresentation.persistence)
}

@Test
fun eventsUseEvidenceChronologyIncidentIdAndChannelDelivery() = runTest {
    val row = fixtureWithIncidentHistory().viewModel.uiState.value.events.single()
    assertEquals("MG-20260820-0142", row.presentation.incidentId)
    assertTrue(row.presentation.evidence.isNotEmpty())
    assertTrue(row.transitions.any { it.kind == IncidentTransitionKind.SEVERITY_ESCALATED })
    assertEquals(DeliveryState.FAILED, row.telegramDelivery?.state)
    assertEquals(DeliveryState.SENT, row.smsDelivery?.state)
}
```

- [ ] **Step 2: Write RED Compose accessibility and persistence tests**

Add these named tests to `ProtectionAppScreenTest`:

- `criticalSnackbarDoesNotReplacePersistentIncidentCard`
- `unchangedPresentationIdentityIsAnnouncedOnlyOnce`
- `persistentCardHasTextualSeverityOutcomeActionAndIncidentId`
- `eventsShowThaiEvidenceChronologyAndPerChannelDelivery`
- `eventsNeverShowRawEnumDiagnosticOrExceptionText`
- `largeFontKeepsHeadlineActionAndIncidentIdVisible`
- `failedApplyCardCanBeAcknowledgedWithoutDiscardingSettings`

Core assertions:

```kotlin
compose.onNodeWithText("ระดับความเสี่ยง: สูง").assertIsDisplayed()
compose.onNodeWithText("ตรวจสอบสถานการณ์และดำเนินการตามความเหมาะสม").assertIsDisplayed()
compose.onNodeWithText("MG-20260820-0142").assertIsDisplayed()
compose.onAllNodes(hasText("SENSOR_INIT_FAILED", substring = true)).assertCountEquals(0)
compose.onAllNodes(hasText("accelerometer_magnitude", substring = true)).assertCountEquals(0)
```

Define the merged TalkBack summary from safe presentation fields only:

```kotlin
fun ProtectionMessagePresentation.accessibilitySummaryTh(): String = listOf(
    headlineTh,
    protectionOutcomeTh,
    "แนะนำ: ${action.labelTh}",
    lastIncident?.let { "เหตุการณ์: ${it.incidentId}" },
).filterNotNull().joinToString(". ")
```

- [ ] **Step 3: Run UI tests and verify RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.ui.ProtectionViewModelTest"
.\gradlew.bat --no-daemon --max-workers=1 :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.motorcycleantitheftsensor.ui.ProtectionAppScreenTest
```

Expected: FAIL because UI models/screens still carry raw enums/details and persistent coverage is incomplete.

- [ ] **Step 4: Project presentations once in ViewModel**

Replace `ProtectionEventRow` raw fields with a presentation-owned row:

```kotlin
data class ProtectionEventRow(
    val id: String,
    val presentation: IncidentMessagePresentation,
    val transitions: List<IncidentTransition>,
    val telegramDelivery: ChannelDeliveryPresentation?,
    val smsDelivery: ChannelDeliveryPresentation?,
)
```

Add `protectionPresentation: ProtectionMessagePresentation` to `ProtectionUiState`. `ProtectionViewModel` invokes pure factories from the authoritative snapshot and repository records. It maps repository/settings exceptions to fixed safe Thai copy; it never uses `exception.message` in UI state.

Transient Snackbar remains `ProtectionUiMessage(id, content)` but its content is already sanitized. Stable IDs prevent recomposition/rotation replay.

- [ ] **Step 5: Render persistent cards and typed actions**

`ProtectionScreen` renders one persistent card when presentation persistence is not transient. Required order is headline, textual severity, protection outcome, action, incident time, bounded evidence, delivery truth, incident ID. It provides a `ดูรายละเอียดเหตุการณ์` action to Events when an incident exists.

`SettingsScreen` renders failed configuration apply from typed capability/result fields and offers `รับทราบ` or `ลองใช้การตั้งค่าอีกครั้ง` according to policy. It never prints apply diagnostics.

`EventsScreen` renders Thai transition rows, evidence, state, and per-channel delivery. It removes generic `Enum<*>.displayName()` and never reads `evidence.diagnostic`, `DeliveryAttempt.detail`, or `closeReason`.

- [ ] **Step 6: Add accessible semantics and bounded live regions**

Persistent critical/degraded card:

```kotlin
Modifier.semantics(mergeDescendants = true) {
    heading()
    liveRegion = LiveRegionMode.Assertive
    contentDescription = presentation.accessibilitySummaryTh()
}
```

Only presentation identity/state changes update the live region. Sample-age or telemetry-only updates do not change identity. Snackbar stays `LiveRegionMode.Polite` and displays immediate acknowledgement only.

Use text labels in addition to icons/colors. At font scale 2.0, cards wrap vertically and do not hide the action or incident ID.

- [ ] **Step 7: Render foreground notification copy**

`SensorService.createNotification(...)` consumes `ForegroundNotificationCopy`:

```kotlin
NotificationCompat.Builder(this, CHANNEL_ID)
    .setContentTitle(copy.titleTh)
    .setContentText(copy.contentTh)
    .setStyle(NotificationCompat.BigTextStyle().bigText(copy.expandedTh))
    .setSmallIcon(android.R.drawable.ic_lock_lock)
    .setContentIntent(pendingIntent)
    .setOngoing(true)
    .setSilent(true)
    .setOnlyAlertOnce(true)
    .setPriority(NotificationCompat.PRIORITY_LOW)
    .build()
```

Remove `notificationText(snapshot)`. Both initial foreground start and later renders use the same presenter and fingerprint.

- [ ] **Step 8: Run GREEN host and Compose tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.ui.ProtectionViewModelTest" --tests "com.example.motorcycleantitheftsensor.service.ForegroundNotificationPresenterTest" --tests "com.example.motorcycleantitheftsensor.service.ForegroundNotificationPolicyTest"
.\gradlew.bat --no-daemon --max-workers=1 :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.motorcycleantitheftsensor.ui.ProtectionAppScreenTest
```

Expected: PASS with persistent cards, one-shot Snackbar, Thai Events, expanded notification, safe errors, TalkBack text, and large-font layout.

- [ ] **Step 9: Commit UI and notification integration**

```powershell
git add -p app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionUiModels.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModel.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/protection/ProtectionScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/events/EventsScreen.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt
git diff --cached --check
git diff --cached
git commit -m "feat: show readable persistent protection alerts"
```

---

### Task 7: Recover Typed Persistent Notices and Prevent Duplicate Announcements

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStore.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt`
- Modify tests: `ProtectionCoordinatorTest.kt`, `ProtectionSnapshotStoreTest.kt`, `ProtectionViewModelTest.kt`, `SnapshotProjectionGateTest.kt`

**Interfaces:**
- Consumes: authoritative `SensorConfigurationApplyOperation` from Settings Task 3 and current snapshot recovery flow
- Produces: recovery of that same typed operation plus presentation-identity deduplication; no second failed-apply state model

- [ ] **Step 1: Write RED persistence/recovery tests**

```kotlin
@Test
fun failedApplyPersistsAsTypedCodesWithoutLocalizedStrings() {
    val snapshot = snapshotWithSensorOperation(
        failedApplyOperation(
            id = "apply-42",
            issue = SensorConfigurationIssue(
                capability = SensorCapability.ROTATION,
                source = SensorSource.GYROSCOPE,
                code = SensorConfigurationIssueCode.CALIBRATION_FAILED,
            ),
        ),
    )
    store.save(snapshot, 42_000L)
    val recovered = store.loadForRecovery().liveSnapshot.sensorConfiguration.operation
    assertEquals("apply-42", recovered?.id)
    assertEquals(
        SensorConfigurationIssueCode.CALIBRATION_FAILED,
        recovered?.issues?.single()?.code,
    )
    assertFalse(preferences.allStringValues().any { it.contains("ปรับเทียบ") })
}

@Test
fun acknowledgingNoticeDoesNotChangeProtectionStateOrConfiguration() {
    val before = coordinator.snapshot.value
    coordinator.acknowledgeSensorConfigurationResult("apply-42")
    val after = coordinator.snapshot.value
    assertEquals(before.state, after.state)
    assertEquals(before.sensorHealth, after.sensorHealth)
    assertEquals(0, runtime.configurationApplyCalls)
    assertTrue(after.sensorConfiguration.operation?.acknowledged == true)
}

@Test
fun telemetryOnlyRevisionDoesNotReplayCriticalAnnouncement() {
    val first = gate.shouldProject(alertSnapshot(revision = 1, sampleAgeMs = 100), 1_000L)
    val second = gate.shouldProject(alertSnapshot(revision = 2, sampleAgeMs = 200), 1_100L)
    assertTrue(first)
    assertFalse(second)
}
```

- [ ] **Step 2: Run recovery tests and verify RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.ProtectionCoordinatorTest" --tests "com.example.motorcycleantitheftsensor.protection.ProtectionSnapshotStoreTest" --tests "com.example.motorcycleantitheftsensor.protection.SnapshotProjectionGateTest" --tests "com.example.motorcycleantitheftsensor.ui.ProtectionViewModelTest"
```

Expected: FAIL because failed-apply notice and presentation-identity dedupe are absent.

- [ ] **Step 3: Reuse the authoritative typed apply operation**

Do not add `PersistentNoticeState`, `PersistentNoticeCode`, or a second acknowledgement API. Persist and present `ProtectionSnapshot.sensorConfiguration.operation` from Settings Task 3. `acknowledgeSensorConfigurationResult(operationId)` marks only the matching operation acknowledged; a successful replacement apply clears the previous failed operation according to the existing Settings retention policy. Unknown runtime input is already reduced to `SensorConfigurationIssueCode` before entering the snapshot; raw text is discarded.

- [ ] **Step 4: Persist only typed notice fields in snapshot recovery**

Add keys for operation ID, affected capabilities, phase, outcome, failed sources, typed issue tuples, and acknowledged boolean. Invalid/missing combinations recover as no operation. Do not persist title/body/remediation/presentation identity.

Active incident persistent presentation is reconstructed from `lastIncidentId` plus `IncidentRepository.findById(...)`; if recovery marks the incident interrupted, Events shows the typed `INTERRUPTED` transition and never calls it escalation.

- [ ] **Step 5: Deduplicate by material presentation identity**

Update projection gates so `ALERT_ACTIVE`, severity escalation, state changes, per-channel delivery changes, notice changes, and explicit reminders bypass rate limiting. Sensor sample age/value-only changes may refresh visible telemetry at the existing interval but do not replay Snackbar/live-region/external sends.

- [ ] **Step 6: Run GREEN recovery tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.ProtectionCoordinatorTest" --tests "com.example.motorcycleantitheftsensor.protection.ProtectionSnapshotStoreTest" --tests "com.example.motorcycleantitheftsensor.protection.SnapshotProjectionGateTest" --tests "com.example.motorcycleantitheftsensor.ui.ProtectionViewModelTest"
```

Expected: PASS; typed failed-apply notice survives process recovery, acknowledgement is presentation-only, and unchanged telemetry does not replay alerts.

- [ ] **Step 7: Commit recovery and deduplication**

```powershell
git add -p app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStore.kt app/src/main/java/com/example/motorcycleantitheftsensor/service/SensorService.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionSnapshotStoreTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/protection/SnapshotProjectionGateTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt
git diff --cached --check
git diff --cached
git commit -m "feat: recover persistent protection guidance"
```

---

### Task 8: Prove Cross-Channel Consistency and Remove the Parallel Legacy Alert Path

**Files:**
- Modify/Create: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentDeliveryTruthPathIntegrationTest.kt`
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusTruthPathIntegrationTest.kt`
- Modify: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`
- Remove after proof: `app/src/main/java/com/example/motorcycleantitheftsensor/service/AlertDispatcher.kt`
- Remove after equivalent coverage exists: `app/src/test/java/com/example/motorcycleantitheftsensor/service/AlertDispatcherTest.kt`

**Interfaces:**
- Consumes: one controlled incident, one authoritative snapshot, all presentation/formatter/UI/delivery consumers
- Produces: contract evidence that all channels preserve canonical meaning and that no floating legacy formatter remains

- [ ] **Step 1: Add one cross-channel fixture and RED consistency assertions**

Create a controlled critical tamper incident with ID `MG-20260820-0142`, fixed device-local time, movement/rotation/light evidence, valid Telegram-only location presentation, Telegram failure, and SMS device-sent success.

Assertions:

```kotlin
@Test
fun everyChannelPreservesSeverityStateTimeAndIncidentId() {
    val outputs = harness.renderAllChannels()
    outputs.forEach { output ->
        assertTrue(output.text.contains("สูง"))
        assertTrue(output.text.contains("การป้องกันยังทำงาน"))
        assertTrue(output.text.contains("MG-20260820-0142"))
        assertTrue(output.text.contains(harness.expectedLocalTime))
    }
    assertFalse(outputs.sms.text.contains("ตำแหน่ง"))
    assertTrue(outputs.telegram.text.contains("ตำแหน่งล่าสุด"))
    assertEquals("ส่ง Telegram ไม่สำเร็จ", outputs.events.telegramOutcome)
    assertEquals("โทรศัพท์ส่ง SMS แล้ว", outputs.events.smsOutcome)
}

@Test
fun capturedOutputsContainNoForbiddenTechnicalText() {
    val combined = harness.renderAllChannels().allText()
    forbiddenTechnicalStrings.forEach { forbidden ->
        assertFalse("forbidden user text: $forbidden", combined.contains(forbidden, ignoreCase = true))
    }
    assertFalse(Regex("\\{[A-Za-z][A-Za-z0-9]*}").containsMatchIn(combined))
}
```

Define the forbidden list explicitly in the integration test:

```kotlin
private val forbiddenTechnicalStrings = listOf(
    "VIBRATION",
    "TAMPER",
    "POWER_THERMAL",
    "ALERT_ACTIVE",
    "ARMED_DEGRADED",
    "accelerometer_magnitude_sensitivity_5",
    "ambient_lux",
    "SENSOR_INIT_FAILED",
    "update status card only",
    "รวมใน /status",
    "Exception",
    "StackTrace",
    "secret=",
    "token=",
    "chat_id",
)
```

The SMS-specific assertions separately reject precise coordinates, `maps.google.com`, location labels, accuracy, and fix-age copy.

- [ ] **Step 2: Run cross-channel tests and verify RED before final integration fixes**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.IncidentDeliveryTruthPathIntegrationTest" --tests "com.example.motorcycleantitheftsensor.telegram.ProtectionStatusTruthPathIntegrationTest"
```

Expected: any remaining mismatch fails with the named channel and canonical field.

- [ ] **Step 3: Resolve only demonstrated consistency gaps**

Fix the shared factory or the affected channel formatter. Do not patch output independently with raw-domain access. If a formatter needs a missing field, add a safe field to the shared presentation and update all formatters/tests together.

Remove the temporary `ProtectionStatusFormatter.format(snapshot, ...)` compatibility overload after `SensorService`, Telegram command handling, and tests all construct `ProtectionMessagePresentation` through the shared factory. The final formatter surface must accept presentations only.

- [ ] **Step 4: Remove the unused legacy dispatcher**

First prove no production reference:

```powershell
rg -n --glob '!**/build/**' "AlertDispatcher|AlertEvent|AlertTransport|AlertDispatchResult" app/src/main app/src/test app/src/androidTest
```

Expected before removal: only `service/AlertDispatcher.kt` and `AlertDispatcherTest.kt`. Delete both with `apply_patch` after `IncidentDeliveryTruthPathIntegrationTest` covers Telegram-first/SMS-fallback behavior. Re-run the search and require zero matches.

- [ ] **Step 5: Run GREEN cross-channel and Compose tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest --tests "com.example.motorcycleantitheftsensor.protection.IncidentDeliveryTruthPathIntegrationTest" --tests "com.example.motorcycleantitheftsensor.telegram.ProtectionStatusTruthPathIntegrationTest"
.\gradlew.bat --no-daemon --max-workers=1 :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.example.motorcycleantitheftsensor.ui.ProtectionAppScreenTest
```

Expected: PASS for canonical meaning, channel-specific privacy, delivery truth, accessibility semantics, persistent state, and no legacy dispatcher.

- [ ] **Step 6: Commit cross-channel contract and legacy removal**

```powershell
git add -p app/src/test/java/com/example/motorcycleantitheftsensor/protection/IncidentDeliveryTruthPathIntegrationTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusTruthPathIntegrationTest.kt app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt
git add -u app/src/main/java/com/example/motorcycleantitheftsensor/service/AlertDispatcher.kt app/src/test/java/com/example/motorcycleantitheftsensor/service/AlertDispatcherTest.kt
git diff --cached --check
git diff --cached
git commit -m "test: enforce cross-channel alert consistency"
```

---

### Task 9: Full Verification, Secret/Leak Scan, and Real-Device Acceptance

**Files:**
- Create during execution: `docs/superpowers/status/2026-08-20-human-readable-alerts-guidance-evidence.md`
- Read only: final diff, APK metadata, device outputs, Telegram/SMS controlled acceptance

**Interfaces:**
- Consumes: completed Tasks 1–8
- Produces: bounded host/build/device evidence; no unsupported success claim

- [ ] **Step 1: Run source leak and liveness scans**

```powershell
rg -n --glob '!**/build/**' "incident\.type\.name|ev\.kind\.name|Enum<\*>\.displayName|degradationReasons.*joinToString|exception\.message|error\.message|update status card only|รวมใน /status|LOC:" app/src/main app/src/test app/src/androidTest
rg -n --glob '!**/build/**' "AlertDispatcher|AlertEvent|AlertTransport|AlertDispatchResult" app/src/main app/src/test app/src/androidTest
rg -n --glob '!**/build/**' "formatTelegram\(.*SecurityIncident|formatSms\(.*SecurityIncident|notificationText\(" app/src/main app/src/test
```

Expected: no production user-facing raw fallback, no legacy dispatcher, and no channel formatter accepting raw incident/snapshot data. Test fixtures may contain forbidden strings only inside negative assertions.

- [ ] **Step 2: Run the complete host gate**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 :app:testDebugUnitTest :app:assembleDebug
```

Expected: BUILD SUCCESSFUL. Record exact test counts, APK path, SHA-256, Git HEAD, and dirty status. A successful build is not device acceptance.

- [ ] **Step 3: Install the exact APK and verify permissions without changing security boundaries**

```powershell
adb devices -l
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell dumpsys package com.example.motorcycleantitheftsensor | Select-String "versionName|versionCode|SEND_SMS|POST_NOTIFICATIONS|RECORD_AUDIO|ACCESS_FINE_LOCATION"
```

Expected: installed APK identity matches the built artifact. Keep `SEND_SMS: deny` unless the controlled SMS fallback test is explicitly authorized for this device/run.

- [ ] **Step 4: Perform controlled same-incident channel acceptance**

Using one controlled incident ID, capture and compare:

1. Protection persistent card.
2. Immediate Snackbar.
3. Collapsed and expanded foreground notification.
4. Events detail and chronology.
5. Telegram incident.
6. Telegram `/status` after the delivery attempt.
7. SMS only when explicitly authorized and configured with a test destination/key.

Require the same severity, protection outcome, device-local time, and incident ID. Require exact neutral incident guidance. Require Telegram-only bounded location and zero SMS location. Confirm the UI/Events show actual Telegram and SMS outcomes separately.

- [ ] **Step 5: Perform accessibility and persistence acceptance**

- Enable the device’s largest practical font/display size and verify headline, protection outcome, action, evidence link, and incident ID remain visible and operable.
- Use TalkBack to verify the card announces textual severity/outcome/action without relying on emoji or color.
- Rotate/recompose the app and verify the same Snackbar/live-region message is not repeated.
- Force-stop/restart the app/service during a controlled active incident and verify the recovered Events/card uses `INTERRUPTED`, not “escalated.”
- Trigger a controlled failed configuration apply, restart the process, verify the typed notice remains, acknowledge it, and verify acknowledgement does not change protection configuration/state.

- [ ] **Step 6: Record evidence and remaining limitations**

Write `docs/superpowers/status/2026-08-20-human-readable-alerts-guidance-evidence.md` with exact commands, timestamps, device serial/model/Android version, APK hash, test counts, screenshots/log artifact paths, Telegram/SMS outcome, permission state, and unresolved items. Redact destination, token, key, Chat ID, coordinates, and personal location labels.

- [ ] **Step 7: Verify final diff and commit evidence only if authorized**

```powershell
git status --short
git diff --check
git diff -- docs/superpowers/status/2026-08-20-human-readable-alerts-guidance-evidence.md
git add docs/superpowers/status/2026-08-20-human-readable-alerts-guidance-evidence.md
git diff --cached --check
git diff --cached
git commit -m "docs: record human-readable alert acceptance"
```

Expected: evidence commit contains only the status document. If device, Telegram, or SMS acceptance was not run, state `NOT RUN` and do not claim that channel accepted.

## Final Acceptance Criteria

- Every real incident uses exactly `ตรวจสอบสถานการณ์และดำเนินการตามความเหมาะสม`.
- Protection, notification, Events, Telegram, and eligible SMS preserve the same localized severity, protection outcome, device-local timestamp, and incident ID.
- Telegram and SMS outcomes remain separate. SMS device-sent success is never presented as Telegram success or recipient delivery.
- SMS plaintext before encryption and decrypted controlled acceptance contain no location information of any kind.
- Channel formatters accept immutable presentations only and cannot access raw sensor diagnostics, raw exceptions, raw degradation text, raw coordinates, or secrets.
- Movement, rotation, magnetic, light, and proximity evidence uses plain-language meaning; numeric secondary evidence has a unit and context.
- `INTERRUPTED` is never formatted as escalation. Open/escalated/closed external sends receive an explicit typed trigger.
- Non-escalating evidence updates persist under the same incident without duplicate external notifications.
- Active alert, degraded, offline, setup-blocked, and failed-apply states remain visible under their typed persistence policy; Snackbar is not the only feedback.
- Events show typed chronology, bounded evidence, protection outcome, and confirmed per-channel delivery attempts.
- Android notification has informative collapsed and expanded Thai copy and remains ongoing/silent under the existing foreground policy.
- Unknown legacy reasons become generic safe copy; they are never echoed.
- UI failure states never expose `Throwable.message`.
- Thai copy is UTF-8 safe, contains no unresolved placeholders, and remains usable with TalkBack and large font.
- Process recovery reconstructs presentations from typed snapshot/history/notice data. No localized/channel-ready string is persisted.
- No orphan `AlertDispatcher`, raw-enum display helper, or raw-model channel formatter remains.
- Host tests and debug assembly pass. Device/channel claims are limited to the exact acceptance evidence actually collected.

## Expected Commit Sequence

1. `feat: add safe alert presentation contract`
2. `feat: persist typed incident chronology`
3. `feat: project safe incident and protection messages`
4. `feat: format alerts from safe presentations`
5. `fix: preserve alert delivery truth by channel`
6. `feat: show readable persistent protection alerts`
7. `feat: recover persistent protection guidance`
8. `test: enforce cross-channel alert consistency`
9. `docs: record human-readable alert acceptance` — only after authorized evidence collection

Each commit must pass its focused tests. Tasks 1–8 must be complete before the full host/device gate. Do not squash away RED/GREEN review boundaries until the receiving reviewer accepts the complete series.
