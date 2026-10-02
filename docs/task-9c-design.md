# Task 9c — coordinator-owned purge design

## Authority and ownership

This document is the Coordinator's design. The Worker owns facts only
(`docs/task-9c-worker.md`) and makes no architecture decision. The Worker
reported green on the facts survey with 12 open questions; every question is
decided below. Implementation authoring is not released by this document. A
separate explicit release follows QA baseline review and user-visible approval
of this design.

Fixed constraints: `purgeBy = deletedAt + 86,400 seconds`, immutable.
V1–V8 immutable. Ordinary deletion stays fail-closed (`503`) until the
readiness conditions in section 11 are verified. Test-only replacements never
flip production.

## 1. Architecture

Three new production pieces, all inside the existing backend module:

- `AccountPurgeRepository` — all purge SQL. Statements are finite,
  account-UUID-scoped, row-count-checked deletes plus claim/complete
  transitions on the intent row. No business policy inside.
- `AccountPurgeService` — owns phase order, transaction boundaries, progress
  persistence, the explicit completeness check, and sanitized failure mapping.
  One public entry: `purgeNextBatch(...)` for the scheduler and one
  `purgeAccount(UUID)` callable used by tests through the real Spring path.
- `AccountPurgeScheduler` — `@Scheduled` fixed-delay loop. Claims due
  intents ordered by `purge_by`, invokes the service per account, never
  extends a deadline, never deletes by provider subject.

Existing pieces reused unchanged: `PurgeReadiness` interface,
`DeletionLedgerCrypto`/`DeletionLedgerKeys`, `DeletionSubjectFence`,
writer/fence locks, export `checkLive` gates, pruning-candidacy filters.

## 2. Interfaces

- `PurgeReadiness.isPurgeCapable()` — unchanged signature. Production bean
  stays `AlwaysUnavailablePurgeReadiness` (false) until section 11 flips it
  to a config-backed bean. No request parameter may bypass it.
- `AccountPurgeService.purgeAccount(oldAccountId)` — requires a pending or
  in-progress intent row; throws sanitized transient vs definitive faults;
  commits per phase; marks terminal state only after the section 3
  completeness check passes in the same transaction.
- `AccountPurgeScheduler.sweep()` — claims at most `account-budget`
  intents with `FOR UPDATE SKIP LOCKED` ordered by `purge_by`, calls the
  service once per claimed account, catches per-account faults without
  aborting the sweep.

## 3. Completion definition and whitelist (Q1)

Purge of old account A is complete only when every account-bearing row for
A is gone from all stores in section 4 AND the explicit verification SELECTs
return zero in the same transaction that marks completion.

Permanent whitelist, retained indefinitely:

- `account_deletion_ledger` row for A (eight columns, no payload).
- `account_purge_intents` row for A in terminal `complete` state with
  `completed_at` (no payload, sanitized `last_error` cleared on success).

Nothing else remains: no account, binding, session, credential, replay,
sync, snapshot, reservation, domain or exercise row for A, and no transient
purge state beyond the terminal intent row.

## 4. FK-safe delete order (Q2)

Delete children before restricted parents. Keep the disabled `accounts`
anchor until all tombstoned-child deletes are done, because
`prevent_tombstoned_child_delete` requires `accounts.deleted_at` set.
Explicit deletes with row-count checks; never rely on `CASCADE` to satisfy a
phase (cascade edges are verified as empty afterward, not used as the
mechanism). Order per account:

1. `workout_sets`, `routine_sets`.
2. `workout_exercises`, `routine_exercises`.
3. `workouts`, `routines` (roots).
4. `exercise_muscle_groups`, `exercise_equipment` for owned exercises, then
   owned `exercises` rows (global catalogue rows never touched).
5. `child_id_reservations`, `root_id_reservations` for A.
6. `sync_changes`, then `sync_change_batches`, then `account_sync_state`.
7. `sync_snapshot_records` (explicit), then `sync_snapshots` headers, then
   `sync_cursor_tokens` (headers `RESTRICT` on tokens, so headers first).
8. `processed_mutations`, `resource_mutations` for A.
9. `auth_idempotency`, then `auth_credentials`, then `auth_sessions` for A.
10. `google_identity_bindings` for A (acceptance detaches; this is a
    verify-absent plus belt-and-braces scoped delete).
11. `accounts` row for A (anchor deleted last among live stores).
12. Verification SELECTs across all above stores; only then mark the intent
    `complete` with `completed_at`.

`auth_challenges` rows are never deleted by purge (Q9): they carry no
account column and have their own five-minute/consumption cleanup.

## 5. Durable progress (Q3)

New forward-only migration V9 extends `account_purge_intents` (Q12):

- `state` check widens from `('pending')` to
  `('pending','in_progress','complete')`. No `failed` state: a transient
  fault rolls back the current phase and returns the intent to `pending`
  with `attempts + 1`.
- New columns: `phase text NOT NULL DEFAULT 'pending'`,
  `attempts integer NOT NULL DEFAULT 0`,
  `updated_at timestamptz NOT NULL DEFAULT now()`,
  `completed_at timestamptz NULL`,
  `last_error text NULL` (short sanitized code only, never payload/SQL).
- New index `(state, purge_by)` for the scheduler claim.

Crash rule: committed phases stay committed; the failed phase rolls back;
retry resumes at the recorded `phase`. Original `deleted_at`, `purge_by`,
digest versions and receipt never change.

## 6. Lock order and concurrency (Q4)

Per account, one transaction per phase:

1. Lock `accounts WHERE id = :a FOR UPDATE` (same account-first order as
   writers; skips nothing).
2. Lock the intent row `FOR UPDATE`; transition
   `pending -> in_progress` once, update `phase` per phase.
3. Bounded deletes for the current phase only.

Across workers, only intent claiming uses `SKIP LOCKED`, ordered by
`purge_by`. No `SKIP LOCKED` on account or domain rows inside a purge.
The purger never takes the provider-subject advisory lock and never issues
a statement scoped by subject, credential key, resource UUID or latest
binding — old-account UUID binds only (Q8). Double completion is
impossible: completion requires holding the intent lock with all phases
recorded plus the section 3 verification in the same transaction.

## 7. Deadline handling (Q5)

`purge_by` is written once at acceptance and never moved by retries,
restarts, budgets or failures. The scheduler prioritizes earliest
`purge_by` first and gives overdue intents head-of-line on every sweep.
A completion with `completed_at > purge_by` still finishes physical removal
but is recorded and surfaced as a breach (sanitized log + health signal),
never as compliance. Controlled-time fixtures must prove before/equal/
deadline behavior and the breach path; an intentionally missed deadline in
tests is failure evidence. Production capacity and availability under the
documented operating assumptions must be evidenced before release; any gap
stays an explicit release blocker, not a silent SLA.

## 8. Work budgets (Q6)

Defaults (tunable by config, validated by QA fixtures):

- `account.deletion.purge.account-budget: 20` intents per sweep.
- `account.deletion.purge.row-budget: 500` rows per table per account per
  transaction, mirroring the existing 500-row auth victims.
- One account per `REQUIRES_NEW` transaction chain; a sweep never holds one
  transaction across accounts.

Large accounts make measurable progress across invocations without new
product caps. QA must prove progress resumption and deadline compliance
under backlog fixtures with controlled time.

## 9. Outstanding-work cancellation (Q7)

- Export streaming: existing per-segment `checkLive` gates already stop
  release on observed disablement; QA races must show no new durable copy
  after completion and no hybrid successful export.
- Cursor/snapshot fences: `FOR SHARE` pre-insert checks plus
  `DurableCopyHooks` stay; post-completion verification proves no new token,
  header or record for A.
- Refresh/replay: 9b revocation stands; purger deletes credentials and
  replay rows, and races must show denied refresh/replay with no new grant.
- Pruning candidacy already filters `deleted_at IS NULL`, so pruning never
  competes for A's rows; snapshot-expiry cleanup only touches expired
  headers, while purge deletes A's snapshots regardless of expiry.
- History TTL never extends `purge_by`: purge reads the intent deadline,
  not retention clocks.

## 10. Key versions (Q10)

Every ledger-key version referenced by any stored ledger row must stay
configured; `keyForVersion` absence keeps failing closed to safe 503 for
receipt/status paths. Rollover procedure: add the new version, keep all
referenced versions, verify with a query over stored
`subject_digest_version`/`request_digest_version`, and remove an old
version only after no row references it. Tests cover verification with
historical keys and the missing-key 503.

## 11. Readiness gate (Q11)

Ordinary acceptance flips from always-false to available only when all of
the following hold on identical frozen inputs:

1. The section 1–10 implementation exists behind review with no broad
   refactor, no controller SQL, no request-body logging.
2. All AC9c gates pass: dedicated 9a/9b/9c suites, all accepted
   auth/API/sync regressions, full suite, Coordinator structural review and
   real HTTP/PostgreSQL spots.
3. Capacity/availability evidence under documented operating assumptions
   plus breach alerting; otherwise deletion release stays blocked.
4. The production bean becomes config-backed
   (`account.deletion.purge.enabled`, default false) with scheduler health;
   missing config fails closed. Test-classpath replacements keep working in
   isolation without touching the production default.

## 12. Migration and configuration (Q12)

- `V9__purge_progress.sql`: forward-only; widens the intent state check,
  adds `phase`, `attempts`, `updated_at`, `completed_at`, `last_error`;
  adds the `(state, purge_by)` claim index. No applied migration is edited.
- `application.yaml` additions under `account.deletion.purge`: `enabled`
  (default false), `fixed-delay-ms` (default 60000),
  `initial-delay-ms` (default 60000), `account-budget` (default 20),
  `row-budget` (default 500). No secret values; no production SLA is
  chosen by assumption.
- No new dependency. No controller, auth-route or shared-contract change.

## 13. Responsibilities and acceptance reading

- Coordinator: this design, approval gates, structural review, spots,
  acceptance. No production/test authorship.
- Worker (next assignments): local implementation of sections 1–12 exactly
  as specified; open questions come back, never decided locally.
- QA (after baseline): dedicated 9c suites per AC9c.1–7 on the real
  scheduler path with controlled time, crash injection, actual process
  restart, B-state fixtures and race gates; then integrated/full regression
  per AC9c.8.
- AC9c.8 frozen acceptance reuses the section 11 gate list; functional
  acceptance and deployment release readiness stay explicitly separate.
