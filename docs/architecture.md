# Backend architecture

This guide describes current code, not a replacement for the
[shared contract](../shared-docs/docs/backend-contract/README.md).
For operating instructions see [personal-server.md](personal-server.md);
for unresolved correctness and coverage issues see [technical-debt.md](technical-debt.md).

## Component map

All classes below are under `src/main/java/com/dragosfotache/gtbackend/`.

| Package | Responsibilities and principal classes |
|---|---|
| `auth` | `AuthController`/`AuthService`: challenge, exchange, refresh, logout and cleanup. `GoogleTokenVerifier`: external token verification. `SessionAuthorization`: live account/session/credential checks. `LifecycleProofService`: non-signup proof admission. |
| `domain` | `CanonicalAggregates`: internal aggregate values. `AggregateValidator`: complete shapes and domain invariants. |
| `persistence` | `CanonicalAggregateRepository`: owner-filtered row adapter. `AccountWriteRepository`: serialized account writes, revisions and batch publication. Browse, history-pruning and snapshot row adapters. |
| `resource` | Direct mutation preflight/execution, canonical mapping, coherent individual reads, live keyset browsing and signed list tokens. |
| `sync` | `PushPreflight`, `RoutineUpsertExecutor`: ordered commands/replay. Pull/cursor services, frozen snapshots and bounded cleanup schedulers. |
| `account` | Export admission/streaming, deletion acceptance/status, minimal ledger and versioned HMAC keys, purge repository/service/scheduler and readiness flag. |
| `catalogue` | `CatalogueImporter`: fixed seed checksum and installed-value validation. Bundled catalogue data is global and protected from mutation. |

Controllers handle HTTP parsing and sanitized response mapping; repositories own
SQL. Low-level row adapters are not standalone domain APIs: callers must supply
authorized transactions, replay ownership and complete publication where required.

## Write transactions and exact replay

`AccountWriteRepository.inAccountWrite` locks the live account through commit.
Protected mutations also use `SessionAuthorization.requireLiveAccess`, which
locks account, session and credential state. Revocation/deletion cannot be ignored
by relying on an earlier bearer check.

Domain rows, root/child reservations, revisions, account cursor allocation,
complete ordered change batch and replay evidence commit together. Rollback leaves
no visible cursor hole. The mutation engine uses savepoints for recognized domain
rejections; unexpected integrity faults must not become permanent domain outcomes.

Direct resources and sync share domain application/publication through
`RoutineUpsertExecutor.executeDomainCommandInAccountWrite`, but **not replay identity**:

| Surface | Identity and stored outcome |
|---|---|
| Sync | Account + mutation UUID + exact UTF-8 mutation-object bytes, excluding surrounding array whitespace/separators. Original accepted batch or rejected code in `processed_mutations`. |
| Direct resource | Account + idempotency key + method + normalized concrete route + entire original body bytes. Original status/body in `resource_mutations`. |
| Authentication | Operation-specific exchange/refresh key and request identity; encrypted grants retained through their defined expiry. Live authorization is rechecked on replay. |
| Deletion | Old account + accepted key + versioned keyed digest of exact body bytes; replay returns the original receipt, never a credential grant. |

Whitespace, property order and numeric spelling affect exact-byte identity. Do not
recover an uncertain operation by switching surfaces. Resource/sync outcomes and
identity reservations survive log pruning for the account lifetime, then belong
in whole-account purge.

## Reads, pull, snapshots and export

- Individual resources are owner-filtered and read coherently in a transaction.
  Read authorization alone is not a long-stream cancellation mechanism.
- Resource lists use live keyset pagination with signed, 15-minute continuations
  bound to account, endpoint, filters and limit. They are not frozen list sessions.
- `GET /v1/sync/pull` returns complete stored commit batches. It does not
  recompute old convenience labels. An absent cursor reads retained history,
  **not** a full account bootstrap.
- `GET /v1/sync/snapshot` captures all live owned roots, including routine drafts,
  with one baseline cursor. Stored records preserve labels and page contents
  across restarts. Access expires exactly 24 hours after creation. Expired storage
  cleanup can lag; this does not relax the separate account-purge deadline.
- New cursor registration and snapshot header creation use a live-account
  `FOR SHARE` fence before insertion. Consider implicit FK locks when arranging races.
- Export is one direct JSON attachment, not a stored artifact or resumable
  snapshot. `LifecycleProofService` commits admitted proof consumption separately
  from the repeatable-read export view. `AccountExportService` checks current
  account liveness through a separate connection during streaming. Downloaded bytes
  cannot be recalled; an interrupted file must not be treated as a complete export.

Canonical `exerciseName` is a convenience projection. Custom-exercise rename does
not rewrite historical batches or increment every referencing root revision.
Android must resolve labels from its current selected-scope exercise data.

## Authentication and account lifecycle

Google subjects, not email or display name, own identity bindings. Signup serializes
provider binding work with an advisory transaction lock. Challenges are purpose-bound
(`signIn`, `export`, `deleteAccount`, `accountStatus`), valid for five minutes,
and successful consumption is one-use. Service access credentials last 15 minutes;
refresh credentials have a sliding 30-day lifetime.

Deletion acceptance records ledger evidence and intent, consumes proof, disables
the account, revokes sessions and detaches the binding atomically. Status and export
do not sign in or mint grants. Identical accepted deletion replay can recover its
receipt after old proof expiry; fresh status verifies ownership of the specified
old account using ledger evidence. A fresh post-deletion signup creates a distinct
account B; old A work cannot be reassigned to B.

`DeletionSubjectFence` checks the signup challenge's creation against the latest
accepted deletion. Keep that fresh-attempt boundary separate from ordinary auth replay.

## Purge: actual implementation and limits

`AccountPurgeScheduler.sweep` calls `AccountPurgeService.purgeDue` when enabled.
Defaults: 60-second initial/fixed delays, 20 intents per sweep, 500 rows per table
per phase. The service selects pending/in-progress intents in deadline order with
`FOR UPDATE SKIP LOCKED`; claim locks end before phase processing. They are **not**
a durable single-worker lease.

`purgeAccount` requires an intent and rejects a present live account. It runs the
following phases, each in `REQUIRES_NEW`, normally locking account then intent:

1. Workout/routine sets, entries, then roots.
2. Owned exercise muscle/equipment relationships, then owned exercises.
3. Child/root identity reservations.
4. Sync changes, batches and account sync state.
5. Snapshot records, headers and cursor tokens.
6. Sync/resource replay.
7. Auth replay, credentials and sessions.
8. Old-account bindings, then the account anchor.
9. Explicit per-store counts and conditional completion.

The disabled account must remain while tombstoned-child trigger checks run.
Deletion statements use the old account UUID, not the Google subject now bound to B.
Snapshot headers, sync batches and sessions have some dependency guards; the account
delete also guards against snapshot cascade. Domain-parent deletes lack equivalent
bounded-child guards: see the concrete large-collection risk in technical debt.

V9 records state, phase, attempts, timestamps and sanitized last error. Faults roll
back the failing transaction and try to mark the intent pending. **Every retry
starts the phase list again; the recorded phase is not used as a resume cursor.**
Completion checks all inventoried live stores in one transaction, retaining the ledger
and a terminal intent row. Anonymous challenges are not account-scoped purge victims.

The original `purge_by = deleted_at + 86,400 seconds` is immutable. Late completion
logs a warning; overdue unfinished work does not currently have a health/alert path.
`ConfigBackedPurgeReadiness` returns the enable flag, not scheduler-health or deadline
capacity evidence. These are limitations, not a proved 24-hour guarantee.

## Migrations and keys

| Migration | Main responsibility |
|---|---|
| V1 | Global catalogue schema |
| V2 | Custom exercises |
| V3 | Accounts, roots/children, reservations, triggers and sync log/state |
| V4 | Identity bindings, challenges, sessions, credentials and auth replay |
| V5 | Exact sync replay bytes and durable cursor tokens |
| V6 | Direct resource replay |
| V7 | Frozen snapshot headers/records |
| V8 | Minimal deletion ledger and separate purge intents |
| V9 | Purge progress columns/state and claim index |

`AUTH_ENCRYPTION_KEY` derives separate auth HMAC/encryption keys and protects
credential lookup, encrypted grant replay and pagination signatures. Treat it as
stable; casual rotation invalidates existing data/tokens.

`ACCOUNT_DELETION_LEDGER_KEYS` is an independent versioned HMAC ring. Ledger rows
hold their subject/request digest versions; keep all referenced versions. Missing
required keys fail closed. Raw Google tokens and credential-bearing deletion bodies
are not ledger contents. Neither ledger metadata nor intent rows are anonymous.

## Maintenance boundaries

Auth cleanup removes expired/consumed challenges, expired replay/credentials and
unreferenced sessions in bounded chunks. History pruning removes only the eligible
90-day contiguous prefix for live accounts; it does not expire replay evidence.
Snapshot cleanup removes expired headers (records cascade), independently of purge.

When changing a store or durable-copy path, update the purge inventory/completeness
queries and review authorization, FK/trigger ordering and B isolation. Keep existing
fault/concurrency tests, but do not use one representative passing race as proof of
all participants. No current backup-restore procedure safely reconciles newer deletion
and synchronization evidence with an old domain backup.
