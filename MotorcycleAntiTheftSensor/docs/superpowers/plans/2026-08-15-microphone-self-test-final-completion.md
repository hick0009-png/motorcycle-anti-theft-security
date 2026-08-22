# Microphone Self-Test Final Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** ปิดปัญหา Test Microphone และ Gradle test hang ให้จบ โดย self-test ต้องจบภายในเวลาที่กำหนด ยกเลิกได้ คืนทรัพยากรไมค์เสมอ ไม่เปลี่ยนพฤติกรรม audio capture ตอน Arm และไม่มี test fixture ที่สามารถ deadlock แบบเดิมได้อีก

**Architecture:** แยกโหมดอ่านเสียงที่ interface ของ recorder ให้ชัดเจน: self-test ใช้ `NON_BLOCKING` ส่วน protection capture ตอน Arm ใช้ `BLOCKING` เหมือนเดิม จากนั้นครอบ self-test ด้วย monotonic deadline และ coroutine safety timeout พร้อม rethrow cancellation ก่อน generic exception handling การแก้ test hang จำกัดอยู่ที่ test fixture และ lifecycle cleanup; ไม่แตะ Telegram, SMS production, GPS, sensor fusion, classifier หรือ navigation

**Tech Stack:** Kotlin, Android `AudioRecord`, Kotlin Coroutines, JUnit4, kotlinx-coroutines-test, Jetpack Compose UI tests, Gradle Kotlin DSL

## Global Constraints

- Repository: `D:\security\MotorcycleAntiTheftSensor`
- Baseline reviewed on 2026-08-15: HEAD `868e2b7`; worktree มีงาน GPS/audio/UI ที่ยังไม่ commit และต้องรักษาไว้ทั้งหมด
- ก่อนแก้ให้รัน `git status --short` และบันทึก baseline; ห้าม `git reset`, `git clean`, `git checkout --`, stash, ลบไฟล์ หรือ revert งานเดิม
- แผนนี้เป็น final remediation ต่อจาก `docs/superpowers/plans/2026-08-15-microphone-ui-operation-state-remediation.md`
- แผนเดิมห้ามแก้ audio runtime; แผนนี้อนุญาตข้อยกเว้นเฉพาะไฟล์ audio ที่ระบุใน Allowed Files ด้านล่าง เพื่อแก้ blocking self-test เท่านั้น
- ห้ามเปลี่ยน classifier, YAMNet model, calibration, audio gate, candidate buffer, sensor fusion rules, Telegram, SMS production, GPS, incident persistence, navigation หรือ Gradle dependencies
- ห้ามเปลี่ยน sample rate 16 kHz, frame size 1,600 samples หรือช่วงตรวจ self-test ปกติ 5,000 ms
- Audio capture ตอน Arm ต้องยังขอ `BLOCKING`; ห้ามเปลี่ยนเป็น `NON_BLOCKING`
- Test Microphone เท่านั้นที่ต้องขอ `NON_BLOCKING`
- ห้ามใช้ `Thread.sleep()` หรือ `runBlocking { deferred.await() }` เพื่อควบคุม coroutine test
- ห้ามแสดง raw exception, raw `detail`, token, key หรือข้อมูลลับใน UI/log/test artifact
- ไม่เพิ่ม dependency ใหม่
- ห้ามสร้าง commit จนกว่าเจ้าของโปรเจกต์อนุญาตต่างหาก
- รัน Gradle ทีละคำสั่งด้วย JBR และ `--max-workers=1`; ห้ามเปิด Gradle/ADB หลายงานพร้อมกัน
- Host tests/build ไม่ใช่หลักฐานว่าไมค์บนมือถือผ่าน ต้องทำ exact-APK device acceptance ก่อนปิดงาน

## Baseline Evidence and Known Failures

สิ่งที่ผ่านแล้วก่อนเริ่มแผนนี้:

- `ProtectionViewModelTest`: 36 tests, 0 failures, 0 errors
- `AudioThreatPipelineTest`: 20 tests, 0 failures, 0 errors
- Full `testDebugUnitTest`: 447 tests, 0 failures, 0 errors, 0 skipped
- `assembleDebug`: PASS
- `compileDebugAndroidTestKotlin`: PASS

สิ่งที่ยังไม่สมบูรณ์และแผนนี้ต้องปิด:

1. `AndroidAudioRecorderBackend.read()` ใช้ `AudioRecord.read(short[], offset, size)` แบบ 3 arguments ซึ่ง Android ส่งต่อเป็น `READ_BLOCKING`
2. `runSelfTest()` เพิ่มเวลาหลัง `backend.read()` คืนค่าเท่านั้น และไม่มี hard timeout; ถ้า read ค้าง cleanup และ UI `finally` จะไปไม่ถึง
3. `runSelfTest()` จับ `CancellationException` รวมใน `catch (Exception)`
4. ไม่มี unit test ที่เรียก `AudioThreatPipeline.runSelfTest()` โดยตรง
5. `FakeProtectionSettingsGateway` ยังมี dead `allowSmsFallback: CompletableDeferred<Unit>?` และ `runBlocking { await() }` ซึ่งสร้าง deadlock แบบเดิมได้ถ้าถูกนำกลับมาใช้
6. SMS ownership test ใช้ ViewModel scope บน `Dispatchers.Default` แต่ไม่ได้ clear lifecycle หลังจบ test
7. Settings แสดง failure เป็น `Failed (<raw detailCode>)`; ต้องแปลงเป็นข้อความปลอดภัย รวม `SELF_TEST_TIMEOUT`

## Allowed Files

แก้ได้เฉพาะ:

- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipeline.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipelineTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetectorSourceContractTest.kt`
- `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt`
- `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt`
- `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`
- Create: `docs/superpowers/status/2026-08-15-microphone-self-test-final-evidence.md`

ไฟล์อื่นเป็น read-only สำหรับแผนนี้ หาก implementation บังคับให้แก้นอกขอบเขต ให้หยุดและรายงานเจ้าของ ห้ามเดาและห้ามขยาย scope เอง

## Required Final Behavior

- เมื่อ Disarmed และกด Test Microphone งานต้องจบภายใน 8 วินาทีบนมือถือจริง ไม่ว่าจะได้ sample, ไม่ได้ sample, permission ถูกปฏิเสธ หรือ backend ผิดปกติ
- เวลาจับเสียงเป้าหมายยังคง 5 วินาที; 8 วินาทีเป็น acceptance ceiling รวม startup/cleanup/UI projection ไม่ใช่ duration ใหม่
- ถ้าได้ sample อย่างน้อยหนึ่งครั้ง: `success=true`, detail prefix `OK`, observed dBFS เป็น finite value
- ถ้า 5 วินาทีไม่มี sample: `success=false`, detail `NO_SAMPLES_READ`
- ถ้า safety timeout ทำงาน: `success=false`, detail `SELF_TEST_TIMEOUT`
- External coroutine cancellation ต้อง propagate เป็น cancellation ไม่ถูกแปลงเป็น failure result
- ทุก terminal path หลังผ่าน initialized check และพยายาม start แล้วต้องเรียก `stop()` และ `release()` อย่างละหนึ่งครั้งเท่าที่ backend อนุญาต; backend ที่ไม่ initialized ต้องถูก `release()` หนึ่งครั้งก่อน return
- `settingsOperationInFlight` และ `activeSettingsOperation` ต้องถูกล้างเสมอเมื่อ self-test สำเร็จ ล้มเหลว timeout หรือ cancellation
- Self-test ห้ามส่ง Telegram, SMS, notification, event หรือ popup
- หลัง self-test เสร็จ ต้อง Arm ได้และ audio runtime ต้องกลับไป Calibrating/Listening ได้ตามเดิม
- Active protection capture ต้องใช้ blocking read ต่อไป เพื่อไม่เปลี่ยน cadence ของ classifier/fusion

---

### Task 1: Preflight Scope Lock and Reproduce the Missing Coverage

**Files:**
- Read only: all Allowed Files
- Do not modify production in this task

**Interfaces:**
- Consumes: current dirty worktree and baseline tests
- Produces: exact pre-edit scope record and RED test list

- [ ] **Step 1: Record the worktree without cleaning it**

Run:

```powershell
Set-Location 'D:\security\MotorcycleAntiTheftSensor'
git rev-parse HEAD
git status --short
git diff --check
```

Expected: record HEAD and every dirty/untracked path. `git diff --check` may report pre-existing issues; classify them without editing unrelated files.

- [ ] **Step 2: Confirm the four unresolved source patterns**

Run:

```powershell
rg -n "fun read\(target: ShortArray\)|recorder\.read\(target, 0, target\.size\)|runSelfTest\(|catch \(e: Exception\)|allowSmsFallback|runBlocking" app/src/main app/src/test
rg -n "runSelfTest\(" app/src/test app/src/androidTest
```

Expected before implementation:

- production has only `fun read(target: ShortArray)`;
- Android backend calls the 3-argument `AudioRecord.read`;
- `runSelfTest()` has generic exception handling and no direct tests;
- the old `allowSmsFallback`/`runBlocking` branch still exists.

- [ ] **Step 3: Run the focused baseline sequentially**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AudioThreatPipelineTest"
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionViewModelTest"
```

Expected: both finish without hanging. Record counts; this is baseline only and must not be called proof that self-test timeout is fixed.

---

### Task 2: Make Recorder Read Mode Explicit Without Changing Armed Capture

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipeline.kt:25-32,181-189,341-361`
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt:22-61`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipelineTest.kt:24-65`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetectorSourceContractTest.kt`

**Interfaces:**
- Produces: `enum class AudioReadMode { BLOCKING, NON_BLOCKING }`
- Produces: `AudioRecorderBackend.read(target: ShortArray, mode: AudioReadMode): Int`
- Active capture consumes `BLOCKING`; self-test consumes `NON_BLOCKING`

- [ ] **Step 1: Add RED mode-ownership tests**

Update `TrackedRecorderBackend` to record every requested mode:

```kotlin
val readModes = mutableListOf<AudioReadMode>()

override fun read(target: ShortArray, mode: AudioReadMode): Int {
    readCalls.incrementAndGet()
    readModes += mode
    if (!recordingState) return -1
    returnErrorCode?.let { return it }
    val frame = frameProvider()
    val toCopy = minOf(target.size, frame.size)
    System.arraycopy(frame, 0, target, 0, toCopy)
    return toCopy
}
```

Add exact tests:

```kotlin
@Test fun selfTestRequestsOnlyNonBlockingReads()
@Test fun armedCaptureRequestsOnlyBlockingReads()
```

Required assertions:

```kotlin
assertTrue(recorder.readModes.isNotEmpty())
assertTrue(recorder.readModes.all { it == AudioReadMode.NON_BLOCKING })
```

and separately:

```kotlin
assertTrue(recorder.readModes.isNotEmpty())
assertTrue(recorder.readModes.all { it == AudioReadMode.BLOCKING })
```

The two behaviors must use separate recorder instances/tests. Do not run self-test while Armed to manufacture the assertion.

- [ ] **Step 2: Strengthen the Android source contract RED**

In `AudioPeakDetectorSourceContractTest`, add assertions that the Android backend:

```kotlin
assertTrue(content.contains("AudioRecord.READ_BLOCKING"))
assertTrue(content.contains("AudioRecord.READ_NON_BLOCKING"))
assertTrue(content.contains("recorder.read(target, 0, target.size, readMode)"))
assertFalse(content.contains("recorder.read(target, 0, target.size)"))
```

Keep the existing 16 kHz and no-PCM-file assertions.

- [ ] **Step 3: Run RED tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AudioThreatPipelineTest" --tests "*AudioPeakDetectorSourceContractTest"
```

Expected: compile/test FAIL because `AudioReadMode` and explicit read mode do not exist yet. A pass means the tests are not exercising the intended contract; stop and correct the tests.

- [ ] **Step 4: Add the exact mode API**

In `AudioThreatPipeline.kt`, replace the backend interface with:

```kotlin
enum class AudioReadMode {
    BLOCKING,
    NON_BLOCKING,
}

interface AudioRecorderBackend {
    val initialized: Boolean
    val recording: Boolean
    fun start()
    fun read(target: ShortArray, mode: AudioReadMode): Int
    fun stop()
    fun release()
}
```

Do not provide a default mode. Both call sites must state intent explicitly so a later refactor cannot silently choose the wrong mode.

- [ ] **Step 5: Map the mode in the Android backend**

In `AndroidAudioRecorderBackend`:

```kotlin
override fun read(target: ShortArray, mode: AudioReadMode): Int {
    val recorder = audioRecord ?: return AudioRecord.ERROR_INVALID_OPERATION
    val readMode = when (mode) {
        AudioReadMode.BLOCKING -> AudioRecord.READ_BLOCKING
        AudioReadMode.NON_BLOCKING -> AudioRecord.READ_NON_BLOCKING
    }
    return recorder.read(target, 0, target.size, readMode)
}
```

Add the `AudioReadMode` import. `minSdk=24`, therefore the four-argument overload introduced in API 23 is valid without compatibility branching.

- [ ] **Step 6: Update the two production call sites only**

Self-test:

```kotlin
val readSize = backend.read(buffer, AudioReadMode.NON_BLOCKING)
```

Active protection capture:

```kotlin
val readSize = backend.read(frameBuffer, AudioReadMode.BLOCKING)
```

Do not alter frame sizes, ring buffer, inference queue, calibration, recovery or telemetry logic.

- [ ] **Step 7: Run GREEN tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AudioThreatPipelineTest" --tests "*AudioPeakDetectorSourceContractTest"
```

Expected: PASS and both mode-ownership tests observe at least one read.

---

### Task 3: Bound Self-Test by Monotonic Deadline, Safety Timeout, and Cancellation

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipeline.kt:137-219`
- Test: `app/src/test/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipelineTest.kt`

**Interfaces:**
- Consumes: Task 2 `AudioReadMode.NON_BLOCKING`
- Produces stable result codes: `OK`, `NO_SAMPLES_READ`, `SELF_TEST_TIMEOUT`, existing initialization/start codes
- External cancellation remains cancellation

- [ ] **Step 1: Make the test helper accept an explicit clock and delay**

Extend only the test helper signature:

```kotlin
private fun createPipeline(
    recorderSupplier: () -> TrackedRecorderBackend = {
        TrackedRecorderBackend().also { createdRecorders.add(it) }
    },
    classifierSupplier: () -> TrackedClassifier = {
        TrackedClassifier().also { createdClassifiers.add(it) }
    },
    clock: () -> Long = { simulatedTimeMs },
    delayAction: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) },
): AudioThreatPipeline
```

Pass them as:

```kotlin
timeProvider = clock,
delayProvider = delayAction,
```

Keep all existing callers on the defaults; do not change their timing semantics.

- [ ] **Step 2: Add direct RED self-test cases**

Add exact tests:

```kotlin
@Test fun selfTestWithSamplesCompletesAtDeadlineAndReleasesOnce()
@Test fun selfTestWithoutSamplesReturnsNoSamplesAndReleasesOnce()
@Test fun selfTestSafetyTimeoutReturnsTypedFailureAndReleasesOnce()
@Test fun selfTestExternalCancellationPropagatesAndReleasesOnce()
@Test fun selfTestStartFailureStillStopsAndReleasesOnce()
```

Required arrangements and assertions:

- Normal tests use `clock = { testScheduler.currentTime }` and the normal coroutine `delay`.
- Sample test uses a positive 1,600-short frame and `durationMs = 100L`; expect `success=true`, `detail.startsWith("OK")`, finite dBFS, `stopCalls=1`, `releaseCalls=1`.
- No-sample test uses `ShortArray(0)` and `durationMs = 100L`; expect exactly `NO_SAMPLES_READ`, `stopCalls=1`, `releaseCalls=1`.
- Safety-timeout test uses `clock = { 1_000L }` so the monotonic deadline never advances, but leaves coroutine delay operational; expect exactly `SELF_TEST_TIMEOUT`, bounded virtual completion, `stopCalls=1`, `releaseCalls=1`.
- Cancellation test launches `runSelfTest(5_000L)`, runs the first scheduled work, cancels and joins the job; assert `job.isCancelled`, no result was delivered, and stop/release are each exactly one.
- Start-failure test uses `allowRecording=false`; expect `START_RECORDING_FAILED`, stop/release exactly one.

Use `async`, `launch`, `runCurrent`, `advanceTimeBy`, `advanceUntilIdle`, `cancelAndJoin` from kotlinx-coroutines-test. Do not use wall-clock sleep and do not block the test thread.

- [ ] **Step 3: Run the new tests RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AudioThreatPipelineTest"
```

Expected: at least the timeout and cancellation contract tests FAIL before implementation.

- [ ] **Step 4: Implement monotonic duration and safety timeout**

Add imports:

```kotlin
import kotlinx.coroutines.withTimeoutOrNull
```

Add constants inside `AudioThreatPipeline`:

```kotlin
private companion object {
    const val SELF_TEST_POLL_INTERVAL_MS = 50L
    const val SELF_TEST_TIMEOUT_GRACE_MS = 1_000L
}
```

Inside `runSelfTest`, normalize the requested duration first, then after successful `backend.start()` and recording-state check use the normalized values:

```kotlin
val boundedDurationMs = durationMs.coerceIn(1L, 30_000L)
val safetyTimeoutMs = boundedDurationMs + SELF_TEST_TIMEOUT_GRACE_MS

val captureResult = withTimeoutOrNull(safetyTimeoutMs) {
    val buffer = ShortArray(1600)
    val startedAtMs = timeProvider()
    var samplesReadTotal = 0L
    var maxRmsDbfs = -120.0

    while (isActive && activeRun == null) {
        val nowMs = timeProvider()
        val elapsedMs = (nowMs - startedAtMs).coerceAtLeast(0L)
        if (elapsedMs >= boundedDurationMs) break

        val readSize = backend.read(buffer, AudioReadMode.NON_BLOCKING)
        if (readSize > 0) {
            samplesReadTotal += readSize
            val features = calibrator.extractFeatures(buffer, readSize, nowMs)
            maxRmsDbfs = max(maxRmsDbfs, features.rmsDbfs)
        }

        val remainingMs = boundedDurationMs - elapsedMs
        delayProvider(minOf(SELF_TEST_POLL_INTERVAL_MS, remainingMs))
    }

    if (samplesReadTotal > 0L) {
        MicrophoneSelfTestResult(
            success = true,
            detail = "OK: $samplesReadTotal samples observed",
            observedLevelDbfs = maxRmsDbfs,
        )
    } else {
        MicrophoneSelfTestResult(
            success = false,
            detail = "NO_SAMPLES_READ",
            observedLevelDbfs = null,
        )
    }
}

captureResult ?: MicrophoneSelfTestResult(
    success = false,
    detail = "SELF_TEST_TIMEOUT",
    observedLevelDbfs = null,
)
```

Rules:

- Use the exact `boundedDurationMs` and `safetyTimeoutMs` normalization shown above everywhere in the loop; do not add the untrusted input directly to the timeout.
- Do not calculate duration by incrementing a synthetic counter.
- Do not use wall clock (`System.currentTimeMillis`) for elapsed duration.
- Do not wrap the call in `runBlocking` or create a new thread/executor.

- [ ] **Step 5: Preserve structured cancellation**

Before the existing generic catch, add exactly:

```kotlin
} catch (exception: CancellationException) {
    throw exception
} catch (exception: Exception) {
```

Keep stop/release in `finally`. Do not catch `Throwable`; fatal errors must not be converted to an ordinary microphone result.

- [ ] **Step 6: Confirm cleanup occurs once on every path**

Keep one cleanup block:

```kotlin
finally {
    try {
        backend.stop()
    } catch (_: Exception) {
    }
    try {
        backend.release()
    } catch (_: Exception) {
    }
}
```

Do not add stop/release calls inside individual success, timeout or cancellation branches. The pre-start `!backend.initialized` branch may release directly because it returns before entering the `try/finally`; its existing behavior remains.

- [ ] **Step 7: Run GREEN and a forced rerun**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AudioThreatPipelineTest"
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AudioThreatPipelineTest" --rerun-tasks
```

Expected: both runs finish; no hang; every new test passes.

---

### Task 4: Remove the Old Test Deadlock Seam and Close ViewModel Lifecycle

**Files:**
- Modify: `app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt:663-705,1197-1267`
- Production files: none

**Interfaces:**
- Removes: unused `allowSmsFallback: CompletableDeferred<Unit>?`
- Retains: bounded `CountDownLatch` SMS test gate
- Produces: lifecycle-clean ownership test

- [ ] **Step 1: Prove the old branch is unused**

```powershell
rg -n "allowSmsFallback" app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt
```

Expected: one constructor declaration, one dead `runBlocking` branch, and the current latch argument. No live test passes `allowSmsFallback`.

- [ ] **Step 2: Remove the dead coroutine gate**

Delete:

```kotlin
private val allowSmsFallback: CompletableDeferred<Unit>? = null,
```

Replace `saveSmsFallback` gate handling with:

```kotlin
smsFallbackStarted?.countDown()
allowSmsFallbackLatch?.let { gate ->
    require(gate.await(5, TimeUnit.SECONDS)) { "SMS save gate timed out" }
}
```

Then continue existing failure/write/result logic unchanged. No `runBlocking` may remain in this fake.

- [ ] **Step 3: Make the real-dispatcher test lifecycle-owned**

In `smsSaveOwnsOnlySaveSmsFallbackOperation`, create the ViewModel through a local `ViewModelStore` and `ViewModelProvider.Factory`, then clear it in `finally`:

```kotlin
val store = ViewModelStore()
val factory = object : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ProtectionViewModel(
            coordinator = fakeCoordinator(ProtectionState.DISARMED_ONLINE),
            incidents = FakeIncidentRepository(),
            settings = settings,
            nowMs = { 1_000L },
            ticker = emptyFlow(),
            dispatcher = Dispatchers.Default,
            callbackDispatcher = Dispatchers.Default,
        ) as T
    }
}
val vm = ViewModelProvider(store, factory)[ProtectionViewModel::class.java]
```

Cleanup:

```kotlin
finally {
    allowSmsSave.countDown()
    store.clear()
}
```

Add imports for `androidx.lifecycle.ViewModel`, `ViewModelProvider`, and `ViewModelStore`. Do not add a production test hook and do not make `ProtectionViewModel` open.

- [ ] **Step 4: Keep every wall-clock wait bounded**

The test may retain the existing 2-second latch wait and two 2-second polling deadlines because it intentionally exercises `Dispatchers.Default`. Every loop must include a deadline and 10 ms maximum polling sleep. No unbounded `await`, `join`, or loop is allowed.

- [ ] **Step 5: Run the previously hanging class three times**

Run sequentially:

```powershell
1..3 | ForEach-Object {
    .\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionViewModelTest" --rerun-tasks
    if ($LASTEXITCODE -ne 0) { throw "ProtectionViewModelTest failed on iteration $_" }
}
```

Expected: all three complete without manual termination. Parse the final XML and record test/failure/error/skipped counts.

- [ ] **Step 6: Verify the deadlock pattern is gone**

```powershell
rg -n "allowSmsFallback:|runBlocking" app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt
```

Expected: no output for either pattern. The bounded `allowSmsFallbackLatch` may remain; this exact search intentionally excludes it.

---

### Task 5: Render Timeout as Safe Persistent UI Copy

**Files:**
- Modify: `app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt:392-405`
- Test: `app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt`

**Interfaces:**
- Consumes: `MicrophoneSelfTestUiResult.detailCode`
- Produces: safe friendly failure copy; never renders raw detail code

- [ ] **Step 1: Add RED UI mapping tests**

Add exact tests:

```kotlin
@Test fun microphoneTimeoutShowsFriendlyPersistentFailure()
@Test fun unknownMicrophoneFailureNeverRendersRawDetailCode()
```

Timeout state:

```kotlin
selfTestResult = MicrophoneSelfTestUiResult(
    success = false,
    detailCode = "SELF_TEST_TIMEOUT",
    observedLevelDbfs = null,
    completedAtMs = 1_000L,
)
```

Required assertions:

- UI contains `Microphone test timed out. Try again.`
- UI does not contain `SELF_TEST_TIMEOUT`
- result remains after advancing Compose clock beyond Snackbar duration
- no Snackbar node exists

Unknown-code state uses `detailCode = "RAW_SECRET_DRIVER_FAILURE"`; assert that exact string is absent and generic `Microphone test failed.` is present.

- [ ] **Step 2: Compile RED**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' compileDebugAndroidTestKotlin
```

If source compiles because the assertion is runtime-only, run the focused connected test when an authorized device is already connected. Record RED as assertion failure, not compile failure.

- [ ] **Step 3: Add one bounded mapper in `SettingsScreen.kt`**

```kotlin
internal fun microphoneSelfTestFailureText(detailCode: String): String = when {
    detailCode == "MIC_BUSY_ARMED" -> "Microphone is currently used by protection."
    detailCode == "NOT_INITIALIZED" -> "Microphone could not be initialized."
    detailCode == "START_RECORDING_FAILED" -> "Recording could not start."
    detailCode == "NO_SAMPLES_READ" -> "No audio samples were received."
    detailCode == "SELF_TEST_TIMEOUT" -> "Microphone test timed out. Try again."
    detailCode == "INIT_FAILED" -> "Microphone test failed."
    detailCode == "EXCEPTION" -> "Microphone test failed."
    detailCode == "SELF_TEST_EXCEPTION" -> "Microphone test failed."
    detailCode.startsWith("MICROPHONE_TEST_NOT_ALLOWED_IN_") -> "Disarm protection before testing."
    else -> "Microphone test failed."
}
```

Render failure with this function. Do not interpolate `result.detailCode` into visible text. Preserve the current success rendering and timestamp.

- [ ] **Step 4: Compile Android tests GREEN**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' compileDebugAndroidTestKotlin
```

Expected: PASS. Device execution remains a separate gate in Task 7.

---

### Task 6: Host Regression and Scope Gate

**Files:**
- Verify all Allowed Files
- Do not add more source changes during this task unless a failure is directly caused by Tasks 2-5

**Interfaces:**
- Produces: reproducible host evidence and exact scoped diff

- [ ] **Step 1: Run focused tests sequentially**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*AudioThreatPipelineTest" --tests "*AudioPeakDetectorSourceContractTest"
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest --tests "*ProtectionViewModelTest"
```

Expected: both finish with zero failures/errors and no stuck worker.

- [ ] **Step 2: Run full host gates sequentially**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' testDebugUnitTest
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' assembleDebug
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' compileDebugAndroidTestKotlin
```

Expected: all PASS. Count tests from `app/build/test-results/testDebugUnitTest/TEST-*.xml`; do not report only `BUILD SUCCESSFUL`.

- [ ] **Step 3: Verify no Gradle test worker remains**

```powershell
jps -lv
```

Expected: no `GradleWorkerMain` or test executor from the completed run. Do not kill unrelated Java processes such as IDE/Kotlin language servers.

- [ ] **Step 4: Audit the exact implementation scope**

```powershell
git diff --check
git status --short
git diff -- app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipeline.kt app/src/main/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetector.kt app/src/test/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipelineTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/sensor/AudioPeakDetectorSourceContractTest.kt app/src/test/java/com/example/motorcycleantitheftsensor/ui/ProtectionViewModelTest.kt app/src/main/java/com/example/motorcycleantitheftsensor/ui/settings/SettingsScreen.kt app/src/androidTest/java/com/example/motorcycleantitheftsensor/ui/ProtectionAppScreenTest.kt
rg -n "backend\.read" app/src/main/java/com/example/motorcycleantitheftsensor/sensor/audio/AudioThreatPipeline.kt
rg -n "allowSmsFallback:|runBlocking|recorder\.read\(target, 0, target\.size\)" app/src/main app/src/test
```

Required result:

- self-test call is explicitly `NON_BLOCKING`;
- active capture call is explicitly `BLOCKING`;
- no Android 3-argument recorder read remains;
- no deferred SMS gate or `runBlocking` remains in `ProtectionViewModelTest`;
- changes attributable to this plan are confined to Allowed Files;
- unrelated pre-existing dirty/untracked files are still present and unmodified by this plan.

---

### Task 7: Exact-APK Real-Device Acceptance

**Files:**
- Verify freshly built APK
- Create evidence file only after completing the matrix

**Interfaces:**
- Consumes: Task 6 APK and Android tests
- Produces: device proof required to close the work

- [ ] **Step 1: Identify one authorized device and the exact APK**

```powershell
adb devices -l
Get-FileHash -Algorithm SHA256 'app\build\outputs\apk\debug\app-debug.apk'
```

Expected: exactly one intended device in `device` state. Record serial, model, Android version and APK SHA-256. If device is absent/unauthorized, mark device acceptance `NOT RUN` and do not close the plan.

- [ ] **Step 2: Install and launch the exact APK**

```powershell
adb install -r 'app\build\outputs\apk\debug\app-debug.apk'
adb shell am force-stop com.example.motorcycleantitheftsensor
adb logcat -c
adb shell monkey -p com.example.motorcycleantitheftsensor -c android.intent.category.LAUNCHER 1
```

Expected: install and launch succeed with no immediate `AndroidRuntime` crash.

- [ ] **Step 3: Run focused instrumentation tests**

```powershell
.\gradlew.bat --no-daemon --max-workers=1 '-Dkotlin.compiler.execution.strategy=in-process' connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.example.motorcycleantitheftsensor.ui.ProtectionAppScreenTest'
```

Expected: all focused UI tests pass on the same device. Record test count and failures.

- [ ] **Step 4: Run ten normal self-tests**

For each iteration 1-10:

1. Confirm state is `DISARMED_ONLINE`.
2. Open Settings.
3. Start a stopwatch, press `Test Microphone (Disarmed only)`, and make ordinary room noise during part of the test.
4. Confirm only the microphone button shows loading; SMS and bot-token buttons must not show saving text/spinner.
5. Confirm completion within 8 seconds.
6. Confirm a persistent pass/fail row appears and no raw detail code is shown.
7. Confirm no Telegram, SMS, notification, Events entry, Snackbar or top popup was produced.

Required: 10/10 finish without a permanent spinner, frozen screen or app restart.

- [ ] **Step 5: Verify permission-denied bounded failure**

```powershell
adb shell pm revoke com.example.motorcycleantitheftsensor android.permission.RECORD_AUDIO
```

Relaunch, navigate to Settings, press Test Microphone if UI permits the action, and verify it fails safely within 8 seconds without raw error text or stuck loading. Then restore permission:

```powershell
adb shell pm grant com.example.motorcycleantitheftsensor android.permission.RECORD_AUDIO
adb shell am force-stop com.example.motorcycleantitheftsensor
adb shell monkey -p com.example.motorcycleantitheftsensor -c android.intent.category.LAUNCHER 1
```

If Android UI blocks the button when permission is absent, record that truthful blocked state as PASS and do not bypass the product UI to manufacture a result.

- [ ] **Step 6: Prove Armed capture was not regressed**

1. After restoring microphone permission, run one successful self-test.
2. Arm protection.
3. Confirm audio runtime leaves Off, proceeds through Starting/Calibrating, and reaches Listening or a truthful bounded Degraded/Failed state.
4. Leave it armed for at least 60 seconds while generating a few ordinary sounds.
5. Confirm the UI remains responsive and there is no rapid CPU-driven loop symptom, screen flashing or navigation reset.
6. Disarm and confirm runtime returns to Off.

This is the device proof that the Armed path remained blocking and functional after adding explicit modes.

- [ ] **Step 7: Inspect crash/ANR evidence**

```powershell
adb logcat -d -v threadtime AndroidRuntime:E ActivityManager:E AudioRecord:E '*:S'
adb shell dumpsys activity processes | Select-String -Pattern 'com.example.motorcycleantitheftsensor|ANR'
```

Expected: no new crash or ANR attributable to the matrix. AudioRecord errors during the revoked-permission test are acceptable only if the UI completes safely and the app does not crash.

- [ ] **Step 8: Write the evidence record**

Create `docs/superpowers/status/2026-08-15-microphone-self-test-final-evidence.md` with these exact sections:

```markdown
# Microphone Self-Test Final Evidence

## Source Baseline
## Files Changed by This Plan
## Focused Test Results
## Full Host Test Counts
## Build and Android-Test Compile
## APK SHA-256 and Device Identity
## Instrumentation Result
## Ten-Run Self-Test Matrix
## Permission-Denied Result
## Armed Audio Regression Result
## Crash and ANR Check
## Remaining Risks
## Final Verdict
```

Every command must include exit code/result. `Final Verdict` may be `COMPLETE` only when all host gates and all device steps pass. Otherwise use `HOST COMPLETE / DEVICE NOT ACCEPTED` or `FAILED` with the exact failed item.

---

## Final Acceptance Checklist

- [ ] `AudioRecorderBackend.read` requires an explicit `AudioReadMode`; there is no default
- [ ] Android backend maps both modes to the four-argument `AudioRecord.read`
- [ ] Self-test uses only `NON_BLOCKING`
- [ ] Armed capture uses only `BLOCKING`
- [ ] Self-test uses monotonic elapsed time and a coroutine safety timeout
- [ ] Self-test normal duration remains 5 seconds and device ceiling is 8 seconds
- [ ] Timeout returns `SELF_TEST_TIMEOUT`
- [ ] External cancellation propagates and is not converted to a failure result
- [ ] Recorder stop/release assertions pass on success, no-sample, timeout, start-failure and cancellation paths
- [ ] Direct `runSelfTest()` unit tests exist and pass
- [ ] Old `allowSmsFallback` deferred and `runBlocking` branch are removed
- [ ] SMS ownership test releases its latch and clears the ViewModelStore in `finally`
- [ ] `ProtectionViewModelTest` completes three forced reruns without hanging
- [ ] UI never displays raw self-test detail codes
- [ ] Timeout/unknown failure copy is persistent and safe
- [ ] No Telegram, SMS, event, notification, popup or Snackbar is triggered by self-test
- [ ] Full unit tests, APK build and Android-test compilation pass sequentially
- [ ] Focused instrumentation tests pass on the exact installed APK
- [ ] Ten real-device self-tests finish 10/10 within 8 seconds
- [ ] Permission-denied behavior is bounded and truthful
- [ ] Armed audio capture works for at least 60 seconds after self-test
- [ ] No crash, ANR, stuck spinner or Gradle worker remains
- [ ] Changes from this plan are confined to Allowed Files
- [ ] Unrelated dirty worktree content is preserved
- [ ] Evidence file verdict is `COMPLETE`
- [ ] No commit was created without owner authorization

## Stop Conditions

Agent must stop and report instead of guessing when:

- four-argument `AudioRecord.read` is unavailable despite the verified `minSdk=24`;
- changing the interface requires any production implementer outside the two known files;
- device test shows Armed audio no longer reaches a valid runtime state;
- self-test exceeds 8 seconds even once in the ten-run matrix;
- a new crash/ANR appears;
- fixing a failure would require Telegram, SMS production, GPS, fusion, classifier, persistence, navigation, dependency or Gradle changes;
- unrelated worktree files would need to be reset, moved or deleted.

Do not mark partial host success as completed work. The project may move to the next feature only after the evidence verdict is `COMPLETE`, or after the owner explicitly accepts a documented device-test exception.
