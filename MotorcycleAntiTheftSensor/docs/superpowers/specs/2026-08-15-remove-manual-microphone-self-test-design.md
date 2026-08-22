# Remove Manual Microphone Self-Test Design

**Date:** 2026-08-15

## Decision

Remove the manual microphone self-test feature completely. The product will no longer expose a Test Microphone button, store a self-test result, or provide a separate self-test API through UI, coordinator, runtime, detector, or audio pipeline layers.

## Reason

The manual test duplicates information already provided by the armed audio runtime and introduces a second recorder lifecycle, timeout behavior, operation state, UI state, and test surface. For this product, microphone usefulness is established by the real protection path while Armed: runtime state, recent samples, changing dBFS level, classifier readiness, and truthful degraded/failed health.

## Keep

- Microphone permission and hardware health reporting.
- Automatic microphone startup when protection is Armed.
- `AudioRuntimeState` and `AudioTelemetry`.
- Audio level, baseline, last-sample age, inference latency, dropped frames, restarts, and candidate diagnostics.
- YAMNet classification, calibration, audio gate, candidate buffer, Sensor Fusion, incidents, Telegram, SMS, GPS, and protection state behavior.
- Blocking `AudioRecord` reads for the active Armed capture loop.

## Remove

- Settings Test Microphone button, loading copy, and last self-test result/time.
- `SettingsOperation.MICROPHONE_SELF_TEST`.
- `MicrophoneSelfTestUiResult` and every `selfTestResult` field/projection.
- `ProtectionViewModel.testMicrophone()` and `runMicrophoneTest()`.
- `ProtectionAppActions.testMicrophone` and Navigation binding.
- `ProtectionCoordinator.testMicrophone()`.
- `ProtectionRuntime.testMicrophone()` and Android runtime/detector forwarding methods.
- `AudioPeakDetector.testMicrophone()`.
- `AudioThreatPipeline.runSelfTest()` and `MicrophoneSelfTestResult`.
- The read-mode enum introduced only to distinguish self-test from Armed capture. The remaining recorder API will have one `read(target)` method whose Android implementation explicitly calls the four-argument platform API with `AudioRecord.READ_BLOCKING`.
- Tests, fakes, imports, timeout branches, raw-detail output, and plan evidence used only by manual self-test.

## UI Behavior After Removal

Settings continues to show automatic audio diagnostics. When Disarmed, runtime truthfully shows Off. When Armed, it may show Starting, Calibrating, Listening, Classifying, Degraded, or Failed. There is no manual microphone action and no manual-test result card.

## Error and Health Behavior

Recorder initialization/read failures continue through the existing active-capture recovery, telemetry, and health-degradation path. No replacement popup, Snackbar, Telegram message, SMS, or new manual retry button is added.

## Non-Goals

- Do not fix the transient startup `OFFLINE` flash in this change; it requires a separate initialization-state design.
- Do not change audio model thresholds, labels, calibration, fusion rules, permissions, notification behavior, or protection commands.
- Do not add dependencies or restructure unrelated files.

## Acceptance

- No production symbol or visible copy for manual microphone self-test remains.
- Settings retains automatic microphone/audio diagnostics.
- Disarmed state does not own an AudioRecord.
- Armed capture still requests `AudioRecord.READ_BLOCKING` through the four-argument API.
- Existing audio classification and Sensor Fusion tests remain green.
- Fresh APK installs and launches; Settings has no manual test; Armed audio reaches a truthful runtime state and receives recent samples on the authorized device.
- No unrelated dirty-worktree content is reset, deleted, moved, staged, or committed.
