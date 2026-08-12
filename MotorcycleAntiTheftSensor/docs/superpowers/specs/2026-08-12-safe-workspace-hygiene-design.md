# Safe Workspace Hygiene and Pending-Work Reconciliation Design

## Goal

Make the `D:\security` workspace easier to operate without deleting, moving,
or overwriting any existing file.  Establish a current, evidence-based record
of unfinished work so that later implementation begins from the correct
checkpoint and branch state.

## Scope

This change is additive only:

1. Add a root `.gitignore` for reproducible local artifacts that must not be
   accidentally staged: IDE state, Gradle caches, build outputs, JVM crash and
   replay logs, and local tool caches.
2. Add a pending-work status document under
   `MotorcycleAntiTheftSensor/docs/superpowers/status/`.
3. Preserve every currently untracked file, including source, tests, `.hcp`
   checkpoint material, plans, and local evidence.
4. Reconcile only the status of QR enrollment, Telegram TLS pin refresh, and
   the uncommitted Telegram verification change.  Do not change production
   code as part of this hygiene work.

## Non-goals

- No deletion, relocation, reset, clean, or automatic staging of files.
- No commit, build, device operation, or dependency upgrade.
- No claim that QR enrollment or Telegram verification is complete without
  the required review and verification evidence.
- No modification to the existing app-level `.gitignore`.

## Ignore Rules

The root ignore file must use narrow, path-based rules.  It will ignore only
local/generated directories or diagnostic outputs:

- `.cursor/`, `.idea/`, `.serena/`, `.gradle-task2/`, and `.pytest_cache/`
- `MotorcycleAntiTheftSensor/.gradle/` and `MotorcycleAntiTheftSensor/**/build/`
- `MotorcycleAntiTheftSensor/hs_err_pid*.log` and
  `MotorcycleAntiTheftSensor/replay_pid*.log`

It must not ignore `.hcp/`, `.agents/`, `docs/`, `scripts/`, `work/`, app
source, tests, Gradle wrapper files, or configuration files.  Existing files
remain on disk; Git status merely stops presenting matching generated files as
new candidates for staging.

## Pending-Work Record

The status document will have one section per workstream and identify its
branch/commit context, confirmed evidence, next blocking action, and the
appropriate verification boundary.

- **Authenticator QR:** checkpoint `CKP-20260811-2357-YD5EKY` is in progress.
  The next action is a scoped review of `3ace729..3322c36`, followed by the
  automated and device gates only if that review is clean.
- **Telegram TLS pins:** `f9d7756` implements the refresh and `c0ca4ce`
  records the migration sequence.  Its final verification state must be read
  from its SDD progress/review artifacts before it is called complete.
- **Telegram verification refactor:** two tracked local edits are pending and
  internally inconsistent: the source exposes an `internal verifyBotToken`
  callback returning `TelegramBotVerificationResult`, while its source
  contract test expects methods not present in that source.  Preserve both
  edits and require a targeted code/test reconciliation before testing.

## Safety and Verification

Before and after the hygiene edit, record `git status --short`, the tracked
diff file list, and ignored/untracked summaries.  Verification succeeds only
when the new documentation is present, intended generated paths are ignored,
and source/docs/checkpoint paths remain visible and untouched.  No tests are
required because this work does not change application behavior.

## Rollback

If the ignore classification is wrong, remove only the newly added ignore rule
in a follow-up change; ignored files were never deleted and remain recoverable
immediately.
