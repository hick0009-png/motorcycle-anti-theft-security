# Remediation Plan — 2026-08-23 (from full-project inspection)

Source: full-project review after power guard wiring (`86752d2`). Findings are ordered by
severity; every phase ends with a verification gate before moving on. RAM discipline
unchanged: one Gradle invocation at a time, `--no-daemon --max-workers=1`.

## Phase 0 — Protect the work (do today, ~15 min)

### 0.1 Fast-forward merge the 52-commit branch into the feature branch

Finding: all implementation work lives only on `codex/continuity-recovery-tdd`;
`feature/motorcycle-guard-protection` (the main checkout at `D:/security`) has zero
unique commits and contains no Power Guard files. Verified fast-forwardable
(merge-base = feature tip `a2f5b29`; 0 divergent commits).

```powershell
Set-Location D:\security
git status --short                       # must be empty (verified clean)
git checkout feature/motorcycle-guard-protection
git merge --ff-only codex/continuity-recovery-tdd
git log -3 --oneline                     # expect 32dcaed on top
git push -u origin feature/motorcycle-guard-protection   # remote backup
```

Gate: `git log -1` on the feature branch shows `32dcaed`; top-level checkout now
contains `PowerCompositeArbiter.kt` etc.; push succeeds.

Note: keep the worktree alive afterwards for slice 4 (rebase it onto the updated
feature branch or continue committing on its branch — either works since history is
linear).

### 0.2 Restore `AI_WORKFLOW.md`

Finding: `AGENTS.md` mandates "Read AI_WORKFLOW.md" as step 1 of every task, but the
file does not exist anywhere in the repo.

Fix (choose one, prefer A):
- **A. Recreate** `AI_WORKFLOW.md` at repo root capturing the current contract:
  required workflow (read checkpoints → inspect → smallest change → tests → verify →
  report), subagent delegation policy, boundaries (no architecture changes without
  approval, no weakened validation, no secrets), Cursor collaboration split, and the
  RAM/Gradle discipline (`--no-daemon --max-workers=1`, never parallel Gradle).
- **B. Amend** `AGENTS.md` to drop the reference (only if the workflow doc is
  intentionally retired).

Commit: `docs: restore AI_WORKFLOW.md referenced by AGENTS.md`.

## Phase 1 — Kill the drift risks (short, host-test only)

### 1.1 Single source of truth for diagnostic strings

Finding: `entry_*` / `power_*` constants are duplicated across three files
(AndroidProtectionRuntime.kt file-private, IncidentEngine companion,
IncidentMessageFormatter companion). They are coupled by string comparison; a rename
in one place silently degrades formatter output to generic copy.

Fix:
- Create `app/src/main/java/com/example/motorcycleantitheftsensor/protection/ProtectionDiagnostics.kt`
  holding `internal const val ENTRY_DIAGNOSTIC_PREFIX … POWER_RECOVERED` (same values).
- Replace the three private copies with imports. No behavior change intended.
- Add one host test (e.g. `ProtectionDiagnosticsTest`) asserting the formatter's
  `when` branches still match the shared constants by rendering each diagnostic
  through `IncidentMessageFormatter.powerMessage` (guards against future copy-paste
  divergence).

Gate: focused formatter + engine tests GREEN; full gate unchanged count ± small delta.

### 1.2 Single charging-connectivity mapping

Finding: CHARGING/FULL→connected mapping exists twice (runtime extension
`ChargingState.chargingConnected()` and ViewModel `powerSummaryRows`).

Fix: promote the mapping to an internal extension in `ProtectionModels.kt`
(next to the enum) and use it from both call sites.

Gate: `*ProtectionViewModelTest` + coordinator tests GREEN.

Commit Phase 1 as one refactor commit: `refactor: single-source power/entry diagnostics and charging mapping`.

## Phase 2 — Design consolidation (fold into slice 4 planning)

### 2.1 One owner for POWER incident opening

Finding: the legacy charger precursor path (charger disconnect + audio correlation)
can still open a POWER incident while the new armed-session arbiter also opens one;
`IncidentEngine` keeps a single active incident, so a collision silently replaces the
open incident.

Proposal (needs developer approval — architecture boundary):
- While an armed POWER session is active, route legacy `charger_disconnected`
  observations through the arbiter pipeline only (suppress the precursor/auto-open
  path), making the arbiter the sole opener of POWER incidents.
- Keep the legacy path for non-POWER profiles where no arbiter exists.

### 2.2 Incident slot model (optional, spec-level)

Single-slot `activeIncident` means different incident types replace each other. If
device acceptance shows real collisions (e.g. vibration tamper during a power
episode), consider type-scoped slots. This changes delivery semantics — decide with
the spec owner before implementing; do not improvise.

## Phase 3 — Housekeeping (low priority)

- Add `.gitignore` patterns for scratch artifacts (`*.log` at app root,
  `plans/task*-*.png`) so `git status` noise shrinks; keep acceptance screenshots
  untracked-but-visible by convention (`plans/powertask*-*.png`).
- Delete stale scratch logs older than the current slice after each checkpoint.
- Trivial: remove the local `assertFalse` wrapper in `PowerIncidentFormatterTest`.
- Optional (approval needed): track `yamnet.tflite` (~4 MB binary) via Git LFS to
  stop repo bloat; requires LFS availability on the remote — do not improvise.

## Acceptance-session runbook (unblocks Task 7.3 for both guards)

One owner session covers Entry + Power:
1. Verify installed build (`dumpsys package … lastUpdateTime`) matches the wired APK.
2. Light-sensor sanity first: open Power commissioning, cover/uncover the phone —
   confirm live lux updates. **If lux does not stream continuously**, stop and add the
   periodic re-evaluation tick follow-up before continuing (documented in the power
   checkpoint risks).
3. Run the Entry acceptance script (spec section 8 Entry bullet).
4. Run the Power acceptance script (spec section 8 Power bullet): commissioning lamp
   off/on → immediate Arm within 10 min → later challenge → skipped-challenge degraded
   → dimming → shifted hood → stale samples → charger-only loss → witness-only loss →
   dual loss → crossover → transient → partial/full recovery.
5. Screenshots under `plans/powertask*-*.png` (untracked), then final checkpoint doc.

## Order of execution

| Step | When | Effort |
|---|---|---|
| 0.1 ff-merge + push | immediately | 15 min |
| 0.2 AI_WORKFLOW.md | immediately | 20 min |
| Owner acceptance runbook | next owner session | 1–2 h |
| 1.1 + 1.2 refactor | after acceptance (or parallel) | 1–2 h |
| 2.x consolidation | inside slice 4 planning | spec discussion |
| 3 housekeeping | anytime | 30 min |
