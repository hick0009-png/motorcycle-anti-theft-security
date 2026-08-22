# Telegram Full Status Truth-Path Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ทำให้คำสั่ง Telegram `/status` รายงานสถานะเต็มจากข้อมูล runtime จริงอย่างต่อเนื่อง โดย GPS, ไมโครโฟน, เซนเซอร์, แบตเตอรี่, Service, Telegram, sensitivity และเหตุการณ์ล่าสุดต้องตรงกับ `ProtectionCoordinator.snapshot` และต้องไม่รายงาน `5/5` เมื่อข้อมูลหายหรือ listener ไม่ได้ลงทะเบียน

**Architecture:** คงแนวทาง Snapshot-first ตาม Design Spec: sensor/runtime producer ส่งข้อมูล health แบบ typed เข้า `ProtectionCoordinator` อย่างต่อเนื่อง จากนั้น `/status` อ่าน Snapshot revision เดียว สร้าง projection แบบ pure แล้ว format เป็นภาษาไทยหนึ่งข้อความ ห้าม `/status` สั่ง start/stop/read ฮาร์ดแวร์ ห้ามใช้ diagnostic string เช่น `"stopped"`, `"fix "` หรือ incident UUID เป็น source of truth

**Tech Stack:** Kotlin, Android framework sensors/location/battery APIs, Kotlin coroutines `StateFlow`, JUnit4, Robolectric, kotlinx-coroutines-test, Gradle/JBR, ADB และ Telegram real-device acceptance

## Global Constraints

- Repository คือ `D:\security\MotorcycleAntiTheftSensor`; branch ที่ตรวจคือ `feature/motorcycle-guard-protection`; reviewed HEAD คือ `574fa94` เมื่อวันที่ 2026-08-15
- ก่อนแก้ต้องอ่าน `docs/superpowers/specs/2026-08-15-telegram-full-status-design.md` ทั้งไฟล์ และถือ Spec นั้นเป็นข้อกำหนดหลัก หากโค้ดปัจจุบันต่างจากแผนให้หยุดและบันทึก drift ห้ามเดา API ใหม่
- Worktree ปัจจุบันมีงาน GPS/audio/UI และไฟล์ untracked ปะปนอยู่ ห้าม `git reset`, `git clean`, `git checkout --`, `git stash`, ลบไฟล์ หรือใช้ `git add .`
- รอบ implementation ห้ามแก้ไฟล์นอก Allowed File Responsibility Map หากพบว่าจำเป็นต้องแก้ไฟล์อื่นให้หยุดและขออนุมัติเป็นลายลักษณ์อักษร
- ห้ามแก้ Sensor Fusion, YAMNet labels/thresholds, audio correlation 15 วินาที, GPS movement policy, Live Location transport, Arm/Disarm delay, Telegram authentication, `/pair`, `/disarm`, SMS, emergency call, TLS pinning หรือ UI ที่ไม่อ่าน Snapshot ชุดนี้
- ไม่ต้องปรับประหยัดแบตเตอรี่ ผู้ใช้ยืนยันว่าเสียบสายชาร์จได้
- ห้ามเพิ่ม dependency, repository, Android permission หรือ command ใหม่ เช่น `/status full`
- `ProtectionCoordinator` เป็น source of truth เพียงตัวเดียวสำหรับ `/status`; UI, Telegram และ service ห้ามถือ health copy คนละชุด
- Diagnostic string เก็บไว้เพื่อ log/debug compatibility ได้ แต่ห้ามใช้ตัดสิน state, readiness, active count, failure guidance หรือ incident type
- Timestamp ของ sample/fix ใช้ monotonic elapsed time สำหรับ freshness; timestamp ของ Service, Telegram contact, arm duration และเวลาที่แสดงต่อผู้ใช้ใช้ wall clock
- ค่าจากอนาคต, timestamp ติดลบ, `NaN`, `Infinity`, accuracy ติดลบ และข้อมูลหายต้องไม่ถูกจัดเป็น healthy
- Missing health entry ต้องแสดงว่า “ยังไม่มีข้อมูล/ไม่พร้อม” และห้ามนับ active หรือ ready
- Waiting state จะนับว่าทำงานได้เฉพาะเมื่อ listener/provider ลงทะเบียนสำเร็จจริง (`isRegistered == true`)
- Power/Thermal ไม่ stale เพียงเพราะไม่มี broadcast ภายใน 5 วินาที แต่ต้องมี Android battery source และ registration ที่สำเร็จ
- `/status` ต้องตอบหนึ่งคำสั่งต่อหนึ่งข้อความ ไม่รอ callback ไม่เริ่ม sensor ใหม่ ไม่สร้าง incident และไม่ส่ง SMS
- ห้ามใส่ bot token, Chat ID, raw diagnostic, full coordinates, Maps URL หรือ secret ลง `/status`, test fixture, log หรือ evidence
- `AI_WORKFLOW.md` ไม่พบในขณะตรวจ ห้ามสร้างเนื้อหาสมมติ ให้ทำตาม `AGENTS.md`, Design Spec และแผนนี้
- เนื่องจาก worktree สกปรก ห้าม commit โดยอัตโนมัติ ถ้าเจ้าของอนุญาตภายหลัง ให้ stage เฉพาะ hunk ที่ตรวจแล้วและตรวจ `git diff --cached` ก่อน commit ทุกครั้ง

## Reviewed Starting Evidence

- Focused `/status` tests ผ่าน 37/37 และ related host tests ผ่าน 145/145 แต่ส่วนใหญ่สร้าง `ProtectionSnapshot` จำลอง จึงยังไม่พิสูจน์ producer → coordinator → Telegram truth path
- ภาพ Telegram เวลา 20:22 เป็น build เก่า: source formatter ใหม่ถูกแก้หลัง 20:54 และ APK ล่าสุดของรอบตรวจติดตั้งประมาณ 21:12 ภาพเดิมจึงใช้เป็น current acceptance ไม่ได้
- GPS fix ส่งเข้า incident/Telegram ได้ แต่ไม่มี live fix callback ที่อัปเดต Location health ใน authoritative Snapshot
- AudioTelemetry มีอยู่ แต่ไม่มี continuous bridge ที่อัปเดต microphone lifecycle/model/sample เข้า Snapshot
- `recordSensorSample()` สร้าง `SensorHealth` ใหม่และทำ typed detail สูญหาย
- `LocationHealthDetail.trackingMode` เป็น `String?`; runtime และ projection ยังตรวจ `"fix "`, `"stopped"`, `"permission_denied"`
- `protectionActivatedAtMs`, `sensitivityLevel`, `chargingState` และ `lastServiceHeartbeatAtMs` มีช่องใน model แต่ producer บางส่วนไม่เขียนค่าจริง
- Projection นับ missing/AVAILABLE บางกรณีเป็น ready/active จึงมีโอกาสแสดง `5/5` ผิด
- Last incident type ถูกเดาจาก UUID แทน `SecurityIncident.type`

## Allowed File Responsibility Map

Production changes are limited to:

- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt` — typed health/status model เท่านั้น
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionHealthPolicy.kt` — freshness calculation ที่ใช้ร่วมกัน
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt` — read-only health flow contract พร้อม default สำหรับ fake runtime
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt` — detector health flow และการแปลง observation/telemetry เป็น typed health
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt` — atomic snapshot updates และ metadata producer
- `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt` — graph-owned collectors/initial snapshot wiring
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt` — forward telemetry callback เท่านั้น
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProvider.kt` — typed tracking health state flow
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/PowerThermalMonitor.kt` — typed battery/power callback
- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusProjection.kt` — pure projection และ count semantics
- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatter.kt` — Thai formatting/fallback boundary
- `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandler.kt` — แก้เฉพาะเมื่อ test พิสูจน์ว่าตอบไม่ใช่หนึ่งข้อความหรือมี side effect; หากผ่านแล้วห้ามแตะ

Tests allowed:

- `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionModelsTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionHealthPolicyTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntimeTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProviderTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/PowerThermalMonitorTest.kt` (create)
- `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusProjectionTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatterTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandlerTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusTruthPathIntegrationTest.kt` (create)

Evidence allowed:

- `docs/superpowers/status/2026-08-15-telegram-full-status-truth-path-evidence.md` (create)

Explicitly forbidden without a written amendment:

- `IncidentEngine.kt`, `LivePursuitCoordinator.kt`, movement policy/store/transport, YAMNet/audio classifier files, Settings/Compose UI, Gradle dependency files, Manifest, network security, authentication, SMS and emergency-call files

## Exact Status Counting Contract

| Sensor group | Ready while Disarmed | Active while Armed/Alert | Must not count |
|---|---|---|---|
| Vibration | health entry exists and accelerometer hardware is available | listener registered and sample is fresh, or listener registered and explicitly waiting for first sample | missing entry, registration failed, stale, failed |
| Light | health entry exists and light hardware is supported | listener registered and sample is fresh, or registered waiting for first sample | unsupported hardware, missing, stale, failed |
| Microphone | hardware available and permission granted | listener registered and state is `STARTING`, `CALIBRATING`, `LISTENING` or `CLASSIFYING`; `LISTENING/CLASSIFYING` sample must be fresh | `OFF`, `DEGRADED`, `FAILED`, missing permission/hardware, stale sample |
| GPS | hardware/permission/provider readiness has no failure | provider registered and state is `WAITING_FOR_FIX`, or state is `TRACKING` with a fresh valid fix | stopped while Armed/Alert, stale fix, permission/provider/registration failure, missing entry |
| Power/Thermal | Android battery source is available | receiver registered and source available | missing source, registration failure, missing entry |

Total sensor groups is fixed at five. `readyCount` and `activeCount` must be computed independently; do not implement one by reusing the other.

---

### Task 0: Freeze and Record the Dirty Baseline

**Files:**
- Create: `docs/superpowers/status/2026-08-15-telegram-full-status-truth-path-evidence.md`
- Read only: current Git state, Design Spec and files in the Allowed map

**Interfaces:**
- Consumes: current dirty worktree at reviewed HEAD plus approved Design Spec
- Produces: exact pre-edit inventory and evidence shell; no production behavior change

- [ ] **Step 1: Verify repository identity and record drift**

```powershell
git rev-parse --show-toplevel
git branch --show-current
git rev-parse HEAD
git status --short
git diff --check
```

Expected reviewed values are root `D:/security`, branch `feature/motorcycle-guard-protection`, HEAD starting `574fa94`. If HEAD changed, compare the named status files and update only the evidence header; do not reset back to this hash.

- [ ] **Step 2: Inventory tracked and untracked status implementation**

```powershell
git status --short
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatter.kt
git diff --no-index -- NUL app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusProjection.kt
```

`git diff --no-index` may return exit code 1 when differences exist; that is expected. Do not delete crash logs or unrelated untracked files in this task.

- [ ] **Step 3: Create the evidence document with these exact sections**

```markdown
# Telegram Full Status Truth-Path Evidence

## Baseline
- Branch:
- HEAD:
- Worktree: DIRTY, preserved
- AI_WORKFLOW.md: MISSING
- Prior focused result: 37/37 passed; synthetic snapshot evidence only
- Prior related result: 145/145 passed; device truth path not established

## Task Gates
## Full Host Gate
## APK and Install Identity
## Real-Device Matrix
## Privacy Audit
## Final Disposition
```

Write actual branch and HEAD values; do not copy a stale APK timestamp into the new acceptance section.

- [ ] **Step 4: Confirm Task 0 changed documentation only**

```powershell
git status --short docs/superpowers/status/2026-08-15-telegram-full-status-truth-path-evidence.md
```

Expected: the evidence file is the only new file created by Task 0.

---

### Task 1: Replace String-Derived Status with Typed Health Contracts

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionModels.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionHealthPolicy.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionModelsTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionHealthPolicyTest.kt`

**Interfaces:**
- Consumes: existing `SensorHealthState`, `LocationTrackingState`, `AudioRuntimeState`, `ChargingState`, `IncidentType`
- Produces: typed fields used by Tasks 2–7; shared `FreshnessState` calculation

Use these exact contracts, retaining `SensorHealth.detail` and `failureReason` only for logs:

```kotlin
enum class LocationFailureCode {
    HARDWARE_UNAVAILABLE,
    PERMISSION_DENIED,
    NO_PROVIDERS_AVAILABLE,
    REGISTRATION_FAILED,
}

enum class FreshnessState { MISSING, FRESH, STALE, CLOCK_ANOMALY }

data class LocationHealthDetail(
    val trackingState: LocationTrackingState = LocationTrackingState.STOPPED,
    val isRegistered: Boolean = false,
    val hardwareAvailable: Boolean = true,
    val permissionGranted: Boolean = true,
    val lastFixWallClockMs: Long? = null,
    val lastFixElapsedMs: Long? = null,
    val accuracyMeters: Float? = null,
    val failureCode: LocationFailureCode? = null,
    val failureReason: String? = null,
)

data class MicrophoneHealthDetail(
    val audioState: AudioRuntimeState = AudioRuntimeState.OFF,
    val isRegistered: Boolean = false,
    val modelReady: Boolean = false,
    val lastAudioSampleElapsedMs: Long? = null,
    val hardwareAvailable: Boolean = true,
    val permissionGranted: Boolean = true,
    val failureReason: String? = null,
)

data class PowerThermalHealthDetail(
    val sourceAvailable: Boolean = true,
    val isRegistered: Boolean = false,
    val chargingState: ChargingState = ChargingState.UNKNOWN,
    val batteryLevelPercent: Int? = null,
    val temperatureCelsius: Float? = null,
    val lastUpdateWallClockMs: Long? = null,
)
```

Also add `hardwareAvailable: Boolean` to `VibrationHealthDetail`, add `lastSampleElapsedMs: Long?` to `LightHealthDetail`, and add `type: IncidentType? = null` to `IncidentSummary`. Nullable incident type preserves old snapshots; production in Task 2 must always populate it.

- [ ] **Step 1: Write RED model tests**

Add tests that compile only after the typed contract exists:

```kotlin
@Test fun locationTrackingStateIsTyped() {
    val detail = LocationHealthDetail(
        trackingState = LocationTrackingState.WAITING_FOR_FIX,
        isRegistered = true,
    )
    assertEquals(LocationTrackingState.WAITING_FOR_FIX, detail.trackingState)
}

@Test fun microphoneSampleTimeIsExplicitlyMonotonic() {
    val detail = MicrophoneHealthDetail(lastAudioSampleElapsedMs = 12_345L)
    assertEquals(12_345L, detail.lastAudioSampleElapsedMs)
}
```

Add this compile-time contract test; do not invent a new incident taxonomy:

```kotlin
@Test fun incidentSummaryCarriesTypedIncidentType() {
    val summary = IncidentSummary(
        id = "550e8400-e29b-41d4-a716-446655440000",
        severity = IncidentSeverity.WARNING,
        lifecycle = IncidentLifecycle.OPEN,
        updatedAtMs = 1_000L,
        deliveryState = DeliveryState.PENDING,
        type = IncidentType.TAMPER,
    )
    assertEquals(IncidentType.TAMPER, summary.type)
}
```

- [ ] **Step 2: Write RED freshness boundary tests**

Expose one shared function:

```kotlin
fun freshness(lastAtMs: Long?, nowMs: Long, maxAgeMs: Long): FreshnessState
```

Required assertions:

```kotlin
assertEquals(FreshnessState.MISSING, freshness(null, 10_000L, 5_000L))
assertEquals(FreshnessState.FRESH, freshness(5_000L, 10_000L, 5_000L))
assertEquals(FreshnessState.STALE, freshness(4_999L, 10_000L, 5_000L))
assertEquals(FreshnessState.CLOCK_ANOMALY, freshness(10_001L, 10_000L, 5_000L))
assertEquals(FreshnessState.CLOCK_ANOMALY, freshness(-1L, 10_000L, 5_000L))
```

- [ ] **Step 3: Run RED**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionModelsTest" --tests "*ProtectionHealthPolicyTest"
```

Expected: compilation/test failure because new typed fields and `freshness()` do not exist.

- [ ] **Step 4: Implement the minimal model and freshness function**

Implement:

```kotlin
fun freshness(lastAtMs: Long?, nowMs: Long, maxAgeMs: Long): FreshnessState {
    if (lastAtMs == null) return FreshnessState.MISSING
    if (lastAtMs < 0L || nowMs < 0L || lastAtMs > nowMs) return FreshnessState.CLOCK_ANOMALY
    return if (nowMs - lastAtMs <= maxAgeMs) FreshnessState.FRESH else FreshnessState.STALE
}
```

Make `ProtectionHealthPolicy.serviceIsFresh()` and `telegramReachable()` delegate to this function. Keep exact thresholds: sensor 5,000 ms, service 10,000 ms, Telegram 20,000 ms, GPS 30,000 ms.

Remove `trackingMode: String?` and `lastAudioSampleAtMs` only after every allowed caller is migrated. Do not keep duplicate old/new state fields.

- [ ] **Step 5: Run GREEN and scan for forbidden string decisions**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionModelsTest" --tests "*ProtectionHealthPolicyTest"
rg -n 'trackingMode|lastAudioSampleAtMs|startsWith\("fix |failureReason\s*==|contains\("tamper|contains\("audio|contains\("location' app/src/main/java/com/example/motorcycleantitheftsensor
```

Expected: model/policy tests pass. Remaining matches are listed in evidence and must be removed by Tasks 3–6; no formatter/projection decision may remain after Task 6.

---

### Task 2: Make ProtectionCoordinator Publish Complete Authoritative Metadata

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinator.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/ProtectionCoordinatorTest.kt`

**Interfaces:**
- Consumes: typed `SensorHealth`, arm/disarm transitions, runtime sensitivity application, `SecurityIncident.type`
- Produces: `recordSensorHealth()`, `recordSensorHealthSnapshot()` and complete Snapshot metadata

Add these exact coordinator methods:

```kotlin
fun recordSensorHealth(kind: SensorKind, health: SensorHealth)
fun recordSensorHealthSnapshot(healthByKind: Map<SensorKind, SensorHealth>)
```

Both methods must use the existing atomic `updateSnapshot` path. `recordSensorHealthSnapshot()` merges only supplied keys; it must not erase a Location entry when runtime emits the other four sensor groups.

- [ ] **Step 1: Write RED atomic health tests**

Required tests:

```kotlin
@Test fun typedHealthUpdatePreservesDetailsAndIncrementsOneRevision() {
    val c = coordinator(FakeRuntime(ReadinessReport(emptySet(), emptySet())), ArmingDelay { })
    val detail = MicrophoneHealthDetail(
        audioState = AudioRuntimeState.LISTENING,
        isRegistered = true,
        modelReady = true,
        lastAudioSampleElapsedMs = 900L,
    )
    val before = c.snapshot.value.revision
    c.recordSensorHealth(
        SensorKind.MICROPHONE,
        SensorHealth(SensorHealthState.HEALTHY, microphoneDetail = detail),
    )
    assertEquals(detail, c.snapshot.value.sensorHealth[SensorKind.MICROPHONE]?.microphoneDetail)
    assertEquals(before + 1L, c.snapshot.value.revision)
}

@Test fun partialHealthSnapshotDoesNotEraseExistingLocation() {
    val c = coordinator(FakeRuntime(ReadinessReport(emptySet(), emptySet())), ArmingDelay { })
    val gps = SensorHealth(
        SensorHealthState.AVAILABLE,
        locationDetail = LocationHealthDetail(
            trackingState = LocationTrackingState.WAITING_FOR_FIX,
            isRegistered = true,
        ),
    )
    c.recordSensorHealth(SensorKind.LOCATION, gps)
    c.recordSensorHealthSnapshot(
        mapOf(SensorKind.MICROPHONE to SensorHealth(SensorHealthState.AVAILABLE)),
    )
    assertEquals(gps, c.snapshot.value.sensorHealth[SensorKind.LOCATION])
    assertTrue(c.snapshot.value.sensorHealth.containsKey(SensorKind.MICROPHONE))
}

@Test fun powerHealthCopiesBatteryTemperatureAndChargingToTopLevelSnapshot() {
    val c = coordinator(FakeRuntime(ReadinessReport(emptySet(), emptySet())), ArmingDelay { })
    c.recordSensorHealth(
        SensorKind.POWER_THERMAL,
        SensorHealth(
            SensorHealthState.HEALTHY,
            powerThermalDetail = PowerThermalHealthDetail(
                sourceAvailable = true,
                isRegistered = true,
                chargingState = ChargingState.CHARGING,
                batteryLevelPercent = 85,
                temperatureCelsius = 32.1f,
                lastUpdateWallClockMs = 1_000L,
            ),
        ),
    )
    assertEquals(85, c.snapshot.value.batteryLevelPercent)
    assertEquals(32.1f, c.snapshot.value.batteryTemperatureCelsius)
    assertEquals(ChargingState.CHARGING, c.snapshot.value.chargingState)
}

@Test fun genericRecordSensorSampleDoesNotDiscardExistingTypedDetail() {
    val c = coordinator(FakeRuntime(ReadinessReport(emptySet(), emptySet())), ArmingDelay { })
    val detail = VibrationHealthDetail(hardwareAvailable = true, isRegistered = true)
    c.recordSensorHealth(
        SensorKind.VIBRATION,
        SensorHealth(SensorHealthState.AVAILABLE, vibrationDetail = detail),
    )
    c.recordSensorSample(SensorKind.VIBRATION, 1_000L, "sample", 9.8)
    assertEquals(detail, c.snapshot.value.sensorHealth[SensorKind.VIBRATION]?.vibrationDetail)
}
```

For power, `recordSensorHealth()` must copy valid detail values to top-level `batteryLevelPercent`, `batteryTemperatureCelsius` and `chargingState` in the same revision.

- [ ] **Step 2: Write RED metadata lifecycle tests**

First extend the existing test helper with an injected clock:

```kotlin
private fun coordinator(
    runtime: FakeRuntime,
    armingDelay: ArmingDelay,
    clock: ProtectionClock = ProtectionClock { 1_000L },
    // retain the existing optional test dependencies
): ProtectionCoordinator = ProtectionCoordinator(
    // retain the existing initial snapshot and dependencies
    runtime = runtime,
    armingDelay = armingDelay,
    clock = clock,
)
```

Then add these tests:

```kotlin
@Test fun successfulArmSetsActivationTimeOnce() = runTest {
    var now = 1_000L
    val runtime = FakeRuntime(ReadinessReport(emptySet(), emptySet()), healthyVibration())
    val c = coordinator(runtime, ArmingDelay { }, ProtectionClock { now })
    c.arm("arm", CommandOrigin.LOCAL)
    assertEquals(1_000L, c.snapshot.value.protectionActivatedAtMs)
}

@Test fun alertRoundTripDoesNotResetActivationTime() = runTest {
    var now = 1_000L
    val runtime = FakeRuntime(ReadinessReport(emptySet(), emptySet()), healthyVibration())
    val c = coordinator(runtime, ArmingDelay { }, ProtectionClock { now })
    c.arm("arm", CommandOrigin.LOCAL)
    val activatedAt = c.snapshot.value.protectionActivatedAtMs
    now = 2_000L
    val opened = incident("550e8400-e29b-41d4-a716-446655440000", now).copy(type = IncidentType.AUDIO)
    c.recordIncident(opened)
    now = 3_000L
    c.recordIncident(opened.copy(lifecycle = IncidentLifecycle.CLOSED, updatedAtMs = now, closedAtMs = now))
    assertEquals(activatedAt, c.snapshot.value.protectionActivatedAtMs)
}

@Test fun disarmClearsActivationTime() = runTest {
    val runtime = FakeRuntime(ReadinessReport(emptySet(), emptySet()), healthyVibration())
    val c = coordinator(runtime, ArmingDelay { })
    c.arm("arm", CommandOrigin.LOCAL)
    c.disarm("disarm", CommandOrigin.LOCAL)
    assertNull(c.snapshot.value.protectionActivatedAtMs)
}

@Test fun changeSensitivityUpdatesRuntimeAndSnapshot() {
    val runtime = FakeRuntime(ReadinessReport(emptySet(), emptySet()))
    val c = coordinator(runtime, ArmingDelay { })
    assertEquals(CommandOutcome.APPLIED, c.changeSensitivity("s8", 8).outcome)
    assertEquals(8, runtime.appliedSensitivity)
    assertEquals(8, c.snapshot.value.sensitivityLevel)
}

@Test fun rejectedSensitivityDoesNotMutateSnapshot() {
    val runtime = FakeRuntime(ReadinessReport(emptySet(), emptySet()))
    val c = coordinator(runtime, ArmingDelay { })
    val before = c.snapshot.value
    assertEquals(CommandOutcome.REJECTED, c.changeSensitivity("s0", 0).outcome)
    assertEquals(CommandOutcome.REJECTED, c.changeSensitivity("s11", 11).outcome)
    assertEquals(before.sensitivityLevel, c.snapshot.value.sensitivityLevel)
    assertNull(runtime.appliedSensitivity)
}

@Test fun heartbeatWritesRunningAndTimestampInOneRevision() {
    val c = coordinator(FakeRuntime(ReadinessReport(emptySet(), emptySet())), ArmingDelay { })
    val before = c.snapshot.value.revision
    c.recordServiceHeartbeat(12_345L)
    assertTrue(c.snapshot.value.serviceRunning)
    assertEquals(12_345L, c.snapshot.value.lastServiceHeartbeatAtMs)
    assertEquals(before + 1L, c.snapshot.value.revision)
}

@Test fun incidentSummaryUsesSecurityIncidentTypeNotUuidText() {
    val c = coordinator(FakeRuntime(ReadinessReport(emptySet(), emptySet())), ArmingDelay { })
    c.recordIncident(
        incident("550e8400-e29b-41d4-a716-446655440000", 2_000L).copy(type = IncidentType.AUDIO),
    )
    assertEquals(IncidentType.AUDIO, c.snapshot.value.lastIncident?.type)
}
```

- [ ] **Step 3: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionCoordinatorTest"
```

Expected: new assertions fail because snapshot fields are currently defaulted or details are overwritten.

- [ ] **Step 4: Implement atomic metadata updates**

Use this merge shape:

```kotlin
fun recordSensorHealth(kind: SensorKind, health: SensorHealth) {
    updateSnapshot { current ->
        val power = health.powerThermalDetail.takeIf { kind == SensorKind.POWER_THERMAL }
        current.copy(
            sensorHealth = current.sensorHealth + (kind to health),
            batteryLevelPercent = power?.batteryLevelPercent ?: current.batteryLevelPercent,
            batteryTemperatureCelsius = power?.temperatureCelsius ?: current.batteryTemperatureCelsius,
            chargingState = power?.chargingState ?: current.chargingState,
        )
    }
}
```

Implement `recordSensorHealthSnapshot()` as one `updateSnapshot` call, not a loop of five updates.

Update `recordSensorSample()` by copying the existing `SensorHealth` and changing generic fields only. Do not construct a replacement that drops `locationDetail`, `microphoneDetail`, `powerThermalDetail`, `vibrationDetail` or `lightDetail`.

Update metadata producers:

```kotlin
runtime.applySensitivity(level)
updateSnapshot { it.copy(sensitivityLevel = level) }
```

```kotlin
updateSnapshot { it.copy(serviceRunning = true, lastServiceHeartbeatAtMs = atMs) }
```

Set `protectionActivatedAtMs` when the current arm finishes successfully. Preserve it through `ALERT_ACTIVE`, degraded/healthy changes and return from Alert; clear it on Disarm, failed Arm and Setup Required. Populate `IncidentSummary.type = incident.type` in `recordIncident()`.

- [ ] **Step 5: Run GREEN and regression**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionCoordinatorTest" --tests "*ProtectionModelsTest"
```

Expected: all named tests pass; each tested public update increments revision once.

---

### Task 3: Publish Continuous Detector and Microphone Health without Losing Typed Detail

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntime.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntimeTest.kt`

**Interfaces:**
- Consumes: sensor observations and `AudioPeakDetector.telemetry`
- Produces: `StateFlow<Map<SensorKind, SensorHealth>>`; Graph merges four non-location sensor groups into coordinator

Add a default-compatible runtime contract:

```kotlin
interface ProtectionRuntime {
    val audioTelemetry: StateFlow<AudioTelemetry>
    val sensorHealth: StateFlow<Map<SensorKind, SensorHealth>>
        get() = MutableStateFlow(emptyMap())
    // retain existing methods
}
```

`AndroidDetectorSet` exposes the same read-only `sensorHealth` flow. `PlatformAndroidDetectorSet` owns one `MutableStateFlow<Map<SensorKind, SensorHealth>>`; every producer update goes through one helper:

```kotlin
private fun publishHealth(kind: SensorKind, value: SensorHealth) {
    synchronized(this) {
        health[kind] = value
        mutableSensorHealth.value = health.toMap()
    }
}
```

- [ ] **Step 1: Write RED detector-flow tests**

Update `RecordingDetectorSet` so tests can publish exact typed maps:

```kotlin
private val mutableHealth = MutableStateFlow(initialHealth)
override val sensorHealth: StateFlow<Map<SensorKind, SensorHealth>> = mutableHealth

fun emitHealth(kind: SensorKind, value: SensorHealth) {
    health[kind] = value
    mutableHealth.value = health.toMap()
}
```

Add this exact flow-forwarding test:

```kotlin
@Test fun runtimeForwardsTypedDetectorHealthWithoutReconstruction() {
    lateinit var detectors: RecordingDetectorSet
    val runtime = runtime(vibrationProcessor(), detectorCapture = { detectors = it })
    val detail = MicrophoneHealthDetail(
        audioState = AudioRuntimeState.CALIBRATING,
        isRegistered = true,
        modelReady = false,
        lastAudioSampleElapsedMs = 900L,
        hardwareAvailable = true,
        permissionGranted = true,
    )
    detectors.emitHealth(
        SensorKind.MICROPHONE,
        SensorHealth(SensorHealthState.AVAILABLE, microphoneDetail = detail),
    )
    assertEquals(detail, runtime.sensorHealth.value[SensorKind.MICROPHONE]?.microphoneDetail)
}
```

Add platform-detector tests using the existing Robolectric/ApplicationProvider setup with these exact assertions:

| Test | Input | Assertions |
|---|---|---|
| `vibrationObservationPublishesRegisteredTypedHealth` | start succeeds, emit vibration at elapsed 900/wall 5,000 | `hardwareAvailable=true`, `isRegistered=true`, both sample clocks match |
| `lightObservationPublishesLuxAndRegistration` | start succeeds, emit lux 123.0 | `lastLux=123.0`, `isRegistered=true`, elapsed clock retained |
| `microphoneTelemetryPublishesEveryLifecycleTransition` | emit STARTING, CALIBRATING, LISTENING | flow emits the same three states in order |
| `microphoneTelemetryUsesElapsedSampleTime` | telemetry sample 900 with wall clock 5,000 | `lastAudioSampleElapsedMs=900`; `SensorHealth.lastSampleAtMs` is either wall 5,000 or null, never 900 |
| `microphonePermissionFailureKeepsTypedPermissionFalse` | RECORD_AUDIO denied and start attempted | `permissionGranted=false`, `isRegistered=false`, state UNAVAILABLE |
| `stoppingDetectorsPublishesOffAndUnregistered` | successful start then stop | state OFF, `isRegistered=false`, `hardwareAvailable=true`, `permissionGranted=true` |
| `runtimeDoesNotPublishLocationFromDiagnosticObservation` | supplemental Location observation contains diagnostic `fix age_ms=0` | runtime four-sensor flow has no Location key |

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AndroidProtectionRuntimeTest"
```

Expected: failures because health is currently polled/reconstructed and microphone telemetry does not continuously publish typed health.

- [ ] **Step 3: Forward AudioPeakDetector telemetry**

Extend constructor with a default callback so existing callers remain source-compatible:

```kotlin
class AudioPeakDetector(
    // existing parameters
    onTelemetryChanged: (AudioTelemetry) -> Unit = {},
) {
    // pipeline callback:
    onTelemetry = { telemetry ->
        _telemetry.value = telemetry
        onTelemetryChanged(telemetry)
    }
}
```

The platform mapper must set:

```kotlin
MicrophoneHealthDetail(
    audioState = telemetry.state,
    isRegistered = detectorsRunning && microphoneStartSucceeded,
    modelReady = telemetry.modelReady,
    lastAudioSampleElapsedMs = telemetry.lastSampleAtMs,
    hardwareAvailable = microphoneHardwareAvailable,
    permissionGranted = microphonePermissionGranted,
    failureReason = telemetry.detailCode,
)
```

State is `FAILED` only for `AudioRuntimeState.FAILED`, `STALE` is derived later from elapsed sample age, `UNAVAILABLE` is hardware/permission/start registration failure, and normal lifecycle states publish `AVAILABLE` or `HEALTHY` without parsing `detailCode`.

- [ ] **Step 4: Remove the lossy sample-recorder truth path**

Remove `sensorSampleRecorder` from `AndroidProtectionRuntime` constructor and remove its call from `handleObservation()`. `PlatformAndroidDetectorSet.record()` must publish typed health before forwarding the observation for incident processing.

Delete the startup block that calls:

```kotlin
record(location.currentObservation())
```

Change `currentLocationObservation()` to return supplemental incident evidence only; it must not call `updateHealth` and must not insert Location into the platform four-sensor health map.

Replace `startOptional()` generic `SensorHealth(UNAVAILABLE, "listener unavailable")` overwrite with kind-specific typed updates. A failed light listener must retain `hardwareSupported`; a failed microphone listener must retain permission/hardware and `isRegistered = false`; power is completed in Task 5.

- [ ] **Step 5: Wire runtime health after coordinator construction**

Construct the initial Snapshot with actual initial health and saved sensitivity:

```kotlin
val configuredSensitivity = preferences.getSensitivity()
val initialHealth = runtime.currentSensorHealth()
coordinator = ProtectionCoordinator(
    initialSnapshot = ProtectionSnapshot.offline(wallClock.nowMs()).copy(
        sensorHealth = initialHealth,
        sensitivityLevel = configuredSensitivity,
    ),
    // existing dependencies unchanged
)
runtime.applySensitivity(configuredSensitivity)
```

Then collect without blocking Telegram or the main thread:

```kotlin
scope.launch {
    runtime.sensorHealth.collect(coordinator::recordSensorHealthSnapshot)
}
```

Do not capture `coordinator` in a callback that can fire during runtime construction; this avoids repeating the prior eager-`lateinit` startup crash.

- [ ] **Step 6: Run GREEN and lifecycle regression**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AndroidProtectionRuntimeTest" --tests "*ProtectionCoordinatorTest"
```

Expected: health flow tests pass; no test requires a microphone threat classification before microphone health updates.

---

### Task 4: Make LocationObservationProvider the Only GPS Health Producer

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProvider.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionRuntimeGraph.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/LocationObservationProviderTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusTruthPathIntegrationTest.kt`

**Interfaces:**
- Consumes: `LocationRegistrationResult`, `LocationStartFailure`, accepted `TrackedLocationFix`, start/pursuit/stop lifecycle
- Produces: `StateFlow<LocationTrackingHealth>` and one graph-owned Location → Coordinator bridge

Define producer state in `LocationObservationProvider.kt`:

```kotlin
data class LocationTrackingHealth(
    val trackingState: LocationTrackingState,
    val isRegistered: Boolean,
    val hardwareAvailable: Boolean,
    val permissionGranted: Boolean,
    val lastFixWallClockMs: Long?,
    val lastFixElapsedMs: Long?,
    val accuracyMeters: Float?,
    val failureCode: LocationFailureCode?,
)
```

Expose:

```kotlin
val trackingHealth: StateFlow<LocationTrackingHealth>
```

Initial state is typed `STOPPED`; `lastDiagnostic = "stopped"` may remain for logs but cannot control `trackingHealth`.

- [ ] **Step 1: Write RED provider state-machine tests**

Required exact transitions:

```text
initial                         -> STOPPED, registered=false
registration Started           -> WAITING_FOR_FIX, registered=true
accepted valid fix             -> TRACKING, registered=true, fix times/accuracy copied
enter pursuit successfully     -> preserve fix, registered=true
stopTracking                   -> STOPPED, registered=false, fix cleared
PERMISSION_DENIED              -> UNAVAILABLE, permissionGranted=false
NO_PROVIDERS_AVAILABLE         -> UNAVAILABLE, failureCode=NO_PROVIDERS_AVAILABLE
REGISTRATION_FAILED            -> FAILED, failureCode=REGISTRATION_FAILED
callback from stale generation -> no state/fix mutation
invalid/rejected fix           -> no healthy TRACKING publication
```

Also assert raw latitude/longitude never appears in `LocationTrackingHealth.toString()` fields because coordinates are not stored in the health object.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*LocationObservationProviderTest"
```

Expected: compilation/test failure because the typed health flow does not exist.

- [ ] **Step 3: Implement typed updates at the actual lifecycle boundaries**

Update the StateFlow under the provider lock, but assign/callback outside expensive Android calls. On accepted fix publish only metadata:

```kotlin
mutableTrackingHealth.value = current.copy(
    trackingState = LocationTrackingState.TRACKING,
    isRegistered = true,
    lastFixWallClockMs = fix.wallClockMs,
    lastFixElapsedMs = fix.elapsedRealtimeMs,
    accuracyMeters = fix.accuracyMeters,
    failureCode = null,
)
```

Do not publish a valid fix if `LocationFixArbiter.accept()` rejects it. Do not store coordinates in health.

- [ ] **Step 4: Map typed Location health into SensorHealth**

Add one internal pure mapper in `ProtectionRuntimeGraph.kt`:

```kotlin
internal fun LocationTrackingHealth.toSensorHealth(): SensorHealth = SensorHealth(
    state = when (trackingState) {
        LocationTrackingState.STOPPED,
        LocationTrackingState.WAITING_FOR_FIX -> SensorHealthState.AVAILABLE
        LocationTrackingState.TRACKING -> SensorHealthState.HEALTHY
        LocationTrackingState.STALE -> SensorHealthState.STALE
        LocationTrackingState.UNAVAILABLE -> SensorHealthState.UNAVAILABLE
        LocationTrackingState.FAILED -> SensorHealthState.FAILED
    },
    lastSampleAtMs = lastFixWallClockMs,
    locationDetail = LocationHealthDetail(
        trackingState = trackingState,
        isRegistered = isRegistered,
        hardwareAvailable = hardwareAvailable,
        permissionGranted = permissionGranted,
        lastFixWallClockMs = lastFixWallClockMs,
        lastFixElapsedMs = lastFixElapsedMs,
        accuracyMeters = accuracyMeters,
        failureCode = failureCode,
    ),
)
```

After coordinator construction, seed current Location health once and collect future values:

```kotlin
coordinator.recordSensorHealth(SensorKind.LOCATION, locationProvider.trackingHealth.value.toSensorHealth())
scope.launch {
    locationProvider.trackingHealth.collect { health ->
        coordinator.recordSensorHealth(SensorKind.LOCATION, health.toSensorHealth())
    }
}
```

- [ ] **Step 5: Write the first real truth-path integration test**

In `ProtectionStatusTruthPathIntegrationTest`, use a fake `LocationUpdatesClient`, the real provider state flow, real `ProtectionCoordinator`, collector helper and real formatter. Prove:

```kotlin
@Test fun acceptedGpsFixReplacesWaitingAndFormatterNeverSaysStopped() = runTest {
    // Start tracking -> runCurrent -> assert WAITING_FOR_FIX in snapshot.
    // Emit valid fix -> runCurrent -> format the snapshot.
    assertTrue(message.contains("GPS: กำลังติดตาม"))
    assertTrue(message.contains("ความแม่นยำ"))
    assertFalse(message.contains("GPS: หยุดทำงาน"))
}
```

The test must not construct `LocationHealthDetail` directly; data must originate from the fake provider callback.

- [ ] **Step 6: Run GREEN plus pursuit regression**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*LocationObservationProviderTest" --tests "*ProtectionStatusTruthPathIntegrationTest" --tests "*LivePursuitCoordinatorTest"
```

Expected: provider/integration/pursuit tests pass; movement thresholds and Live Location behavior are unchanged.

---

### Task 5: Publish Battery, Temperature and Charging from One Typed Android Battery Update

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/PowerThermalMonitor.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt`
- Create: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/PowerThermalMonitorTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntimeTest.kt`

**Interfaces:**
- Consumes: `ACTION_BATTERY_CHANGED`, `BatteryManager.EXTRA_LEVEL`, `EXTRA_SCALE`, `EXTRA_TEMPERATURE`, `EXTRA_STATUS`, `EXTRA_PLUGGED`
- Produces: one typed `PowerThermalStatus` update plus existing incident observations

Define:

```kotlin
data class PowerThermalStatus(
    val sourceAvailable: Boolean,
    val isRegistered: Boolean,
    val batteryLevelPercent: Int?,
    val temperatureCelsius: Float?,
    val chargingState: ChargingState,
    val observedAtWallClockMs: Long,
)
```

Add constructor callback `onStatusChanged: (PowerThermalStatus) -> Unit = {}`. Keep `onObservation` for existing charger-disconnect/temperature incident behavior; do not route `/status` by parsing those observation diagnostic strings.

- [ ] **Step 1: Write RED battery intent tests**

Using Robolectric broadcast intents, assert:

```text
level=50, scale=100             -> 50%
temperature=321                -> 32.1 C
BATTERY_STATUS_CHARGING        -> CHARGING
BATTERY_STATUS_FULL            -> FULL
BATTERY_STATUS_DISCHARGING     -> DISCHARGING
BATTERY_STATUS_NOT_CHARGING    -> NOT_CHARGING
missing/invalid extras         -> null values and UNKNOWN, never 0%/0.0 C by invention
start registration success    -> isRegistered=true
stop                           -> isRegistered=false while sourceAvailable remains truthful
```

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*PowerThermalMonitorTest" --tests "*AndroidProtectionRuntimeTest"
```

Expected: failure because charging/source/registration are not emitted.

- [ ] **Step 3: Implement one typed status publication per battery broadcast**

Map charging by `EXTRA_STATUS`; use `EXTRA_PLUGGED` only to prevent an unknown plugged state from being reported as discharging. Clamp battery percentage only when level/scale are valid. Temperature `0` from a missing extra becomes null, not `0.0°C`.

In `PlatformAndroidDetectorSet`, map the callback directly:

```kotlin
publishHealth(
    SensorKind.POWER_THERMAL,
    SensorHealth(
        state = if (status.sourceAvailable && status.isRegistered) SensorHealthState.HEALTHY else SensorHealthState.UNAVAILABLE,
        lastSampleAtMs = status.observedAtWallClockMs,
        powerThermalDetail = PowerThermalHealthDetail(
            sourceAvailable = status.sourceAvailable,
            isRegistered = status.isRegistered,
            chargingState = status.chargingState,
            batteryLevelPercent = status.batteryLevelPercent,
            temperatureCelsius = status.temperatureCelsius,
            lastUpdateWallClockMs = status.observedAtWallClockMs,
        ),
    ),
)
```

Do not hard-code `ChargingState.UNKNOWN` in `AndroidProtectionRuntime.updateHealth()` after this change.

- [ ] **Step 4: Run GREEN and assert no five-second false stale**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*PowerThermalMonitorTest" --tests "*AndroidProtectionRuntimeTest" --tests "*ProtectionHealthPolicyTest" --tests "*ProtectionCoordinatorTest"
```

Expected: all pass; a valid power detail remains healthy beyond 5,000 ms unless registration/source fails.

---

### Task 6: Make Projection and Formatter Strict Consumers of Typed Snapshot Data

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusProjection.kt`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatter.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusProjectionTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatterTest.kt`

**Interfaces:**
- Consumes: one immutable `ProtectionSnapshot`, `nowWallClockMs`, `nowElapsedMs`, shared `freshness()`
- Produces: one deterministic projection and full Thai report; no I/O

- [ ] **Step 1: Replace synthetic happy-path assumptions with RED truth rules**

Add or change tests so these cases are mandatory:

```kotlin
@Test fun emptyHealthMapReportsZeroOfFiveNotFiveOfFive() {
    val p = ProtectionStatusProjection.evaluate(
        ProtectionSnapshot.offline(100_000L).copy(state = ProtectionState.ARMED_DEGRADED),
        nowWallClockMs = 100_000L,
        nowElapsedMs = 50_000L,
    )
    assertEquals(0, p.sensorSummary.activeCount)
    assertEquals(0, p.sensorSummary.readyCount)
}

@Test fun waitingGpsCountsOnlyWhenRegistered() {
    fun active(registered: Boolean): Int {
        val health = SensorHealth(
            SensorHealthState.AVAILABLE,
            locationDetail = LocationHealthDetail(
                trackingState = LocationTrackingState.WAITING_FOR_FIX,
                isRegistered = registered,
            ),
        )
        return ProtectionStatusProjection.evaluate(
            ProtectionSnapshot.offline(100_000L).copy(
                state = ProtectionState.ARMED_DEGRADED,
                sensorHealth = mapOf(SensorKind.LOCATION to health),
            ),
            100_000L,
            50_000L,
        ).sensorSummary.activeCount
    }
    assertEquals(0, active(false))
    assertEquals(1, active(true))
}

@Test fun missingHeartbeatIsNotHealthyEvenWhenServiceRunningFlagIsTrue() {
    val p = ProtectionStatusProjection.evaluate(
        ProtectionSnapshot.offline(100_000L).copy(serviceRunning = true),
        100_000L,
        50_000L,
    )
    assertFalse(p.primarySystems.serviceHealthy)
}

@Test fun futureHeartbeatAndTelegramContactAreClockAnomalies() {
    val p = ProtectionStatusProjection.evaluate(
        ProtectionSnapshot.offline(100_000L).copy(
            serviceRunning = true,
            lastServiceHeartbeatAtMs = 100_001L,
            telegramPolling = true,
            telegramReachable = true,
            lastTelegramContactAtMs = 100_001L,
        ),
        100_000L,
        50_000L,
    )
    assertFalse(p.primarySystems.serviceHealthy)
    assertFalse(p.primarySystems.telegramHealthy)
}

@Test fun microphoneFreshnessUsesNowElapsedMs() {
    val health = SensorHealth(
        SensorHealthState.HEALTHY,
        microphoneDetail = MicrophoneHealthDetail(
            audioState = AudioRuntimeState.LISTENING,
            isRegistered = true,
            modelReady = true,
            lastAudioSampleElapsedMs = 44_999L,
        ),
    )
    val p = ProtectionStatusProjection.evaluate(
        ProtectionSnapshot.offline(1_700_000_000_000L).copy(
            state = ProtectionState.ARMED_DEGRADED,
            sensorHealth = mapOf(SensorKind.MICROPHONE to health),
        ),
        nowWallClockMs = 1_700_000_000_000L,
        nowElapsedMs = 50_000L,
    )
    assertTrue(p.sensorSummary.sensors[SensorKind.MICROPHONE]?.isStale == true)
}

@Test fun gpsFreshnessUsesLastFixElapsedMsAtThirtySecondBoundary() {
    fun stale(fixAt: Long): Boolean {
        val health = SensorHealth(
            SensorHealthState.HEALTHY,
            locationDetail = LocationHealthDetail(
                trackingState = LocationTrackingState.TRACKING,
                isRegistered = true,
                lastFixElapsedMs = fixAt,
                accuracyMeters = 10f,
            ),
        )
        return ProtectionStatusProjection.evaluate(
            ProtectionSnapshot.offline(100_000L).copy(
                state = ProtectionState.ARMED_HEALTHY,
                sensorHealth = mapOf(SensorKind.LOCATION to health),
            ),
            100_000L,
            50_000L,
        ).sensorSummary.sensors.getValue(SensorKind.LOCATION).isStale
    }
    assertFalse(stale(20_000L))
    assertTrue(stale(19_999L))
}

@Test fun typedLocationFailureCodeControlsGuidanceWithoutFailureReasonText() {
    val health = SensorHealth(
        SensorHealthState.UNAVAILABLE,
        locationDetail = LocationHealthDetail(
            trackingState = LocationTrackingState.UNAVAILABLE,
            isRegistered = false,
            permissionGranted = false,
            failureCode = LocationFailureCode.PERMISSION_DENIED,
            failureReason = "unrelated diagnostic text",
        ),
    )
    val p = ProtectionStatusProjection.evaluate(
        ProtectionSnapshot.offline(100_000L).copy(sensorHealth = mapOf(SensorKind.LOCATION to health)),
        100_000L,
        50_000L,
    )
    assertTrue(p.sensorSummary.sensors.getValue(SensorKind.LOCATION).statusLineTh.contains("สิทธิ์"))
}

@Test fun incidentTypeUsesTypedFieldWhenIdIsUuid() {
    val summary = IncidentSummary(
        id = "550e8400-e29b-41d4-a716-446655440000",
        severity = IncidentSeverity.CRITICAL,
        lifecycle = IncidentLifecycle.OPEN,
        updatedAtMs = 100_000L,
        deliveryState = DeliveryState.PENDING,
        type = IncidentType.AUDIO,
    )
    val p = ProtectionStatusProjection.evaluate(
        ProtectionSnapshot.offline(100_000L).copy(lastIncident = summary),
        100_000L,
        50_000L,
    )
    assertEquals("ตรวจพบเสียงผิดปกติ", p.lastIncident?.typeTh)
}

@Test fun powerDoesNotBecomeStaleAfterFiveSeconds() {
    val power = SensorHealth(
        SensorHealthState.HEALTHY,
        powerThermalDetail = PowerThermalHealthDetail(
            sourceAvailable = true,
            isRegistered = true,
            lastUpdateWallClockMs = 1_000L,
        ),
    )
    val p = ProtectionStatusProjection.evaluate(
        ProtectionSnapshot.offline(100_000L).copy(
            state = ProtectionState.ARMED_HEALTHY,
            sensorHealth = mapOf(SensorKind.POWER_THERMAL to power),
        ),
        100_000L,
        50_000L,
    )
    assertTrue(p.sensorSummary.sensors[SensorKind.POWER_THERMAL]?.isHealthyOrWorking == true)
}
```

Delete or rewrite any test that expects an empty Disarmed Snapshot to report `พร้อมใช้งาน 5/5`.

- [ ] **Step 2: Run RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionStatusProjectionTest" --tests "*ProtectionStatusFormatterTest"
```

Expected: multiple failures from current defaults, string parsing and unused `nowElapsedMs`.

- [ ] **Step 3: Implement strict per-sensor projection**

Use the Exact Status Counting Contract table above. Required rules:

- `health == null` always returns a missing/unavailable item with `isHealthyOrWorking=false` and `isReadyOrWaiting=false`
- Vibration/Light use elapsed sample time and exact 5,000 ms boundary
- Microphone uses `audioState`, `isRegistered`, `modelReady`, hardware/permission and `lastAudioSampleElapsedMs`; do not infer `modelReady` from generic `SensorHealthState.HEALTHY`
- GPS uses `trackingState`, `isRegistered`, typed failure code, `lastFixElapsedMs` and accuracy; do not read `failureReason == "stopped"` or `detail.startsWith("fix ")`
- GPS fix older than 30,000 ms or accuracy outside `0f..100f` is stale/unusable
- Power uses `sourceAvailable` and `isRegistered`; its last broadcast age is display information, not the five-second stale rule
- Service requires `serviceRunning == true` and fresh non-null heartbeat within 10,000 ms
- Telegram requires polling, reachable, and fresh non-null contact within 20,000 ms
- Arm duration uses `protectionActivatedAtMs` only; do not fall back to `lastTransitionAtMs`
- Last incident maps `IncidentSummary.type`; null type displays generic abnormal event without inspecting ID

- [ ] **Step 4: Use one shared freshness classifier**

Every age decision calls the Task 1 `freshness()` function. Format age only after `FRESH` or `STALE`; `CLOCK_ANOMALY` must create an issue/recommendation and must never display a negative age.

Verify `nowElapsedMs` has actual references:

```powershell
rg -n "nowElapsedMs" app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusProjection.kt
```

Expected: references in microphone, GPS and vibration/light freshness branches, not only in the function signature.

- [ ] **Step 5: Keep formatter pure and bounded**

`ProtectionStatusFormatter` may catch `RuntimeException` to return the existing Thai fallback, but must not catch `Throwable`. Keep section order and full report copy from the approved Design Spec. Do not add coordinates or raw diagnostics.

- [ ] **Step 6: Run GREEN and privacy/string scans**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionStatusProjectionTest" --tests "*ProtectionStatusFormatterTest"
rg -n 'startsWith\("fix |failureReason\s*==\s*"|incident\.id\.contains|trackingMode' app/src/main/java/com/example/motorcycleantitheftsensor/telegram app/src/main/java/com/example/motorcycleantitheftsensor/protection/AndroidProtectionRuntime.kt
rg -n 'botToken|chatId|maps\.google|latitude|longitude' app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusProjection.kt app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatter.kt
```

Expected: formatter/projection tests pass; first scan has no production decision matches; privacy scan has no output.

---

### Task 7: Prove Command Purity and End-to-End Truth Paths

**Files:**
- Modify only if RED requires: `app/src/main/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandler.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/TelegramCommandHandlerTest.kt`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusTruthPathIntegrationTest.kt`

**Interfaces:**
- Consumes: real coordinator snapshot updates from Tasks 2–5 and real formatter
- Produces: one side-effect-free Telegram reply per `/status`

- [ ] **Step 1: Write RED/characterization handler test before touching handler**

```kotlin
@Test fun statusRepliesExactlyOnceWithoutRuntimeOrSensorSideEffects() = runTest {
    val beforeStart = runtime.startCalls
    val beforeStop = runtime.stopCalls
    val beforeReads = runtime.currentSensorHealthCalls
    val replies = mutableListOf<String>()

    handler.handle("status-1", RemoteCommand.Status, replies::add)

    assertEquals(1, replies.size)
    assertTrue(replies.single().startsWith("🛡️ สถานะระบบป้องกัน"))
    assertEquals(beforeStart, runtime.startCalls)
    assertEquals(beforeStop, runtime.stopCalls)
    assertEquals(beforeReads, runtime.currentSensorHealthCalls)
}
```

If this passes with the existing one-line handler, do not modify production handler.

- [ ] **Step 2: Add producer-to-message integration tests**

Tests must originate from producer callbacks/flows, not hand-built complete Snapshots:

```text
Location Started -> /status says waiting for first fix, not stopped
Location valid fix -> /status says tracking with age/accuracy, not stopped
Audio STARTING -> CALIBRATING -> LISTENING -> /status follows each transition
Audio sample older than 5,000 ms -> microphone stale/degraded and active count decreases
Battery broadcast -> percentage, Celsius and charging source match one intent
Heartbeat/contact missing -> system issue, not healthy
Sensitivity command level 8 -> next /status says 8/10
Arm -> Alert -> return Armed -> arm duration origin unchanged
SecurityIncident UUID + typed type -> correct Thai incident type
Missing all five health entries -> 0/5
```

Use two injected clocks in integration tests:

```kotlin
var wallNowMs = 1_700_000_000_000L
var elapsedNowMs = 100_000L
```

Advance only the clock appropriate to the case to prove wall/elapsed values are not mixed.

- [ ] **Step 3: Run focused truth-path gate**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionStatusTruthPathIntegrationTest" --tests "*TelegramCommandHandlerTest" --tests "*ProtectionStatusProjectionTest" --tests "*ProtectionStatusFormatterTest"
```

Expected: all tests pass, one response per command, zero runtime/sensor calls caused by `/status`.

- [ ] **Step 4: Run related regression gate**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AndroidProtectionRuntimeTest" --tests "*LocationObservationProviderTest" --tests "*LivePursuitCoordinatorTest" --tests "*ProtectionCoordinatorTest" --tests "*ProtectionHealthPolicyTest" --tests "*ProtectionStatusTruthPathIntegrationTest" --tests "*ProtectionStatusProjectionTest" --tests "*ProtectionStatusFormatterTest" --tests "*TelegramCommandHandlerTest"
```

Expected: all related tests pass; record exact count, failures, errors and skipped in evidence.

---

### Task 8: Full Verification, APK Identity and Huawei Real-Device Acceptance

**Files:**
- Modify: `docs/superpowers/status/2026-08-15-telegram-full-status-truth-path-evidence.md`
- Do not modify production code during evidence collection; a discovered failure returns to the owning Task

**Interfaces:**
- Consumes: completed Task 1–7 implementation
- Produces: host/build/install/device evidence sufficient to close the work

- [ ] **Step 1: Audit exact changed scope before full tests**

```powershell
git status --short
git diff --check
git diff --name-only
```

Compare every changed production path with the Allowed map. Existing unrelated dirty files may remain but must not have new diffs caused by this plan.

- [ ] **Step 2: Run full unit and build gates sequentially**

Do not overlap Gradle/ADB because this machine has previously hit Windows error 1455.

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --stop
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' assembleDebug
```

Expected: both commands exit 0. Record test count and APK SHA-256:

```powershell
Get-FileHash .\app\build\outputs\apk\debug\app-debug.apk -Algorithm SHA256
Get-Item .\app\build\outputs\apk\debug\app-debug.apk | Select-Object FullName,Length,LastWriteTime
```

- [ ] **Step 3: Install the exact APK on Huawei**

```powershell
adb devices -l
adb -s JUCDU18811013149 install -r .\app\build\outputs\apk\debug\app-debug.apk
adb -s JUCDU18811013149 shell dumpsys package com.example.motorcycleantitheftsensor | Select-String 'versionCode|versionName|lastUpdateTime'
```

Expected: install `Success`; installed `lastUpdateTime` is after local APK `LastWriteTime`. If device serial differs, record the actual serial instead of installing to an unverified target.

- [ ] **Step 4: Execute the real-device matrix in order**

For every row, save Telegram screenshot time, app state, relevant permission setting and a short log excerpt without coordinates or secrets.

| Case | Action | Required `/status` evidence |
|---|---|---|
| D1 | Disarm | full header; sensors stopped by command; ready count based on real hardware/permission, never default 5/5 |
| D2 | Arm and send `/status` during startup | ARMING; microphone STARTING/CALIBRATING; GPS waiting only if registered |
| D3 | Wait through calibration with Location enabled | Armed; microphone LISTENING/model readiness truthful; GPS waiting or tracking, never stale `stopped` |
| D4 | Obtain fresh GPS fix | tracking line includes non-negative age and accuracy; no coordinate/map URL |
| D5 | Disable Location permission/provider, then Arm | typed Thai problem and correction; GPS not counted active |
| D6 | Restore Location and Arm again | recovery to waiting/tracking without reinstall or app-data clear |
| D7 | Deny microphone permission, then Arm | microphone permission guidance; no `listener unavailable` as the only explanation |
| D8 | Restore microphone permission and Arm again | CALIBRATING then LISTENING/model state updates |
| D9 | Plug/unplug charger | percentage, battery temperature and charging state match Android device state |
| D10 | Trigger one controlled non-demo incident | last incident type/lifecycle/delivery match the actual alert; no type guessed from UUID |
| D11 | While Alert is active | GPS remains registered/tracking and arm duration does not reset |
| D12 | Send `/status` five times | five replies, no ANR, no listener restart, no duplicate incident, no SMS |

If a test action could trigger real SMS/emergency call, do not execute it. Use a controlled condition in which external escalation is disabled by the existing approved configuration; do not change production alert rules for testing.

- [ ] **Step 5: Compare Telegram with authoritative local evidence**

At the same timestamps compare:

```text
Protection state
Service running/heartbeat
Telegram polling/contact
Vibration, light, microphone, GPS, power health
Battery percentage/temperature/charging
Sensitivity
Last incident and delivery
```

Telegram may format data differently from UI/notification, but the underlying state must agree. Automated tests or APK installation alone do not satisfy this step.

- [ ] **Step 6: Run final privacy and dead-code scans**

```powershell
rg -n 'trackingMode|startsWith\("fix |failureReason\s*==\s*"|incident\.id\.contains' app/src/main/java/com/example/motorcycleantitheftsensor
rg -n 'botToken|chatId|maps\.google|latitude|longitude' app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusProjection.kt app/src/main/java/com/example/motorcycleantitheftsensor/telegram/ProtectionStatusFormatter.kt
rg -n 'LocationTrackingState' app/src/main/java/com/example/motorcycleantitheftsensor
```

Expected: no string-derived status decisions or privacy leakage; `LocationTrackingState` is used by producer and projection, not merely declared.

- [ ] **Step 7: Complete evidence and issue final disposition**

Set `Final Disposition: ACCEPTED` only when focused tests, related regression, full unit tests, assemble, exact APK install and all applicable device rows pass. Otherwise use:

```markdown
## Final Disposition
NOT ACCEPTED

### Blocking failures
- Gate:
- Exact observed result:
- Owning task:
- Next safe action:
```

Do not say “ใช้งานได้สมบูรณ์” when only host tests pass.

## Final Completion Criteria

Work is complete only when every statement below is true:

- GPS registration and accepted fix continuously update the authoritative Snapshot; `/status` cannot remain `stopped` while tracking has a valid fix
- Microphone lifecycle/model/sample follow real `AudioTelemetry` without requiring a threat detection event
- All five groups are counted from explicit health entries and registration/readiness; missing data never becomes 5/5
- Battery percentage, battery temperature and charging state come from one typed Android battery update
- Heartbeat, Telegram contact, sensitivity and arm activation time have real producers
- Arm duration survives Alert transitions and clears on Disarm/new arm failure
- Incident type comes from `SecurityIncident.type`, never UUID text
- Formatter/projection use typed state and shared freshness, including clock-anomaly handling
- `/status` reads one Snapshot revision, performs no hardware/network wait, replies exactly once and leaks no secrets/coordinates
- Focused, related, full unit and assemble gates pass
- The exact APK is installed and the Huawei/Telegram device matrix has current evidence
- Unrelated dirty worktree content remains preserved and unstaged
