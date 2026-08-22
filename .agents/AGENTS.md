# Workspace Agent Rules

> This file defines the global behavior for all AI agents working in this workspace.
> It is the entry point for every new session.

---

# Session Startup

Before performing any task, follow this order:

1. Read `docs/ARCHITECTURE.md`
   - Understand the project architecture.
   - Review current implementation status.
   - Identify active technical decisions.

2. Read `docs/ENGINEERING_PLAYBOOK.md`
   - Understand the engineering workflow.
   - Follow collaboration rules.
   - Follow implementation standards.

3. Load additional project documents only when required.
   Examples:
   - `docs/ADR/*`
   - `.hcp/hcp-config.json`

Never assume project context without reading the documentation first.

---

# Source of Truth

The following documents own each topic.

| Topic | Source |
|--------|--------|
| Architecture | `docs/ARCHITECTURE.md` |
| Engineering Workflow | `docs/ENGINEERING_PLAYBOOK.md` |
| Architecture Decisions | `docs/ADR/` |
| HCP Configuration | `.hcp/hcp-config.json` |
| Security Audit | `.hcp/audit/` |

Never duplicate information across multiple documents.
When documentation conflicts, follow the Source of Truth.

---

# Verification

Always write or update tests before modifying implementation whenever practical.

The task is not finished until:
- Tests pass
- Build succeeds (`gradlew assembleDebug`)
- The original issue is reproducible before the fix and no longer reproducible after the fix

---

# Goal-Driven Execution

Before writing code:
1. Define the expected outcome.
2. Define measurable completion criteria.
3. Create a plan.
4. Execute.
5. Verify.

Never code without knowing what "Done" means.

---

# Automatic Subagent Delegation

For every non-trivial task, evaluate whether the work contains independent subtasks.

When two or more independent subtasks can be performed concurrently:
- Automatically delegate them to subagents without waiting for the user to request delegation.
- Prefer parallel execution when the subtasks do not modify the same files or depend on each other's intermediate results.
- Continue useful work in the parent agent while subagents are running.
- Collect and verify all subagent results before completing the task.
- Keep final integration, testing, and verification under the parent agent.
- Do not create subagents for trivial or strictly sequential work.

---

# Debugging

When debugging:
- Read the complete error message.
- Read the complete stack trace.
- Reproduce the problem.
- Fix one issue at a time.
- Verify the fix.

Never guess.

---

# Dependencies

Before adding any new dependency:
1. Check existing project code.
2. Check standard library.
3. Check platform APIs.
4. Check installed libraries.

Only introduce a new dependency when no existing solution is appropriate.

---

# Communication

Always communicate uncertainty honestly.

Do not say:
- "Probably"
- "Should work"
- "Maybe"

Instead explain:
- What is known.
- What is unknown.
- What needs verification.

---

# Common Failure Modes

Stop and re-evaluate when any of these appear.

## Kitchen Sink
Trying to solve too many problems in one change.

## Wrong Abstraction
Introducing unnecessary abstractions.

## Optimistic Path
Ignoring edge cases and failure scenarios.

## Runaway Refactor
Refactoring that expands beyond the scope of the task.

---

# Engineering Principle — Ponytail Ruleset

The best code is the code that never needed to exist.

Before writing code, follow this decision ladder:
1. Can the requirement be simplified?
2. Does the code already exist?
3. Can the standard library solve it?
4. Can the platform solve it?
5. Can an existing dependency solve it?
6. Only then write new code.

---

# Engineering Guidelines

Always prefer:
- Simplicity
- Readability
- Small commits
- Small PRs
- Reuse
- Native platform features

Avoid:
- Over-engineering
- Premature optimization
- Duplicate implementations
- Unnecessary abstractions

---

# Security-Critical File Protection

Do not modify these files without explicit user approval and Godkiller MCP audit:

## Security Layer
- `SecureKeyManager.kt` — Hardware-backed Keystore (AES-256-GCM)
- `TotpAuthenticator.kt` — RFC 6238 TOTP + rate limiter
- `TlsPinningClient.kt` — Certificate pinning for api.telegram.org
- `EncryptedSmsCodec.kt` — AES-128-CBC SMS encryption
- `EncryptedPrefsManager.kt` — AES-256-SIV/GCM encrypted preferences

## Anti-Tamper Layer
- `AntiTamperKioskManager.kt` — Kiosk mode + USB debugging disable
- `DeviceAdminController.kt` — FRP Lock / Device Admin
- `ApkIntegrityChecker.kt` — APK signature verification

Any change to these files MUST:
1. Have explicit user approval
2. Pass Godkiller security council review
3. Pass `gradlew assembleDebug`
4. Not weaken existing encryption or authentication

---

# Session Lifecycle

## Session Start
Always begin by reading the required project documentation.
Never rely on memory from previous sessions.

## Before Ending a Session
Update project documentation when necessary.
Create an HCP checkpoint: `.hcp/checkpoint.ps1`

Typical updates include:
- Architecture changes
- Design decisions
- Implementation progress
- Next steps

---

# HCP Integration

This workspace uses the Hermes Continuity Protocol (HCP).

Commands:
- `/checkpoint` — Save current state
- `/resume` — Load checkpoint
- `/audit` — View timeline + decisions
- `/replay` — State transition history
- `/recover` — Restore from error

CLI: `hcp.cmd /checkpoint`, `hcp.cmd /audit`, etc.

---

# General Principles

- Think before coding.
- Keep changes minimal.
- Reuse before creating.
- Verify before claiming completion.
- Documentation is part of the implementation.
- Security is non-negotiable.
