# Task 9c — complete purge, re-signup isolation and integrated lifecycle acceptance

## Status, dependency and authority

**Planned; implementation is not authorized.** Depends on accepted 9a and bounded 9b. Read `docs/task-9-contract.md` for common authority, baseline, roles, release gating, verification and safety rules. This slice closes the physical-purge and all-slice integration gates. It does not authorize deployment, live OAuth interaction, Android changes, backup scheduling or disaster restore.

The ordinary delete capability remains fail-closed until a verified purge implementation and its documented release prerequisites are ready. Do not enable it merely because the acceptance transaction and permanent receipt work. New secret/configuration values, schemas and operational defaults require prior approval.

## Fixed purge behavior

Acceptance records one original deadline: `purgeBy = deletedAt + 86,400 seconds`. Complete removal of account-bearing live data must finish by that deadline. Access stops at acceptance, not when the purger eventually runs. Retries, restarts, skip-locked work and temporary failures cannot move the deadline or turn it into an eventual-cleanup promise.

Implement a production callable purge service, durable progress and actual scheduled invocation. Use finite account/row work budgets and transactions, prioritizing pending deadlines and making measurable progress across invocations. Large accounts and large child collections must not be hidden behind new product caps. Partial purge commits are acceptable only while the account remains irreversibly disabled and progress is durable; do not mark completion or drop the required anchor before every account-bearing store has been checked.

Persist intent at deletion acceptance, not only in process memory. Concurrent workers must not double-complete, skip required phases, regress progress or reopen access. Inspect actual locks and foreign-key order. Unexpected missing required state, a no-op delete that falsely reports progress, or a transient fault must not be presented as successful purge. Commit completion only after an explicit completeness check. Preserve the minimal permanent receipt without domain data.

## Required data inventory and deletion order

The Worker must enumerate all stores at the authorized slice baseline, not rely only on this current list. New account-bearing tables introduced in 9a/9b also belong in the inventory.

| Store | Required removal for old account A |
|---|---|
| `workouts`, `workout_exercises`, `workout_sets` | All owned roots and descendants, including active, finished and tombstoned state. Delete children before restricted parents. |
| `routines`, `routine_exercises`, `routine_sets` | All saved/draft/tombstoned roots and descendants. |
| Owned `exercises`, `exercise_muscle_groups`, `exercise_equipment` | Owned custom roots and relationships only, after references are removed. Global catalogue data and notices remain untouched. |
| `processed_mutations`, `resource_mutations` | Accepted/rejected identity and original domain-bearing request/response data, even when log history has already expired. |
| `sync_changes`, `sync_change_batches`, `account_sync_state`, `sync_cursor_tokens` | Old account's log, state and token metadata. Changes precede restricted parent batches. |
| `sync_snapshot_records`, `sync_snapshots` | Every frozen copy regardless of normal TTL; remove snapshot storage before its restricted cursor references. |
| `auth_idempotency`, `auth_credentials`, `auth_sessions` | All grants, encrypted responses, current/consumed credential fingerprints and session data. Respect session FKs. |
| `google_identity_bindings` | Any remaining A-bound identity data. Acceptance should already detach A; never delete B's replacement binding by matching provider subject alone. |
| `root_id_reservations`, `child_id_reservations` | Old A ownership/revision/identity metadata after the account-lifetime exception ends. |
| `accounts` | Remove the ordinary old account record, or justify any strictly whitelisted minimal representation. Do not retain original account profile/creation data under the guise of the ledger. |
| New lifecycle jobs, proof/admission state or caches | Remove account-linked transient/sensitive data and terminal progress when no longer required. Retain only the permanent shared whitelist. |

V4 challenges are anonymous nonce/purpose records before successful verification; they are not automatically owned by A. Preserve their separate five-minute/consumption cleanup policy and do not delete other users' challenges indiscriminately. Account-linked proof copies introduced later must be inventoried and purged; none may hold raw tokens indefinitely.

V3 permits physical deletion of tombstoned children only after the owning account is disabled. Keep the disabled account anchor available until relevant trigger and FK checks finish. Do not disable constraints/triggers, rewrite applied migrations, or delete root/account anchors first merely to force cascading success. Any necessary schema adjustment is new, reviewed and forward-only.

All destructive work is scoped by **old account UUID**, including jobs that run after the same Google subject signs up again. Do not sweep the new account by provider identity, credential key, resource UUID alone or latest binding. Catalogue and unrelated accounts must remain byte/value intact as appropriate.

## Permanent recovery and new-account isolation

After purge, the narrow deletion ledger remains usable indefinitely for fresh account-status ownership proof and identical accepted-deletion receipt replay. Neither operation depends on surviving ordinary auth/session/challenge/account rows, produces a new grant, changes the original deadline or restores payloads. Historical digest versions must remain verifiable with retained external keys; unavailable required keys fail closed.

A distinct fresh sign-in may create empty B for the same Google subject, with a new internal account UUID. Verify this both before A's purge finishes and after it completes. A delayed purge of A cannot delete B's binding, sessions, roots, reservations or replay. No old A credential, original exchange retry, cursor, snapshot/list token or accepted-deletion replay can authorize or implicitly create B. In-flight pre-deletion signup attempts must not be silently reinterpreted as consent to create B after acceptance; establish and test the fresh-attempt boundary with the Coordinator.

A deliberately new B operation has B's namespace; this is not a server restore feature. Backend tests must distinguish credential/replay fencing from Android's responsibility not to reassign old pending work. Do not claim that backend tests implement Room cache clearing or remote device wiping.

Recheck all account-write and read/export/snapshot boundaries against purge. A request that began before disablement must not create a new durable account-bearing copy after purge is declared complete. A frozen read view alone may still see old authorization; explain the live fence and real FK/transaction effects. New reads reject immediately, and admitted exports stop later release when deletion is observed. Already-downloaded bytes are outside retroactive erasure.

## Deadline and release proof

Approve exact work budgets, scheduling, priority, retry behavior, concurrency and deadline-breach reporting before authoring. Use controllable service time for deterministic tests, but distinguish those tests from actual capacity and availability evidence. A bounded scheduler must drain the supported backlog and resume after interrupted work within the original deadline. Do not retain a payload-bearing "failed job" forever or call a missed deadline successful because ordinary access is already disabled.

The ordinary snapshot-lag exception is not applicable to account purge. If the 24-hour physical deadline cannot be demonstrated under the proposed supported operating assumptions, keep deletion release blocked and escalate. Document remaining deployment capacity, alerting and outage constraints; do not claim an unconditional production guarantee or silently loosen the shared policy. This slice's functional acceptance and the separate production-release readiness decision must be explicit.

## Required no-edit design

Return the complete data/FK/trigger inventory, finite purge phases, durable progress and completeness checks, actual lock graph, deadline-priority/work budget proposal, crash recovery, cancellation of outstanding account-bearing work and B-isolation filters. Explain interaction with auth cleanup, 90-day pruning, ordinary snapshot cleanup and key-version verification. Identify concrete migration/configuration needs and how the 9b readiness gate will remain fail-closed until verified. Do not author a migration or choose a production SLA by assumption.

## Fixed acceptance criteria

**AC9c.1 Complete physical data removal.** Through real HTTP, create A with rich live and tombstoned roots/children, relationships, active/finished workouts, saved/draft routines, accepted/rejected sync/resource replay, several credentials and frozen snapshots. Accept deletion through the real endpoint and invoke actual purge. Inventory every account-bearing table/copy and assert A's payloads, credentials, ordinary identity/state/reservation rows and terminal transient state are gone; only whitelisted permanent evidence remains. Preserve catalogue and a populated unrelated account.

**AC9c.2 Deadline, bounds and actual scheduling.** Controlled time and backlog fixtures verify before/equal/deadline behavior, finite per-invocation work, large-account progress, deadline priority, busy-account retry and no deadline extension. The actual Spring scheduled path invokes the production purger. Temporary failures recover before the recorded deadline in tested scenarios; an intentionally missed deadline is visibly treated as failure, not compliance. Document the conditions under which capacity/availability are verified and any remaining release blocker.

**AC9c.3 Crash, rollback and durable resume.** Inject test-owned failures after representative deletions and before phase/completion advancement. The failed transaction rolls back, earlier valid phases may remain committed, and retry resumes without gaps or false completion. Stop the service process that accepted or advanced purge, then restart against the same isolated database and resume from durable intent/progress. Original `deletedAt`, `purgeBy`, digest versions and receipt remain unchanged. Only test-created processes and faults are stopped/removed.

**AC9c.4 Recovery after actual purge and rollover.** Real HTTP returns the original receipt for identical accepted-deletion retries after purge, proof/session expiry and process restart. Fresh status can confirm old A after more than 90 days and after B signs up, using only minimal evidence. Changed identity conflicts; new deletion key against owned deleted A has the specified invalid state. Historical verification keys survive tested rollover; missing required keys fail closed. No path exposes credentials/domain data or implicitly signs in.

**AC9c.5 New-account and delayed-work isolation.** Fresh sign-in creates empty B with a new UUID before and after A purge. Run delayed A purge/proof/retry/read activity while B has its own rich state and sessions; B remains intact. Old grants, refreshed tokens, mutation/resource retries, cursors and read tokens stay fenced. Coordinate concurrent signup and deletion using the real provider/account locks, including an old in-flight signup attempt. No automatic transfer of A's data or namespace.

**AC9c.6 Integrated races and no late copies.** Deterministic actual DB/HTTP gates cover purge/acceptance versus direct and sync writers, refresh/replay, snapshot capture/continuation, export before/during output, history/snapshot/auth cleanup and another purger. Valid serialized outcomes contain no post-disablement write or new durable copy after completion, no hybrid successful export/snapshot and no resurrected grant. Respect implicit FK locks and avoid artificial circular waits. Do not let a representative green race substitute for source review of participation and completeness.

**AC9c.7 Minimal evidence, structure and operational limits.** Coordinator reviews every new/changed production boundary, finite SQL work, checked progress, account filters, crypto key-version handling and fixed sanitized failure reporting. Permanent evidence contains only the whitelist. No controller SQL, broad legacy refactor, request-body logging, mode-heavy auth coordinator or bypass of database protections. Privacy-safe system-wide logs, backup replication and actual disaster restore remain explicit Task 10 work, not accepted by one scheduler-log test.

**AC9c.8 Frozen independent Task 9 acceptance.** Worker self-check; independent dedicated 9a/9b/9c and all accepted auth/API/sync regressions; full suite; Coordinator structural review, inventory/deadline/coverage review and real HTTP/PostgreSQL spots on identical final frozen inputs. Preserve commands, fresh XML counts/exits, manifests and copied reports. Re-run final targets after any changed test or production input. Close all blocking findings and distinguish functional acceptance from deployment release readiness. No complete-v1, legal-compliance, Android or backup/restore claim follows.

## Completion handoff

Record all three slice decisions, final Git baseline-relative scope, forward migrations, exact commands/evidence, data inventory, external key assumptions, release-gate state and any remaining operational blockers. Update the Coordinator handoff with actual authority and actor state. Do not deploy, apply migrations to the local database, commit or start Task 10 without explicit user approval.
