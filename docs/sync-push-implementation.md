# Sync push implementation: task 5 accepted through bounded 5g verification

`POST /v1/sync/push` requires a live bearer session, strictly preflights the complete UTF-8 request before returning any
result, and returns `{"results":[]}` for an empty push. Preflight rejects duplicate keys/nonfinite values and malformed
JSON as `400 invalid_request`; invalid envelope/payload shapes, unknown fields/kinds, invalid canonical UUIDs,
target/type mismatches and repeated mutation IDs use their contract `422` codes. Mutation identity bytes are captured
from each object's opening `{` through matching `}`, excluding surrounding array whitespace/separators; they are never
reserialized and are not exposed over HTTP.

## Current execution boundary

`sync/RoutineUpsertExecutor` retains the ordered account-lock/session-fenced per-mutation transaction and exact-byte
replay flow, shared across all seventeen supported kinds: `routine.upsert`, `customExercise.upsert`, `routine.save`,
`workout.start`, `workout.upsert`, `workout.finish`, `workout.performAgain`, `routine.startWorkout`,
`workout.saveAsRoutine`, `workout.addExercises`, `workout.replaceExercise`, `workout.deleteExercise`,
`workout.deleteSet`, `routine.deleteExercise`, `routine.deleteSet`, `aggregate.delete`, and `aggregate.import`. A domain
rejection records its original code without domain/log effects and does not prevent later supported mutations from
running. Successful writes store canonical revisioned batch content and original outcomes. V5 stores exact request bytes
with account-lifetime replay evidence and opaque account-bound cursor tokens. Identical retries return `duplicate` with
the original accepted batch or rejection code; changed bytes under the same account/mutation ID return
`mutation_id_reused` without replacing evidence.

All contract mutation kinds are supported. Empty pushes return `{"results":[]}`. Unexpected server failures return
sanitized `500 internal_error`; retryable service failures use `503 temporarily_unavailable`. There is no longer an
unsupported-kind 503 boundary.

`customExercise.upsert` performs complete metadata and relationship replacement through the guarded row adapter;
`routine.save` promotes/validates only an existing owned routine; `workout.start` atomically tombstones the prior active
root and publishes ordered DELETE then UPSERT; `workout.upsert` replaces existing active state only; `workout.finish`
requires a completed set, removes unfinished sets/empty entries, renumbers survivors and finishes atomically.

Slice 5d adds exactly five sync child commands. `workout.replaceExercise` targets an owned ACTIVE workout, validates the
entry within that root before validating reference visibility, then changes only the exercise reference; child IDs,
positions, notes, configured rest and all set values remain unchanged, with canonical labels resolved for publication.
`workout.deleteExercise` and `workout.deleteSet` also require an owned ACTIVE workout. `routine.deleteExercise` and
`routine.deleteSet` accept an owned draft or saved routine. Exercise deletion cascades its sets; set deletion leaves the
parent entry even when the last set is removed. All deletes renumber consecutive surviving siblings while preserving
their IDs and values. Each accepted child command increments exactly one root revision and emits exactly one canonical
one-root batch. Missing/foreign roots and child IDs resolve to `not_found`; finished workout commands return
`invalid_state`; invalid, deleted, or foreign custom-exercise references return `invalid_reference`. Domain rejections
are durably replayed without revision, domain, or batch effects.

## Slice 5e: `workout.performAgain`

`workout.performAgain` accepts only an owned finished source workout. Under the normal account lock and savepoint, it
verifies the complete ordered `childIds` source-entry/set bindings and that the new workout root plus every entry/set
UUID are fresh in the workout identity reservation family. It clones the source name, exercise order/references, notes,
configured rest and set repetitions/weight; completion is cleared and recorded rest set to null. It uses the existing
start transaction to tombstone any prior active workout, then publishes one batch ordered DELETE old active followed by
UPSERT new workout. Missing/foreign sources return `not_found`, non-finished sources `invalid_state`, binding mismatch
`source_shape_changed`, and reserved IDs `id_collision`. Original accepted/rejected outcomes use the existing exact-byte
durable replay path.

`routine.startWorkout` accepts only an owned saved routine with a nonblank name and at least one exercise. It verifies
exact ordered source-entry/set bindings and fresh workout-family IDs, copies exercise order/references, configured rest,
nullable set inputs and the routine name, gives workout entries empty notes, and creates incomplete sets with null
recorded rest. A source exercise with no sets remains as an empty-set workout exercise. The routine is unchanged. It
shares the atomic active-workout replacement and ordered DELETE/UPSERT publication path and durable exact-byte replay
used by `workout.performAgain`; missing/foreign sources return `not_found`, drafts or ineligible sources
`invalid_state`, changed bindings `source_shape_changed`, and reserved IDs `id_collision`.

`workout.saveAsRoutine` requires an owned finished source, a nonblank requested name and complete ordered entry/set
bindings. It creates a new saved routine using fresh routine-family root/child IDs, copying exercise references/order,
configured rest and set repetitions/weight while excluding workout notes, completion and recorded rest. The source and
any current active workout are untouched. It uses the guarded create-only persistence primitive inside the existing
savepoint/account/session-fenced transaction and persists the original accepted/rejected result for exact-byte replay.
Missing/foreign sources return `not_found`, unfinished sources `invalid_state`, changed bindings `source_shape_changed`,
reserved IDs `id_collision`, and semantic blank names `validation_error`.

## Slice 5e: `workout.addExercises`

`workout.addExercises` appends selected exercises to an owned ACTIVE workout without changing existing entries. It
validates every requested exercise as bundled or owned/live, selects the latest finished workout containing each
exercise (deterministic timestamp/ID ordering), copies its completed-set shape and values, and uses the contract
fallback set when history is absent. Repeated selections are handled independently in request order. The executor checks
exact target bindings and fresh workout-family IDs before persistence; stale shapes, invalid references, collisions, and
missing/foreign/finished targets are rejected without domain effects. Accepted/rejected outcomes use exact-byte replay.

## Slice 5f: aggregate delete and import

`aggregate.delete` accepts an owned live workout, routine or custom exercise, tombstones its root and emits a revisioned
DELETE. A new mutation ID for a known owned tombstone advances its revision and emits another DELETE without changing
its original `deleted_at`; an identical retry replays the earlier batch. Missing/foreign roots return `not_found`,
bundled exercises `read_only`, and a custom exercise referenced by any live owned workout (including finished history)
or draft/saved routine returns `exercise_in_use`. Deleted roots cannot be resurrected.

`aggregate.import` creates a fresh root snapshot only: active or valid finished workout, draft or saved routine
(including a legitimately blank/empty saved snapshot), or custom exercise with full relationships/metadata. It preserves
submitted IDs/children/nulls and validates ownership, reference visibility and family/parent reservations. Existing or
tombstoned IDs collide rather than overwriting cloud data. Active workout import atomically tombstones any prior active
root and publishes ordered DELETE then UPSERT; finished import leaves the active workout alone. Both kinds use the
existing account/session-fenced transaction, savepoint, original-result replay, revision and batch path.

## Slice 5g: acceptance and integrity-failure recovery

Worker narrowly changed the executor's error classification: unexpected database integrity failures propagate as
sanitized top-level failures and roll back all writes/evidence, allowing unchanged retry after fault removal. Only
recognized reservation/reference domain failures become durable rejection outcomes, including explicit adapter
collisions and both root/child reservation constraint signatures. QA independently reproduced/fixed-verified unknown
post-write CHECK faults for all three copy commands and the root-versus-child collision classification regression. No
migration, resource endpoint or production test seam was added.

QA added ten HTTP/PostgreSQL cases for integrated import/copy/autosave/delete, optional/null/zero fidelity, immutable
original labels/results after rename/edits, ordered clone bindings and persistent identity boundaries,
duplicate/distinct-copy concurrency, all-three-copy rollback/retry, and accepted/rejected outcomes through an actual
second service JVM. Coordinator acceptance is recorded in `docs/task-5g-acceptance.md`; detailed AC/all-17 coverage and
limits are in `docs/task-5g-qa.md`.

## Task 6 integration: shared domain boundary, separate replay

Task 6 is accepted for 14 direct mutable resource operations (`docs/task-6-acceptance.md`). The sync wrapper still owns
exact mutation-object replay and sync evidence; `ResourceMutationExecutor` owns resource method/route/full-body identity
and original HTTP responses in its separate V6 table. Both use the authorized account transaction and the same domain
application/publication algorithms. The common result is explicitly accepted or rejected; no nullable resource mode or
resource-specific evidence branch remains in the legacy command body. Its broader complexity is recorded as debt in
`docs/technical-debt.md`, not authorization to redesign it during unrelated work.

After that bounded cleanup, independent QA passed 116 targeted and 137 full tests, including all 65 sync HTTP and 24
preflight cases; Coordinator passed five risk-based direct HTTP/PostgreSQL spot checks on identical inputs. These newer
artifacts and exact commands are in the task-6 reports. The task-5g figures below remain historical acceptance evidence,
not the current full-suite count. Direct resource boundaries and retry rules are documented in
`docs/resource-mutations-implementation.md`.

## Verification and limits

Final independent QA `./mvnw -q -Dtest=RoutineUpsertHttpIntegrationTest,PushPreflightTest test` passed 89 tests (65 HTTP
+24 preflight); full `./mvnw -q test` passed 122 tests, 0 failures/errors/skips, against isolated Testcontainers
PostgreSQL 16. Worker production package and targeted checks passed; Coordinator independently passed five selected
HTTP/DB regression/concurrency/restart tests on the identical final source/test hashes. The suite logs auth-cleanup
connection noise after a disposable database stops. HTTP/PostgreSQL coverage includes history tie/fallback, repeated
exercises, stale shape, reservations, invalid references, target ownership/state, rejected-command no-effects, replay
after newer history, concurrency, second-process replay, and injected rollback faults. All 17 contract mutation kinds
are implemented. Slice 5f coverage now includes import fidelity for finished workout children, omitted/null finish
timestamps, active-import DELETE-then-UPSERT ordering, blank saved routine import, custom-exercise in-use checks against
active and finished workouts plus draft and saved routines, dependency ordering, deleted/foreign references, root/child
reservation collisions, account ownership/isolation, bundled read-only deletion, tombstone re-delete without changing
deleted_at, second-process replay of accepted/rejected outcomes, injected post-write rollback and concurrent create-only
collisions. 5g added representative optional custom metadata/null cases and cross-kind/concurrent-copy coverage; it does
not claim every metadata/state/fault/concurrency combination. The separate routine-root-versus-child collision
permutation is not independently instantiated, although the common reservation trigger/classifier was inspected and
family independence/routine paths are covered. No blocking bounded 5g finding remains; task 5 is accepted for the
implemented push surface. Pull, 90-day pruning and frozen snapshots remain later work. The authoritative behavior
remains `shared-docs/docs/backend-contract/README.md` and linked contract documents; this file records implementation
status only.
