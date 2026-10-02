# Task 9c Worker — facts-only survey (assignment 1, as amended)

## Standing rule (recorded verbatim in effect)

- The Coordinator owns the intended architecture, interfaces, invariants,
  responsibilities and acceptance interpretation.
- This Worker does not own design or architecture.
- This file contains observed facts only, plus explicit open questions where a
  design decision would be needed.
- No phases, budgets, scheduling, interfaces, migrations, code, config values,
  SLAs or recommendations are proposed here.
- Local implementation-neutral wording: this survey describes observed local
  repository behavior (tables, SQL text, code paths) without resolving shared
  normative interpretation.

## Assignment scope and method

- Assignment: facts-only survey. No-edit. No Maven, no tests, no builds, no
  package/compile.
- Inspection method: `read` and `rg`. `mcpls` preferred per repo guidance.
- Tool fact: `mcpls` server listed 20 tools and connected; one
  `mcpls_workspace_symbol_search` for `AccountDeletionService` returned
  `{"symbols":[]}`. No symbols were relied on. Inspection continued with
  read-only `read`/`rg` without changing tooling. No tooling change was made.
- Baseline observed (read-only `git status` / `git log`):
  - Backend HEAD `af6e1f5` (`sync work`); parent baseline `36fe88d`.
  - Working tree has staged-modified and untracked 9b files (ledger, deletion,
    status, V8, 9b tests/docs). This survey reads the working-tree state as
    found; it does not freeze or hash inputs.

## Observed migration files present

- `V1__catalogue.sql`, `V2__allow_custom_exercises.sql`,
  `V3__aggregate_persistence_foundation.sql`, `V4__authentication_sessions.sql`,
  `V5__sync_mutation_exact_replay.sql`, `V6__resource_idempotency.sql`,
  `V7__durable_sync_snapshots.sql`, `V8__account_deletion_ledger.sql`.
- `application.yaml` observed with `account.deletion.ledger-keys` bound from
  `${ACCOUNT_DELETION_LEDGER_KEYS:}` (empty default), plus existing
  `auth.cleanup.*`, `sync.history.cleanup.*`, `sync.snapshot.cleanup.*` keys.

## Observed account-bearing store inventory (facts only)

Legend: FK text is quoted as written in the migration files. Absence of an
explicit `ON DELETE` clause is recorded as observed; no behavior beyond the
text is asserted here.

### Accounts and catalogue

- `accounts(id PK, created_at, deleted_at)`.
- `catalogue_versions(version PK, manifest_sha256, published_at)`.
- `muscle_groups`, `equipment`: global tables, no account column observed.
- `exercises`: global rows have `owner_id IS NULL AND is_user_created=false`;
  owned custom rows have `owner_id REFERENCES accounts(id)`,
  `is_user_created=true`, `revision > 0`, `created_at/updated_at NOT NULL`.
  Observed constraint name `exercises_catalogue_or_custom`. Observed unique
  index `exercises_custom_id_owner ON exercises(id, owner_id)`.
- `exercise_muscle_groups(exercise_id REFERENCES exercises(id),
  muscle_group_id REFERENCES muscle_groups(id), type; PK
  (exercise_id, muscle_group_id, type))`.
- `exercise_equipment(exercise_id REFERENCES exercises(id), equipment_id
  REFERENCES equipment(id), num_items; PK (exercise_id, equipment_id))`.

### Owned domain roots and children

- `workouts(id PK, owner_id NOT NULL REFERENCES accounts(id), created_at,
  updated_at, revision CHECK >0, deleted_at, name, started_at, finished_at,
  status CHECK IN ('active','finished'), UNIQUE (id, owner_id))`.
- `routines(id PK, owner_id NOT NULL REFERENCES accounts(id), created_at,
  updated_at, revision CHECK >0, deleted_at, name, is_draft,
  UNIQUE (id, owner_id))`.
- `workout_exercises(id PK, workout_id REFERENCES workouts(id)
  ON DELETE RESTRICT, exercise_id REFERENCES exercises(id), position,
  notes, rest_duration_seconds; UNIQUE (workout_id, position),
  UNIQUE (id, workout_id))`.
- `workout_sets(id PK, workout_exercise_id REFERENCES workout_exercises(id)
  ON DELETE RESTRICT, position, repetitions, weight_kg, rest_seconds,
  is_completed; UNIQUE (workout_exercise_id, position))`.
- `routine_exercises(id PK, routine_id REFERENCES routines(id)
  ON DELETE RESTRICT, exercise_id REFERENCES exercises(id), position,
  rest_duration_seconds; UNIQUE (routine_id, position),
  UNIQUE (id, routine_id))`.
- `routine_sets(id PK, routine_exercise_id REFERENCES routine_exercises(id)
  ON DELETE RESTRICT, position, repetitions, weight_kg;
  UNIQUE (routine_exercise_id, position))`.

### Identity reservations

- `root_id_reservations(aggregate_type CHECK IN
  ('workout','routine','customExercise'), aggregate_id, owner_id REFERENCES
  accounts(id); PK (aggregate_type, aggregate_id))`. Observed comment:
  no child-row FK by intent.
- `child_id_reservations(family CHECK IN ('workout','routine'), child_id,
  owner_id REFERENCES accounts(id), root_id, role CHECK IN
  ('root','entry','set'), parent_id; PK (family, child_id))`. Observed
  comment: no child-row FK by intent.

### Sync state, batches, changes, replay

- `account_sync_state(account_id PK REFERENCES accounts(id),
  committed_cursor DEFAULT 0 CHECK >=0, pruned_through DEFAULT 0 CHECK >=0,
  CHECK (pruned_through <= committed_cursor))`.
- `sync_change_batches(account_id REFERENCES accounts(id), cursor CHECK >0,
  batch_id UNIQUE, committed_at DEFAULT clock_timestamp();
  PK (account_id, cursor))`.
- `sync_changes(account_id, cursor, position CHECK >=0, aggregate_type CHECK IN
  ('workout','routine','customExercise'), aggregate_id, operation CHECK IN
  ('upsert','delete'), revision CHECK >0, payload jsonb, expires_at;
  PK (account_id, cursor, position);
  FOREIGN KEY (account_id,cursor) REFERENCES
  sync_change_batches(account_id,cursor) ON DELETE RESTRICT;
  CHECK ((operation='upsert' AND payload IS NOT NULL) OR
  (operation='delete' AND payload IS NULL)))`.
- `processed_mutations(owner_id REFERENCES accounts(id), mutation_id,
  request_kind, request_hash bytea, outcome jsonb, created_at,
  request_bytes bytea; PK (owner_id, mutation_id))`. `request_bytes` added in
  V5; V5 comment states pre-existing rows never match fresh requests.
- `sync_cursor_tokens(token PK, account_id REFERENCES accounts(id),
  cursor CHECK >=0, created_at; UNIQUE (account_id, cursor))`. V7 adds unique
  index `sync_cursor_tokens_account_token ON sync_cursor_tokens(account_id,
  token)`.
- `resource_mutations(owner_id REFERENCES accounts(id), idempotency_key,
  request_method, request_route, request_body bytea, response_status CHECK
  200..599, response_body bytea, created_at; PK (owner_id,
  idempotency_key))`.

### Snapshots

- `sync_snapshots(account_id REFERENCES accounts(id) ON DELETE CASCADE,
  snapshot_id, baseline_cursor, created_at, expires_at, record_count CHECK
  >=0; PK (account_id, snapshot_id);
  FOREIGN KEY (account_id, baseline_cursor) REFERENCES
  sync_cursor_tokens(account_id, token) ON DELETE RESTRICT;
  CHECK (expires_at = created_at + interval '24 hours'))`.
- `sync_snapshot_records(account_id, snapshot_id, ordinal CHECK >=0,
  aggregate_type CHECK IN ('customExercise','routine','workout'),
  aggregate_id, payload jsonb NOT NULL; PK (account_id, snapshot_id,
  ordinal); UNIQUE (account_id, snapshot_id, aggregate_type, aggregate_id);
  FOREIGN KEY (account_id, snapshot_id) REFERENCES
  sync_snapshots(account_id, snapshot_id) ON DELETE CASCADE;
  plus a payload-shape CHECK constraint)`.

### Authentication and identity

- `google_identity_bindings(google_subject PK, account_id REFERENCES
  accounts(id), created_at)`. Index `google_identity_account(account_id)`.
  V4 file comment states deletion must remove this binding in the same
  acceptance transaction and that the ledger retains only keyed digest/receipt.
- `auth_challenges(id PK, nonce, purpose enum auth_challenge_purpose
  ('signIn','export','deleteAccount','accountStatus'), created_at,
  expires_at, consumed_at)`. No account column observed.
- `auth_sessions(id PK, account_id REFERENCES accounts(id), created_at,
  revoked_at)`. Index on `account_id`.
- `auth_credentials(fingerprint PK CHECK octet_length=32, session_id
  REFERENCES auth_sessions(id), kind CHECK IN ('access','refresh'),
  issued_at, expires_at, consumed_at)`. Indexes on `session_id`, `expiry`.
- `auth_idempotency(operation CHECK IN ('google','refresh'), request_key,
  request_digest CHECK 32 bytes, credential_fingerprint CHECK 32 bytes,
  session_id REFERENCES auth_sessions(id), encrypted_response,
  refresh_fingerprint CHECK 32 bytes, expires_at;
  PK (operation, request_key))`. Index on `expiry`.

### 9a lifecycle / export stores

- Observed 9a export path (`AccountExportService`, `LifecycleProofService`,
  `AccountExportController`, `AccountExportMapper`, `AccountExportHooks`,
  `NoOpAccountExportHooks`): no export-specific table observed. Export is
  described in code comments as storing no export state; challenge consumption
  uses `auth_challenges`.
- `auth_challenges` rows with purposes `export`, `deleteAccount`,
  `accountStatus` already exist in V4 enum; no purpose-enum migration observed
  in 9a/9b.

### 9b lifecycle / deletion stores

- `account_deletion_ledger(old_account_id PK, subject_digest_version smallint
  CHECK 1..32767, subject_digest bytea CHECK octet_length=32, deleted_at,
  purge_by, deletion_key uuid, request_digest_version smallint CHECK
  1..32767, request_digest bytea CHECK octet_length=32;
  CHECK (purge_by = deleted_at + interval '86400 seconds'))`.
  Index `account_deletion_ledger_subject_lookup
  (subject_digest_version, subject_digest, deleted_at DESC)`.
  V8 comment: no domain payload or credential stored; no globally unique index
  on `deletion_key` alone.
- `account_purge_intents(old_account_id PK REFERENCES
  account_deletion_ledger(old_account_id) ON DELETE RESTRICT, created_at,
  purge_by, state CHECK (state='pending');
  CHECK (purge_by = created_at + interval '86400 seconds'))`.
  Index `account_purge_intents_purge_by(purge_by)`.
  V8 comment: separate pending purge intent.
- No other 9b tables observed. Purge progress beyond the `pending` intent row
  was not observed as a table.

## Observed trigger behavior (facts only)

- `reserve_root_identity(kind)` (`workouts`, `routines`, owned `exercises`
  inserts): rejects tombstoned starts; inserts `root_id_reservations` and, for
  workout/routine, a `child_id_reservations` `root` row.
- `prevent_root_identity_rebinding()`: rejects `id`/`owner_id` changes.
- `prevent_tombstoned_root_mutation()`: on tombstoned roots, permits only
  `revision`/`updated_at` metadata bumps with strictly increasing revision;
  other column changes rejected.
- `reserve_child_identity(family, role)` (all four child tables,
  insert/update): rejects child UUID change; resolves owner/root/parent via
  parent joins; rejects missing or tombstoned root (`23503`); inserts
  reservation `ON CONFLICT DO NOTHING` then rejects on owner/root/role/parent
  mismatch (`23505`).
- `prevent_tombstoned_child_delete()`: before child delete, when the owning
  root is tombstoned, permits the delete only if the owning account row has
  `deleted_at` set; otherwise raises `23514`.
- `enforce_exercise_visibility()`: child insert/update of
  `exercise_id`/`workout_id`/`routine_id` rejects exercises not visible to the
  aggregate owner (missing row, or user-created row owned by another account).
- `protect_published_catalogue()`: after `catalogue_versions` contains
  `gymtracka-catalogue-v1`, rejects writes to `muscle_groups`/`equipment`,
  global `exercises` rows, and relationships touching bundled exercises.
- `initialize_account_sync_state()`: after `accounts` insert, inserts
  `account_sync_state(account_id)`.

## Observed lock behavior (facts only; exact SQL text)

- Acceptance (`AccountDeletionService`): `pg_advisory_xact_lock` on
  `deletion:<accountId>:<key>`; ledger `FOR UPDATE` reads;
  `auth_challenges ... FOR UPDATE`; `pg_advisory_xact_lock` on raw subject
  string; `google_identity_bindings ... FOR UPDATE`;
  `accounts ... FOR UPDATE`; session inventory
  `SELECT id FROM auth_sessions WHERE account_id=? ORDER BY id FOR UPDATE`;
  effects in one caller-bound `READ COMMITTED` transaction (challenge consume,
  ledger insert, intent insert, `accounts.deleted_at` set, session revoke-all
  with count check, binding delete with row-count check, receipt re-read).
- Status (`AccountStatusService`): same shape in one caller-bound
  `READ COMMITTED` transaction: locked challenge, subject advisory lock,
  locked account, locked binding, ledger read, conditional consume.
- Signup fence (`DeletionSubjectFence`, called from `AuthService.exchange`
  after replay lookup under held subject advisory): reads
  `distinctSubjectVersions`, requires each version present in ring, computes
  per-version digests and `latestDeletionForDigest`, enforces strictly
  post-latest-deletion `challengeCreatedAt`.
- Writers: `AccountWriteRepository.inAccountWrite` locks
  `accounts WHERE id=? AND deleted_at IS NULL FOR UPDATE`, then domain writes,
  `nextRevision` (live-root-only `UPDATE ... RETURNING revision`), single
  `publish`. `SessionAuthorization.requireLiveAccess` locks `accounts FOR
  UPDATE`, `auth_sessions FOR UPDATE`, then re-reads credential
  `FOR UPDATE OF c`. Read-only `requireLiveAccessForRead` takes no row locks
  (code comment).
- Cursor/snapshot fences: `SyncCursorRegistry.issue` and
  `SyncSnapshotRepository.create` do a bound
  `SELECT deleted_at IS NULL FROM accounts WHERE id=? FOR SHARE` immediately
  before a new token/header insert (existing-token reuse path has no fence).
  `DurableCopyHooks` callbacks run around those fences; production
  `NoOpDurableCopyHooks` is inert.
- Export (`AccountExportService` + `LifecycleProofService`): preliminary raw
  screen with no locks; provider verify outside DB locks; bound snapshot read
  joins caller transaction; consumption on a separate raw connection with
  order challenge `FOR UPDATE` then subject advisory then binding/account
  `FOR UPDATE`, freshness recheck adjacent to conditional consume; streaming
  over a read-only `REPEATABLE_READ` transaction with a per-segment
  `checkLive` (`SELECT deleted_at FROM accounts WHERE id=?` on a raw
  connection) before first byte, each segment/token, and final flush.
- Auth exchange/refresh/replay: subject advisory `pg_advisory_xact_lock`,
  key-serializing `pg_advisory_xact_lock` on `op:key`, challenge/credential
  `FOR UPDATE`, `lockSession` locks account then session rows.

## Observed existing cleanup behavior (facts only)

- `AuthService.cleanupExpired()` (called by `AuthCleanupScheduler` on
  `fixedDelayString ${auth.cleanup.fixed-delay-ms:60000}`): four bounded
  statements, each `LIMIT 500` with `FOR UPDATE SKIP LOCKED`: consumed/expired
  challenges; expired `auth_idempotency`; expired `auth_credentials`; sessions
  with no remaining credential or idempotency reference. One transaction
  (`@Transactional`).
- `SyncHistoryPruningService.pruneExpiredHistory()` /
  `SyncHistoryPruningRepository`: 90-day `RETENTION`; per-invocation
  `DEFAULT_ACCOUNT_BUDGET=100`, `DEFAULT_BATCHES_PER_ACCOUNT=500`;
  `findCandidates` joins `accounts`/`account_sync_state`/next-batch and
  filters `a.deleted_at is null`; per-account `REQUIRES_NEW` transaction with
  `accounts ... for update skip locked`, contiguous-prefix window read,
  exact row-count deletes of `sync_changes` then `sync_change_batches`, then
  watermark advance; corruption throws `CorruptSyncHistoryException`.
- `SyncSnapshotCleanupService.deleteExpiredSnapshots()` /
  `SyncSnapshotRepository.lockExpired/delete`: per-invocation
  `DEFAULT_SNAPSHOT_BUDGET=100` in one transaction; `lockExpired` selects
  `sync_snapshots where expires_at<=? order by expires_at,account_id,
  snapshot_id limit ? for update skip locked`; `delete` deletes the header
  with `expires_at<=?` (records cascade).
- Scheduler wiring observed: `SyncHistoryCleanupScheduler`,
  `SyncSnapshotCleanupScheduler`, `AuthCleanupScheduler` are `@Scheduled`
  fixed-delay paths; `application.yaml` defaults are 60000 ms initial/fixed
  delays and enabled flags for history/snapshot cleanup.

## Observed key-version behavior (facts only)

- `DeletionLedgerKeys` binds `${account.deletion.ledger-keys:}` JSON
  `{"activeVersion":"2","keys":{"1":"base64-32B",...}}`; blank means
  unconfigured (`isConfigured()==false`); present-but-malformed fails startup
  with a sanitized error; versions are 1..32767 with strict text/duplicate/
  base64/32-byte checks; `keyForVersion` throws when the version is absent.
- `DeletionLedgerCrypto`: HMAC-SHA256 with uint32 length prefix framing over
  every string/binary component including labels, raw int32 version, pinned
  order, canonical lowercase UUID text; labels `GT-DELETION-SUBJECT-V1`,
  `GT-DELETION-REQUEST-V1`, provider `google`, operation `deletion`;
  constant-time 32-byte compare.
- Ledger writes use the active ring version for both subject and request
  digests; receipt replay and status verification use the stored row versions
  via `keyForVersion`; absent required version raises, mapped to safe 503 in
  the calling service.
- Code comments state the ledger ring is independent of the auth encryption
  key; no shared-secret reuse observed in the ring class.

## Observed 9b readiness-gate behavior (facts only)

- `PurgeReadiness` interface with `isPurgeCapable()`; production bean
  `AlwaysUnavailablePurgeReadiness` (`@Component`) returns `false` with a
  comment stating the physical purger is absent so ordinary new acceptance is
  always unavailable.
- `AccountDeletionService` checks `readiness.isPurgeCapable()` for new
  acceptance (pre-transaction screen plus in-transaction recheck); identical
  already-accepted receipt recovery (`receiptOrConflict`) does not consult the
  gate; `AccountStatusService` does not consult the gate for stored rows.
- No request parameter bypassing the gate was observed in the controller/
  service code read.
- Test-only replacements observed under `src/test` (`TestPurgeReadinessReplacement`,
  `TestChildPurgeReadiness`, `TestAccountDeletionHooks`,
  `TestDurableCopyHooks`): test-classpath fixtures, not production beans.

## Open questions (no recommendations; Coordinator decision required)

1. Purge completion definition: which observed stores must be empty for old
   account A, and which whitelisted rows (`account_deletion_ledger`,
   `account_purge_intents` terminal state, anything else) must remain?
   Supporting facts: V8 `RESTRICT` from intents to ledger; `RESTRICT` FKs on
   child→parent domain rows, `sync_changes`→batches, snapshots→cursor tokens;
   `CASCADE` from snapshot headers to records and from `accounts` to snapshot
   headers; catalogue-protection and tombstone/account triggers above.
2. Purge ordering: what FK-safe delete order should the Coordinator require
   across snapshot records/headers/cursor tokens, `sync_changes`/batches/
   state, `processed_mutations`/`resource_mutations`, auth
   `idempotency`/`credentials`/`sessions`, domain sets/entries/roots/owned
   exercise relationships, reservations, bindings, `account_sync_state`,
   `accounts`, given the observed `RESTRICT` edges and the disabled-account
   anchor required by `prevent_tombstoned_child_delete`?
3. Durable progress: is any new purge-progress storage required beyond the
   observed `account_purge_intents(state='pending')` row, and what watermark
   fields belong in it?
4. Locking: what lock acquisition order should concurrent purge workers use
   relative to the observed writer/fence order (account-first `FOR UPDATE`,
   subject advisory, `FOR SHARE` fences, `SKIP LOCKED` cleanup claims) to
   avoid deadlock and double completion?
5. Deadline handling: how should `purge_by = deleted_at + 86400 seconds` be
   prioritized, retried and reported on breach, given the observed intent
   `purge_by` index and the existing fixed-delay cleanup schedulers?
6. Work scope per invocation: what finite per-invocation account/row limits
   apply to purge, given the observed 500-row auth victims, 100/500 pruning
   budgets and 100-snapshot budget facts (cited as existing-behavior facts
   only, not as proposals)?
7. Outstanding-work cancellation: what rechecks the Coordinator requires for
   export streaming (`checkLive` gates observed), cursor/snapshot fences,
   refresh/replay liveness, pruning candidacy (`deleted_at is null`
   observed), and snapshot-expiry cleanup when the target account is disabled
   or being purged?
8. B-isolation: what exact equality filters (old account UUID scoping, binding
   pair scoping) should govern every destructive statement and every
   signup/replay/status path so delayed purge of A cannot touch B, given the
   observed `owner_id`/`account_id` columns and `(subject, account_id)` binding
   deletes?
9. Anonymous challenges: should any `auth_challenges` rows be removed by
   purge, given the observed absence of an account column and the separate
   five-minute/consumed cleanup policy?
10. Key versions: which historical ledger-key versions must remain configured
    for receipt/status verification after rollover, and what failure is
    required when a stored version is absent, given the observed
    `keyForVersion` throw mapped to safe 503?
11. Readiness gate: what verified conditions and evidence flip ordinary
    acceptance from the observed always-false production gate to available,
    and what remains blocked until then?
12. Migration/config: are a forward-only migration and scheduler/key
    configuration changes required, and if so with what exact names, fields
    and defaults? No values are stated here; Coordinator approval is required
    before authoring.

## Worker state

- No production code, migration, test, config value or SLA authored.
- No Maven, build or test process run.
- No secrets, local database or `dev.env` accessed.
- Awaiting next assignment; this survey is the recorded output for
  assignment 1 as amended.

---

## Implementation notes (assignment 2 — design sections 1–12, as specified)

Implemented production exactly as specified in `docs/task-9c-design.md`
sections 1–12. No test files, shared-docs, secrets or applied migrations
touched. No surefire execution in this slot. Standing rule observed: only
local implementation decisions (method shapes, phase labels, SQL text);
no architecture decision taken locally.

### Files changed (all under owned production scope)

- `src/main/resources/db/migration/V9__purge_progress.sql` (new,
  forward-only): drops and re-adds the intent state check as
  `IN ('pending','in_progress','complete')`; adds `phase` (default
  `'pending'`), `attempts` (default 0, `CHECK >= 0`), `updated_at`
  (default `now()`), `completed_at` (NULL), `last_error` (NULL); adds
  index `(state, purge_by)`. V1–V8 untouched. Existing test inserts use
  explicit column lists, so new defaults are compatible.
- `src/main/java/com/dragosfotache/gtbackend/account/AccountPurgeRepository.java`
  (new): all purge SQL. Finite UUID-scoped bounded deletes (ctid-windowed,
  `LIMIT`-style per-table caps) plus claim/complete transitions on the
  intent row. Parent deletes that carry `RESTRICT` children
  (batches/headers/tokens/sessions) delete only rows with no remaining
  child, so phases never fault on the restriction. `verifyCounts` covers
  every section-4 store; the ledger row is asserted present, never deleted.
- `src/main/java/com/dragosfotache/gtbackend/account/AccountPurgeService.java`
  (new): section-4 phase order (`sets`, `entries`, `roots`, `exercises`,
  `reservations`, `sync_log`, `snapshots`, `replay`, `auth`, `binding`,
  `account`), one `REQUIRES_NEW` transaction per phase, progress persisted
  via `markPhase`, transient faults return the intent to `pending` with
  `attempts + 1` and sanitized `503`/`500` mapping through
  `LifecycleGoogle.isRetryablePersistenceFault`. Completion (`markComplete`
  with `completed_at`) happens only in the same transaction as the
  all-zero verification. `purge_by` is read, never written. Breach
  (`completed_at > purge_by`) finishes removal but logs one fixed sanitized
  warning and reports `breached=true`. Public entries: `purgeAccount(UUID)`
  and `purgeNextBatch(accountBudget)`.
- `src/main/java/com/dragosfotache/gtbackend/account/AccountPurgeScheduler.java`
  (new): `@Scheduled` fixed-delay sweep behind
  `@ConditionalOnProperty(prefix="account.deletion.purge", name="enabled",
  havingValue="true", matchIfMissing=false)`; claims at most
  `account-budget` intents ordered by `purge_by` via the repository claim,
  one bounded pass per account, per-account faults recorded without
  aborting the sweep, fixed sanitized scheduler log only.
- `src/main/java/com/dragosfotache/gtbackend/account/ConfigBackedPurgeReadiness.java`
  (new): production readiness bean active only when
  `account.deletion.purge.enabled=true`; returns the flag.
- `src/main/java/com/dragosfotache/gtbackend/account/AlwaysUnavailablePurgeReadiness.java`
  (edit): now `@ConditionalOnProperty(... havingValue="false",
  matchIfMissing=true)` so exactly one production readiness bean is active
  at a time. Default (absent/false) still returns false: ordinary deletion
  stays fail-closed. Test `@Primary` replacements are unaffected.
- `src/main/resources/application.yaml` (edit): added
  `account.deletion.purge` keys `enabled` (default false),
  `fixed-delay-ms`/`initial-delay-ms` (default 60000),
  `account-budget` (default 20), `row-budget` (default 500), each bound
  from `ACCOUNT_DELETION_PURGE_*` env with the specified defaults. No
  secret values.

### Boundaries kept

- Cohesive services, explicit outcomes (`AccountResult`/`SweepResult`,
  `PurgeException` with status+code). No controller SQL, no broad refactor,
  no request-body logging, no statement scoped by subject/credential key/
  resource UUID. Purger never takes the provider-subject advisory lock.
  Lock order per phase: account `FOR UPDATE`, then intent `FOR UPDATE`;
  `SKIP LOCKED` only in the intent claim.
- `auth_challenges` never deleted by purge (no account column; separate
  five-minute/consumption cleanup), per section 4.

### Verification in this slot

- Compile-only Maven under the build lock, no surefire:
  `flock /tmp/gt-backend-task7-build.lock ./mvnw -q -DskipTests package`
  → exit 0 (only JVM/Jansi/Guice warnings). No tests run, no test files
  edited, no local database or secrets touched.
- Open questions: none remaining against sections 1–12 as written; any
  future ambiguity will come back as an open question per the standing rule.

---

## Hardening fixes (assignment 3 — Coordinator structural review, no design latitude)

Three production-only fixes, implemented exactly as stated. No test edits,
no shared-docs edits, no new behavior beyond the three guards.

- Fix 1, missing intent guard (`AccountPurgeService.purgeAccount` entry):
  `lockIntent` null now throws `PurgeException(500, "internal_error")`
  before any delete runs. A purge without an intent row never deletes.
- Fix 2, disabled-anchor guard (new
  `AccountPurgeRepository.readAccountDisabled`, called once at
  `purgeAccount` entry after locking): row missing (null) proceeds as
  before for crash resume; row present with `deleted_at` null throws
  `PurgeException(500, "internal_error")` with no deletes. Never deletes
  for a live account. The read locks the account row (`FOR UPDATE`) to the
  caller commit.
- Fix 3, no-cascade account delete
  (`AccountPurgeRepository.deleteAccountRow`): added `AND NOT EXISTS
  (SELECT 1 FROM sync_snapshots s WHERE s.account_id=accounts.id)`, so the
  anchor delete affects zero rows while snapshot headers remain and the
  pass ends as `PROGRESS` instead of cascading headers away. Snapshot
  headers are still explicitly deleted in the `snapshots` phase first.
- Logs unchanged: every purge log message remains a fixed sanitized string
  with no identifier, deadline, SQL or payload.

Verification in this slot: `flock /tmp/gt-backend-task7-build.lock ./mvnw
-q -DskipTests package` → exit 0 (only JVM/Jansi/Guice warnings). No test
execution, no test edits.

---

## Self-check window 9c01 (assignment 4 — exclusive Worker slot, CLOSED blocked)

- Evidence root: `/tmp/gt-backend-task9c-worker-selfcheck-9c01/`.
- Before-manifest (`manifest.py` method, script unmodified): 162 paths,
  manifest SHA-256 `a534d2aaaa7543af3f7375374cd1b454f5c57296c70beafbabb680e40275dee3`.
- Exact command (once, under the build lock):
  `flock /tmp/gt-backend-task7-build.lock ./mvnw -q
  '-Dtest=Account*Test,Auth*Test,Google*Test' test`.
- UTC start `2026-10-02T13:57:30Z`, end `2026-10-02T14:00:23Z`, exit `1`.
- Result: **136 tests / 26 suites, 4 failures, 16 errors, 0 skipped**
  (fresh XML cross-check matches the Maven summary).
- After-manifest: byte-identical to before (same SHA-256 above). No input
  edits, no weakening, no fixes, no reruns. Stale pre-run surefire output
  was moved aside to `stale-target-reports/` before the run; fresh XML is
  in `fresh-reports/`.
- Observed failure facts (production findings for Coordinator triage, no
  local resolution):
  - `AccountPurgeDeadlineSchedulingHttpIntegrationTest.scheduledPurgeOnEmptyBacklogIsSafeNoOp`
    fails with `9c seam: production purge bean
    com.dragosfotache.gtbackend.account.AccountPurgeService has no no-arg
    scheduled purge method. Worker must provide it.` The QA suite expects a
    no-arg purge entry on the service bean; the current production entry
    points are `purgeAccount(UUID)` and `purgeNextBatch(int)` plus
    `AccountPurgeScheduler.sweep()`.
  - `unknownAccountPurgeIsSafeNoOp`, `repeatedPurgeIsIdempotentWithUnchangedReceipt`,
    `scheduledPurgeAfterCompletionIsStableNoOp` and most recovery/isolation
    cases error with `Purge internal_error` (sanitized 500). The unknown-account
    case has no intent row, which the assignment-3 Fix 1 guard now rejects;
    the suite expects a safe no-op there.
  - `rejectedDeletionWritesNoLedgerRowAndLeavesAccountLive`: expected `<0>`
    but was `<1>`.
  - `gatedAcceptanceDeniesRacingWriterWithoutSideEffects` and
    `purgeRemovesAllAccountBearingRowsAndKeepsLedgerAndCatalogue` fail on
    route-level assertions (`validation_error` envelope; routine-create
    response shape).
  - `largerAccountDrainsWithinFiniteInvocations` errors on fixture setup:
    `duplicate key value violates unique constraint
    "workouts_one_live_active_per_account"` from a test `INSERT INTO
    workouts ...` — a fixture-side fault independent of purge deletes.
- Window closed on first failure per instructions; all evidence preserved.
  Verdict: **blocked**, returned to Coordinator with evidence above.

---

## Diagnostic 9c01 + Change 1 + systematic-500 fix (assignment 5)

- Evidence root: `/tmp/gt-backend-task9c-worker-diag-9c01/` with two
  bracketed sub-windows (reproduce on unfixed inputs, verify on fixed
  inputs), each with before/after manifests and fresh XML.
- Reproduce (before1 == after1, SHA-256 `a534d2aa...`, 162 paths):
  `AccountPurgeNewAccountIsolationHttpIntegrationTest` alone →
  3 tests, 0 failures, 3 errors, all `Purge internal_error`, UTC
  2026-10-02T14:06:08Z–14:06:20Z, exit 1.
- Root cause (code reading, confirmed by the verify run): direct
  `purgeAccount` calls never moved the intent from `pending` to
  `in_progress` (only the scheduler claim path did), so `markComplete`
  — guarded by `WHERE state='in_progress'` — updated zero rows and threw
  `IllegalStateException("Purge intent completion failed")`, mapped to the
  sanitized 500. Every phase's deletes had already succeeded; only
  completion failed. No temporary cause-revealing output was needed, so
  none was added and none had to be removed.
- Change 1 (Coordinator decision, implemented exactly): new no-arg
  `AccountPurgeService.purgeDue()` running `purgeNextBatch` with the
  configured `account.deletion.purge.account-budget`; the service now
  injects that budget itself. `AccountPurgeScheduler.sweep()` calls
  `purger.purgeDue()`, so the scheduled path and the test seam exercise
  the same entry. Fix 1 kept exactly: no-intent purge still throws
  `PurgeException(500, "internal_error")` with no deletes.
- Systematic-500 fix (narrow, no broad refactor, no test edits): new
  `AccountPurgeRepository.beginProgress` (`pending`→`in_progress`,
  mirroring the claim transition) called once at `purgeAccount` entry for
  pending intents. `markComplete` semantics unchanged.
- Files changed: `AccountPurgeService.java` (purgeDue, account-budget
  injection, beginProgress call), `AccountPurgeRepository.java`
  (beginProgress), `AccountPurgeScheduler.java` (calls purgeDue).
- Verify (before2 == after2, SHA-256 `ce79642e...`, 162 paths): same suite
  → 3 tests, 1 failure, 0 errors, UTC 2026-10-02T14:07:13Z–14:07:25Z.
  Both other tests green; the systematic 500 is gone.
- Remaining failure (QA-side finding for Coordinator, not a production
  fault): `delayedPurgeKeepsBRichStateIntactAndFencesOldCredentials:218`
  expects `count(auth_sessions for B) == 1`, but the fixture creates two
  B sessions itself (`signup` exchange session plus synthetic `bearerFor`
  insert). Purge preserved B byte-intact (B-keeps-exercise, B-writes-after,
  old-bearer-fenced assertions all passed its earlier lines); making this
  pass from production would mean deleting B's legitimate session, which
  would violate B-isolation. No test edits made; QA realignment is a
  Coordinator decision. Known separate conflict stands: Fix 1 (no-intent
  purge is 500) vs `unknownAccountPurgeIsSafeNoOp` expecting a no-op.
- Full self-check not run, per instructions.

---

## Self-check rerun window 9c02 (assignment 6 — exclusive Worker slot, CLOSED blocked)

- Evidence root: `/tmp/gt-backend-task9c-worker-selfcheck-9c02/`.
- Before-manifest == after-manifest, 162 paths, manifest SHA-256
  `9fc94baa044d5e703b23730162997da513c16f7288146f78b8eb0104ff37aa2d`
  (new hash vs 9c01/diag because QA realigned several suites since; Worker
  inputs untouched in this window).
- Exact command (once, under the build lock):
  `flock /tmp/gt-backend-task7-build.lock ./mvnw -q
  '-Dtest=Account*Test,Auth*Test,Google*Test' test`.
- UTC start `2026-10-02T14:09:28Z`, end `2026-10-02T14:12:19Z`, exit `1`.
- Result: **136 tests / 26 suites, 3 failures, 0 errors, 0 skipped**
  (fresh XML cross-check matches). Down from 4 failures + 16 errors in
  9c01: every `Purge internal_error` is gone, including all recovery,
  isolation, deadline, replay and race suites.
- The 3 remaining failures (no production purge error in any of them):
  1. `unknownAccountPurgeFailsClosedWithoutDeletes:130 expected <0> but
     was <2>` — the realigned fail-closed oracle holds (500 with no
     deletes), but its unscoped global `count(*) FROM
     account_deletion_ledger` sees the two ledger rows left by the other
     methods in the same shared-DB class. QA isolation finding.
  2. `rejectedDeletionWritesNoLedgerRowAndLeavesAccountLive:203 expected
     <0> but was <1>` — same shape: unscoped global ledger/intent count
     observing another method's accepted-deletion row on the shared class
     database. QA isolation finding.
  3. `purgeRemovesAllAccountBearingRowsAndKeepsLedgerAndCatalogue:188
     expected <200> but was <422>` — the workout-children PUT fixture
     sends string-typed `"repetitions":"5"`, `"weightKg":"20"`,
     `"restSeconds":"60"` where the strict API requires numbers, so setup
     itself is rejected before purge runs. QA fixture finding.
- No input edits, no fixes, no reruns in this window. Stale pre-run
  surefire output moved aside to `stale-target-reports/`; fresh XML in
  `fresh-reports/`. Verdict: **blocked** on the three QA-side findings
  above; production purge calls succeed in every suite.

---

## Self-check rerun window 9c03 (assignment 7 — exclusive Worker slot, CLOSED green)

- Evidence root: `/tmp/gt-backend-task9c-worker-selfcheck-9c03/`.
- Before-manifest == after-manifest, 162 paths, manifest SHA-256
  `cd88c3beca88b6b3f06bb24189467327758b4b6db3ffc8ccdcf2c7221e6f2a1e`
  (new hash vs 9c02: QA realigned the three 9c02 findings since; Worker
  inputs untouched in this window).
- Exact command (once, under the build lock):
  `flock /tmp/gt-backend-task7-build.lock ./mvnw -q
  '-Dtest=Account*Test,Auth*Test,Google*Test' test`.
- UTC start `2026-10-02T14:15:30Z`, end `2026-10-02T14:18:33Z`, exit `0`.
- Result: **136 tests / 26 suites, 0 failures, 0 errors, 0 skipped**
  (fresh XML cross-check; the `-q` log carries no summary lines).
  All three 9c02 QA-side findings verify as realigned on the Worker side
  with no Worker change in this window.
- No input edits, no fixes, no reruns. Stale pre-run surefire output moved
  aside to `stale-target-reports/`; fresh XML in `fresh-reports/`.
  Verdict: **green**.
