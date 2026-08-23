# AI Workflow

This document is the workflow contract referenced by `AGENTS.md`. It defines how the
autonomous engineering agent works in this repository.

## Role

The agent is the autonomous engineering agent for this repository. Primary
responsibilities: multi-file implementation, repository-wide investigation,
refactoring, writing and updating tests, running tests and verification, debugging
failures, code review, and repetitive repository-wide changes.

## Required Workflow (every non-trivial task)

1. Read the latest checkpoint under `plans/` (e.g. `plans/checkpoint-*.md`) to resume
   from the recorded state before assuming anything.
2. Inspect the repository before making assumptions (files, git state, tests).
3. Identify the smallest relevant set of files.
4. Preserve existing architecture and conventions.
5. Implement the smallest correct change.
6. Add or update appropriate tests (host tests preferred; TDD red→green when practical).
7. Run relevant verification.
8. Fix failures caused by your changes.
9. Report: what changed, files changed, tests executed, verification result, remaining
   risks or unresolved issues.

## Build & Test Discipline (RAM-constrained machine)

- Exactly **one Gradle invocation at a time**; never parallel Gradle.
- Always: `--no-daemon --max-workers=1` with the repository's `-Xmx1536m` settings.
- Environment: `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`,
  `ANDROID_HOME=C:\Users\ASUS\AppData\Local\Android\Sdk`.
- Full host gate before checkpoints: `testDebugUnitTest` (expect ≥750 tests, zero
  failures) plus `compileDebugAndroidTestKotlin`.
- After every file edit, verify the edit landed on disk (`findstr`) before building —
  an editing tool in this environment has exhibited silent partial application.

## Worktrees

- Active development happens in `D:\security\.worktrees\<name>` worktrees; the main
  checkout at `D:\security` tracks the integration branch
  (`feature/motorcycle-guard-protection`).
- Merge worktree branches back with `git merge --ff-only` after each completed slice;
  keep history linear. There is currently no git remote — pushing is impossible until
  one is configured (owner action).

## Automatic Subagent Delegation

For every non-trivial task, evaluate whether the work contains independent subtasks.
When two or more independent subtasks can run concurrently: delegate automatically,
prefer parallel execution when files do not overlap, keep final integration/testing
under the parent agent, and do not create subagents for trivial or strictly
sequential work.

## Boundaries

Do NOT:
- Make major architectural decisions without developer approval.
- Introduce major dependencies without approval.
- Modify unrelated code.
- Revert existing developer changes.
- Disable tests to make a task pass.
- Weaken type checking, validation, authentication, or authorization.
- Expose or commit secrets.
- Claim verification was performed when it was not.

## Cursor Collaboration

Cursor is the interactive development assistant. Do not duplicate work already
assigned to Cursor. The agent accepts tasks that can be completed autonomously; tiny
interactive edits may be better suited to Cursor.

## Checkpointing

At the end of each working session, write/update a checkpoint under `plans/` with:
resume point (worktree, branch, head), commits table, verification evidence, decisions,
risks/notes, and exact resume commands. Scratch logs and acceptance screenshots stay
untracked and are never committed.
