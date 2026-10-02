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
