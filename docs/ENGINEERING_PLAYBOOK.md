# Engineering Playbook

## Project Goal
Develop production-quality Android Motorcycle Anti-Theft Sensor with Security-First approach.

## AI Roles
- **Antigravity**: Lead Architect
- **Cursor**: Implementation Engineer

## Engineering Workflow
Follow a 4-step lifecycle:
1. **Analyze**: Understand the problem, requirements, and constraints.
2. **Implement**: Write code conforming to Android and Security standards.
3. **Review**: Check against Code Review Checklist.
4. **Verify**: Test functionality and ensure no regressions.

## Cursor Implementation Task Format
Use the template at `work/templates/CURSOR_IMPLEMENTATION_TASK.md` for handing off tasks to Cursor.

## Engineering Principles
- **Preserve Architecture**: Do not diverge from `docs/ARCHITECTURE.md`.
- **Solve Root Cause**: Avoid band-aid fixes; find and fix the underlying issue.
- **Smallest Safe Change**: Make minimal necessary changes to achieve the objective.
- **Reuse**: Reuse existing components and patterns instead of creating new ones.
- **Use SDK Before Dependencies**: Prefer native Android SDK solutions over third-party libraries.

## Code Review Checklist
Before marking a task complete, verify:
- [ ] **Architecture**: Aligns with project architecture and MVVM patterns.
- [ ] **Regression**: No existing functionality is broken.
- [ ] **Thread Safety**: Coroutines and background tasks are properly synchronized.
- [ ] **Resource Release**: Memory, listeners, and sensors are properly released.
- [ ] **Encryption Integrity**: No plaintext sensitive data; crypto implementations are intact.
- [ ] **Sensor Accuracy**: Sensor logic handles edge cases and noise.

## Android Standards
- **Language**: Kotlin
- **UI**: Jetpack Compose
- **Concurrency**: Coroutines and StateFlow
- **Architecture**: MVVM
- **Data Security**: `androidx.security:security-crypto`

## Security System Principles
- **Preserve Keystore Integrity**: Use Android Keystore for all sensitive keys.
- **EncryptedPrefs**: All persistent preferences must be encrypted.
- **Certificate Pinning**: Ensure TLS pinning is active for external APIs.
- **TOTP**: Use time-based one-time passwords for authentication.
- **Kiosk Mode**: Maintain anti-tamper kiosk mode requirements.

## Definition of Done
A task is considered done when:
- Root cause is addressed.
- Architecture remains consistent.
- No duplicate logic is introduced.
- Changes are minimal and focused.
- Build passes (`gradlew assembleDebug`).
- Security audit passes without warnings.

## Source of Truth
Consult these documents for authoritative guidance:
- `docs/ARCHITECTURE.md`
- `docs/ENGINEERING_PLAYBOOK.md`
- `.agents/AGENTS.md`
- `docs/ADR/`

## Task Handoff Protocol
Use `work/templates/CURSOR_IMPLEMENTATION_TASK.md` to communicate implementation requirements to Cursor.
