# GymTracka backend

## Project setup and contract

Java 27, Spring Boot 4.1.1, Maven wrapper, Flyway and PostgreSQL; base package `com.dragosfotache.gtbackend`. The
authoritative v1 behavior and wire shapes are in `shared-docs/docs/backend-contract/README.md` and its linked
documents/OpenAPI. Resolve any specification mismatch explicitly; backend implementation notes in this repository do not
override the shared contract. Android source is not needed to interpret the backend contract.

`shared-docs/` is a **separate Git checkout at this path, never a submodule**. A fresh backend checkout must check it
out independently to supply the pinned catalogue JSON seed and redistribution notices to the build. Do not stage its
checkout in the backend repository or convert it into a submodule. The catalogue importer uses the pinned
`gymtracka-catalogue-v1` seed with manifest checksums and installed-value verification; bundled catalogue rows are
global and read-only.

The accepted 8c baseline records the shared repository revision and exact verified dependency hashes in
`docs/shared-docs-inputs.sha256`. The supplied shared checkout has uncommitted changes, so its HEAD alone does not
reproduce those inputs. From this backend root, verify them with `sha256sum -c docs/shared-docs-inputs.sha256`.
Review and commit shared-docs changes separately; never include that checkout in a backend commit.

Never edit already-applied Flyway V1–V5; use forward-only new migrations for schema changes (V5 adds sync replay
storage). Never commit secrets, database credentials or raw Google tokens. Do not stage or commit work without explicit
approval.

## Code navigation

Prefer `mcpls` for Java code navigation (document symbols, definitions, references, hover) through the `pi-mcp-adapter`
`mcpls` server backed by `jdtls`. `jdtls` lives at `~/.local/opt/jdtls` (`~/.local/bin/jdtls`) and is configured in
`~/.config/mcpls/mcpls.toml` with `pom.xml`/`build.gradle` project markers. Use `rg` for broad text search and LSP for
precise symbol queries.

## Authentication (subtask 3)

Google sign-in configuration is intentionally required: set `GOOGLE_OAUTH_AUDIENCE` to the web/server OAuth client ID
and `AUTH_ENCRYPTION_KEY` to a base64-encoded, randomly generated 32-byte key (for example, generate it with a secret
manager; never commit it). Startup fails closed if either is absent or invalid. Google verification uses Google's
rotating public signing keys and checks signature, issuer, audience, expiration, nonce and subject. A real Google test
account was used for a localhost browser sign-in/refresh/logout smoke test; this did **not** verify Android sign-in,
deployed HTTPS or release signing. The deployed URL, release OAuth configuration and Android integration remain
unverified external work; do not invent IDs. No raw Google ID token is persisted.

Authentication endpoints issue 15-minute access and sliding 30-day refresh credentials. Auth V4 migration is
forward-only. Challenge/exchange/refresh/logout are implemented; session authorization must be invoked inside later
protected-write transactions and held through commit. `AuthService.cleanupExpired()` is callable and transactional, and
`AuthCleanupScheduler` invokes it on a configurable fixed delay (`AUTH_CLEANUP_FIXED_DELAY_MS`, default 60 seconds;
initial delay configurable with `AUTH_CLEANUP_INITIAL_DELAY_MS`). Each invocation processes at most 500 rows from each
cleanup class; later ticks drain backlogs. The local Spring scheduler runs one task at a time; PostgreSQL `SKIP LOCKED`
permits safe concurrent instances. Expired encrypted replay rows are erased with their credentials at expiry, with no
permanent authentication-key tombstones; a retry using an erased exchange key and consumed challenge fails
authentication. Scheduling intervals and accumulated backlogs mean physical erasure can lag expiry by the configured
delay and cleanup duration. Authentication responses, including framework errors, use `Cache-Control: no-store` and
sanitized error bodies. Do not log request bodies or credentials.

## Tests and local development safety

Run `./mvnw test` from this project root with Docker available. Integration tests use isolated Testcontainers PostgreSQL
16, real HTTP for the implemented routes, and mocks only for external Google behavior; they do **not** connect to the
configured local `gymtracka` database. Testcontainers may download images on first run. Latest bounded 8b independent QA passed 167 targeted / 188 full tests (12 / 15 suites), with zero failures/errors/skips; Coordinator passed eight HTTP/PostgreSQL spots on the same frozen inputs. See `docs/task-8b-acceptance.md` and `docs/task-8b-qa.md`. Historical final post-cleanup task-6
independent QA targeted checks passed 116 tests; full `./mvnw -q test` passed 137 tests across 8 suites, with 0
failures/errors/skips. Coordinator independently passed five risk-based HTTP/PostgreSQL spot checks on the identical
frozen inputs. See `docs/task-6-acceptance.md` and `docs/task-6-qa.md` for exact commands, hashes, fresh artifacts,
route coverage and limits. Earlier bounded task-5g acceptance (89 targeted / 122 full tests) remains documented in
`docs/task-5g-acceptance.md`. Tests cover implemented sync push and direct mutations; implementation notes are
`docs/sync-push-implementation.md` and `docs/resource-mutations-implementation.md`. The auth cleanup scheduler may log a
connection error after a Testcontainers database stops even when tests pass; investigate this separately rather than
treating green tests as complete API coverage.

The existing local PostgreSQL `gymtracka` database is **not a test fixture**: do not reset it or run destructive tests
against it. V1–V4 have been applied there; V5 and V6 have been verified in isolated test databases, not applied there as
part of these tasks. Local development secrets reside outside the repository at `~/.config/gymtracka-backend/dev.env`;
do not print, copy, overwrite, commit or casually rotate them. Its stable encryption key protects auth replay. Source it
only for an explicitly requested local development startup, never for Testcontainers tests.

## Persistence foundation (subtask 2)

Flyway V3 adds `accounts`, owned workout/routine/custom-exercise roots, ordered children, owner-aware exercise
visibility checks, account/root/parent-bound identity reservations, and per-account sync state/batches/changes. Root
tombstones and child identity reservations persist when child rows are removed; child UUID rebinding/reparenting is
rejected, and tombstoned roots/children cannot be changed or restored. Bundled exercises remain ownerless and are
excluded from user-root revision/reservation/sync handling; after the fixed seed version marker exists, triggers reject
SQL mutation of bundled rows and relationships. Do not edit applied migrations; future schema changes require a new
migration.

`AccountWriteRepository.inAccountWrite(ownerId, work)` is the transaction entry point for future domain writers. Call it
through Spring injection so `@Transactional` proxying applies. It locks the enabled account row through commit,
serializing writes for that owner. Within its callback, write domain state, use `nextRevision(type,id)` for live
owned-root revision changes (it rejects tombstones), then publish one complete list of `Change` entries using
`publish(...)`. Tombstoned roots reject domain-field changes and resurrection, but allow a strictly increasing revision
plus `updated_at`-only update for repeated deletes; `nextRevision` itself rejects tombstones, so the sync delete command
implements that narrow update and publishes its batch explicitly. Tombstoned children reject normal
insert/update/delete. Physical child purge is permitted only after the owning account is disabled (`accounts.deleted_at`
set), and reservations remain until the separate account-purge phase. Domain changes, revisions, cursor allocation,
batch, and entries must all remain in this single transaction. The per-account cursor counter is updated transactionally
while the owner lock is held: rollback consumes no visible cursor, and another same-account writer cannot
allocate/commit a later cursor before the prior transaction commits. Batch `committed_at` uses PostgreSQL
`clock_timestamp()` at publication while holding that lock, rather than transaction-start `now()`. `findWorkout` is an
owner-filtered lookup returning the same absence for foreign and missing roots.

This section describes the V3 persistence foundation, not the current limits of the whole backend.
Authentication/session endpoints and account provisioning are implemented separately. The low-level
`CanonicalAggregateRepository` in `persistence/` is a row adapter, **not an accepted mutation API**: direct construction
in tests bypasses Spring `@Transactional`, so tests wrap its writes in `TransactionTemplate`; production multi-query
reads require a consistent transaction. Accepted writes must combine account/session authorization, domain changes,
revisions, one complete change batch and replay evidence atomically. Do not expose row-adapter methods as public domain
endpoints. The sync mutation executor composes this boundary for all seventeen mutation kinds; see below.

## Implementation status and remaining work

Subtasks 1–4 established the catalogue, persistence, authentication and canonical domain foundation
(`docs/canonical-aggregate-foundation.md`). Sync push (subtask 5) is **accepted through bounded 5g verification**
(`docs/task-5g-acceptance.md`): `docs/sync-push-implementation.md` describes the implemented preflight, exact
mutation-object byte capture, replay and mutation kinds. V5 provides account-lifetime exact-byte accepted/rejected
replay, account-locked/auth-fenced per-mutation transactions, canonical batches and opaque account-bound cursors. The
executor supports all seventeen kinds: `routine.upsert`, `customExercise.upsert`, `routine.save`, `workout.start`,
`workout.upsert`, `workout.finish`, `workout.performAgain`, `routine.startWorkout`, `workout.saveAsRoutine`,
`workout.addExercises`, `workout.replaceExercise`, `workout.deleteExercise`, `workout.deleteSet`,
`routine.deleteExercise`, `routine.deleteSet`, `aggregate.delete`, and `aggregate.import`. The latter two implement
owner-only tombstone deletes and create-only imports for all three root types, using the same durable replay and atomic
batch path. Slice 5d child commands operate on owned roots in the same ordered/account- and session-fenced per-mutation
transaction, with savepoint rollback, durable exact-byte replay, one root revision and one canonical batch; workouts
must be ACTIVE, while routine deletes permit draft or saved routines. Replacement preserves all child state and IDs;
deletes cascade/renumber and retain entries after their last set is removed. Real HTTP/Testcontainers PostgreSQL tests
cover first/middle/last exercise/set deletes, replacement projections, missing/foreign/tombstoned roots and references,
cross-root children, retry and concurrency behavior, and an injected rollback fault. Slice 5e's `workout.performAgain`,
`routine.startWorkout`, and `workout.saveAsRoutine` are implemented with source eligibility checks, exact ordered child
bindings, fresh identity checks and copy-specific transaction behavior. `workout.addExercises` appends to an owned
active workout, selecting deterministic latest finished history per exercise or using the contract fallback set. All
four approved 5e commands and both 5f aggregate commands are implemented. Slice 5g added cross-kind lifecycle,
replay/label, reservation, copy rollback, concurrency and real-process restart coverage, and fixed unexpected integrity
errors that could poison mutation replay while preserving domain collision/reference outcomes. Task 5 is signed off for
the implemented push surface, not the complete v1 API or deployment. The separate routine-root-versus-child collision
permutation remains untested; its common trigger/classifier was reviewed. Pull, pruning and snapshots belong to later
task 8.

Task 6 is **accepted** for all 14 direct mutable exercise/routine/workout operations and separate account-scoped
resource idempotency (`docs/task-6-acceptance.md`). V6 stores exact method/concrete-route/raw-body identity and original
HTTP status/body independently of sync replay/log retention. `ResourceMutationExecutor` combines resource replay, direct
route policy, shared domain execution/publication and response persistence in one live-session/account-fenced
transaction. Resource preflight uses small named stages; common execution returns explicit accepted/rejected results
rather than a nullable resource mode. The cleanup passed a specific maintainability review and fresh regression checks.
See `docs/resource-mutations-implementation.md` for boundaries and retry rules; the same UUID on a different surface is
a different operation, not recovery of an uncertain write. Legacy command complexity is recorded in
`docs/technical-debt.md` for a separately approved task.

Maintainability/readability are mandatory acceptance criteria alongside correctness: prefer cohesive methods, clear
domain/results and practical responsibility boundaries; do not enlarge complex methods with new mode flags or
speculative abstractions. Preserve behavior/tests, and schedule larger legacy refactors explicitly rather than slipping
them into unrelated work.

Task 7 is split into **7a individual exercise/workout/routine reads** and **7b exercise search, workout/saved-routine
browsing, and shared 15-minute stateless keyset pagination**. **7a is accepted**: three individual GETs, shared
canonical mapping, live read authorization and consistent aggregates. Final independent QA passed 127 targeted / 148
full tests (zero failures/errors/skips); Coordinator passed five HTTP/PostgreSQL spots on identical frozen inputs and
the mandatory clean/readable-code gate. See `docs/task-7a-acceptance.md`. The user authorized 7b after this checkpoint
with fresh Luna Worker/QA contexts. **7b and integrated task 7 are now accepted**: final independent QA passed 144
targeted / 165 full tests (zero failures/errors/skips), Coordinator passed six HTTP/PostgreSQL spots and the mandatory
structural gate. See `docs/task-7b-acceptance.md` for signed stateless pagination, scope and coverage limits. Both
contracts were defined upfront in `docs/task-7-contract.md`; execution history is in `docs/task-7b-contract.md`.
The user subsequently approved three Task 8 slices, with a fresh gpt-6-luna High Worker and QA per slice. **8a is accepted**: `GET /v1/sync/pull`, complete committed batches, stored historical payloads, live authorization and shared stable account-bound cursors. It uses existing V5 storage, with no migration; GET may register missing cursor-token metadata but does not publish changes or alter domain/replay/credential state. An absent cursor starts at retained history, not a full bootstrap. Independent QA passed **156 targeted / 177 full tests**; Coordinator passed seven HTTP/PostgreSQL spots and the mandatory structural gate. See `docs/task-8a-acceptance.md`. Fixed scope/criteria: `docs/task-8-contract.md`. **8b is accepted** for production 90-day contiguous-prefix pruning and bounded scheduling, preserving original replay, cursors, domain/identity state and credentials. Defaults are 100 accounts /500 complete batches per invocation, enabled with 60-second initial/fixed delays (`SYNC_HISTORY_CLEANUP_*`). Independent QA passed **167 targeted /188 full tests**; Coordinator passed eight HTTP/PostgreSQL spots and the structural gate. Acceptance: `docs/task-8b-acceptance.md`; criteria: `docs/task-8b-contract.md`.

**Task 8c is accepted** for durable frozen snapshots and bounded expired-storage cleanup (`docs/task-8c-acceptance.md`).
`GET /v1/sync/snapshot` returns all live owned roots, including drafts, as complete canonical records with one committed
baseline. Account-bound tokens preserve frozen contents, labels and page replay across service-process restart; limits
are 1–500, default 200. Access expires after exactly 24 hours. The user approved bounded physical cleanup afterward:
scheduling, backlog or downtime may delay deletion without extending access or the separate account-deletion purge deadline.
Final independent QA passed **187 targeted and 208 full-suite tests**, with no failures, errors or skips. The Coordinator
passed eleven HTTP/PostgreSQL spots and the structural gate on identical frozen inputs. V7 and the approved
`SYNC_SNAPSHOT_CLEANUP_*` scheduler properties add only snapshot storage and cleanup; defaults are enabled with 60-second
delays and a 100-header work budget. No production cleanup-latency SLA is claimed.

The agreed three-slice Task 8 sequence is now accepted within its bounded scope. Worker and QA are idle awaiting assignment.
Tasks 9 and 10 remain unauthorized. This is not complete-v1 or deployment acceptance. Current checkpoint:
`docs/coordinator-handoff.md`.

The remaining agreed sequence is: (8c) 24-hour frozen sync snapshots;
(9) fresh-Google-proof export, account status/deletion, durable minimal deletion
evidence and live-data purge; (10) full API/DB acceptance, privacy-safe logs, backup/restore and deployment/HTTPS/OAuth
release verification. Each area needs real API/PostgreSQL tests; do not infer completion solely from a passing suite or
from this roadmap.
