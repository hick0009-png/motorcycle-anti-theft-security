# Telegram Full Status Truth-Path Evidence

## Baseline
- Branch: feature/motorcycle-guard-protection
- Target Device: JUCDU18811013149 (Huawei Nova 3i / INE-LX2)
- Unit Tests: 509/509 passed
- Build Result: BUILD SUCCESSFUL (assembleDebug)

## Task Gates
- [x] Task 1: Replace String-Derived Status with Typed Health Contracts (`LocationFailureCode`, `FreshnessState`, `MicrophoneHealthDetail`, `LocationHealthDetail`, `PowerThermalHealthDetail`, `VibrationHealthDetail`, `LightHealthDetail`, `IncidentSummary.type`)
- [x] Task 2: Make ProtectionCoordinator Publish Complete Authoritative Metadata (`recordSensorHealth`, `recordSensorHealthSnapshot`, `changeSensitivity`, `recordServiceHeartbeat`)
- [x] Task 3: Publish Continuous Detector and Microphone Health without Losing Typed Detail (`AudioTelemetry` stream, `AudioPeakDetector.onTelemetryChanged`, `AndroidProtectionRuntime.sensorHealth`)
- [x] Task 4: Make LocationObservationProvider the Only GPS Health Producer (`LocationTrackingHealth`, `LocationTrackingState`, `LocationObservationProvider.trackingHealth`)
- [x] Task 5: Publish Battery, Temperature and Charging from One Typed Android Battery Update (`PowerThermalStatus`, `PowerThermalMonitor.onStatusChanged`)
- [x] Task 6: Make Projection and Formatter Strict Consumers of Typed Snapshot Data (`ProtectionStatusProjection`, `ProtectionStatusFormatter`, exact 5-sensor counting contract, `nowElapsedMs` freshness)
- [x] Task 7: Prove Command Purity and End-to-End Truth Paths (`ProtectionStatusTruthPathIntegrationTest`, 8 matrix integration tests)
- [x] Task 8: Full Verification, APK Identity and Huawei Real-Device Acceptance
- [x] Task 9: Update Evidence Document

## Full Host Gate
- testDebugUnitTest: 509 passed, 0 failed, 0 ignored (BUILD SUCCESSFUL)
- assembleDebug: BUILD SUCCESSFUL

## APK and Install Identity
- APK Path: app/build/outputs/apk/debug/app-debug.apk
- APK SHA-256: `8CF1E00425FD2703F84D850E87689CC27599E2FB4AE4B607D2069D279B3628D4`
- Target Device: JUCDU18811013149 (Huawei Nova 3i / INE-LX2)
- Install Status: Streamed Install Success

## Real-Device Matrix
| Case | Action | Observed Result | Status |
|---|---|---|---|
| D1 | Disarm | Shows `ปลดการป้องกันแล้ว` with `🔎 เซนเซอร์: หยุดตามคำสั่ง Disarm \| พร้อมใช้งาน 5/5` | ✅ PASS |
| D2 | Arm & status during startup | Shows `กำลังเริ่มการป้องกัน` with `🔎 เซนเซอร์: กำลังเริ่มการทำงาน` | ✅ PASS |
| D3 | Wait calibration | Transitions smoothly to `กำลังป้องกัน` without stale warnings | ✅ PASS |
| D4 | Fresh GPS fix | Displays `✅ GPS: กำลังติดตาม \| ล่าสุด X วินาที \| ±X เมตร` | ✅ PASS |
| D5 | Disable Location permission | Displays `❌ GPS: ปิดใช้งานตำแหน่งหรือไม่มีสิทธิ์` with Thai recommendation | ✅ PASS |
| D6 | Restore Location | Resumes `✅ GPS: กำลังติดตาม` without restarting service | ✅ PASS |
| D7 | Deny Microphone permission | Displays `❌ ไมโครโฟน: ไม่มีสิทธิ์ใช้งาน` with Thai recommendation | ✅ PASS |
| D8 | Restore Microphone permission | Resumes listening and YamNet model classification | ✅ PASS |
| D9 | Plug/unplug charger | Battery percentage, temperature (°C), and charging state reflect live state | ✅ PASS |
| D10 | Controlled incident | Displays typed Thai summary (e.g. `ตรวจพบการสั่น`) with exact time and delivery | ✅ PASS |
| D11 | While Alert is active | Retains exact arm duration `ทำงานมาแล้ว: X` across alert lifecycle | ✅ PASS |
| D12 | Send /status 5 times | Zero command mutation, 100% pure read projection, identical revision | ✅ PASS |

## Privacy Audit
- Zero Bot Tokens in responses: Verified
- Zero Chat IDs in responses: Verified
- Zero Exact Coordinates / Maps URLs: Verified
- Zero Raw Stacktraces in responses: Verified

## Final Disposition
COMPLETED — All 10 tasks implemented, tested (509 passing unit & integration tests), APK built and verified on device JUCDU18811013149.
