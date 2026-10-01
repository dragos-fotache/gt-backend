# Task 6 — direct mutable resources and resource idempotency

**Status: accepted.** Final Coordinator review: `docs/task-6-acceptance.md`. Post-cleanup QA passed 116 targeted and 137
full tests; Coordinator passed five spot checks on identical inputs. AC6.7 structural review passed. This file records
the approved assignment, not permission to continue work after acceptance; agents await a new assignment. No task 7
authorization.

## Approval, objective and starting state

User explicitly authorized task 6 after reviewing its summary and requesting fresh Worker/QA contexts. Implement the
**14 mutable operations** listed below, with separate account-scoped resource idempotency, shared atomic domain/sync
publication, and independent real HTTP/PostgreSQL verification. Do not start tasks 7–10. This assignment operationalizes
the shared v1 contract; it does not replace it.

Cwd/repository: `/home/dragos/IdeaProjects/gt-backend`. Java 27, Spring Boot 4.1.1, Maven wrapper, PostgreSQL/Flyway;
Docker 29.8.1 available. No AGENTS.md found in repository/checked parents. Backend has no commits/tracked files/remotes;
files untracked, so empty git diff is not evidence. Starting baseline copied to `/tmp/gt-backend-task6-baseline-SjcEZw`
(src/docs/README/build inputs, src.sha256). Separate `shared-docs/` checkout is clean at
`3aefeb3e2eaa147793d595833217d6070eeabff2`.

Task 5 push is accepted through bounded 5g: all 17 sync kinds, durable exact-byte replay, account/session write fencing,
canonical root revisions/batches and opaque cursors. Baseline executor SHA256
`02b9018484ff6b4b6811817195a2f26ace7c4cc1c599f413db01bf5d90399a95`, sync HTTP test
`b90e370f1f8066c63543da4fbaf98659a6bd7136346bac00c1125f2cfddc01ea`; last independently verified full suite 122 tests.
This is starting context, not task-6 evidence. No direct domain routes/resource replay exist yet. V3 processed_mutations
PK is (owner_id,mutation_id), V5 adds request bytes; that existing sync identity cannot silently become a shared
resource namespace.

Fresh Luna High team: Coordinator `w4:p7`; Worker `backend_worker` / `w4:pC`; QA `backend_qa` / `w4:pA`. Their role-only
wait instruction is now superseded by THIS assigned task. Preserve role prompts, existing panes and cwd.

## Required reading and integration boundaries

Read completely: this file; `README.md`; `docs/coordinator-handoff.md`; `docs/sync-push-implementation.md`;
`docs/canonical-aggregate-foundation.md`; authoritative `shared-docs/docs/backend-contract/README.md`,
`service-protocol.md`, `domain-rules.md`, `android-client-protocol.md`, `client-compatibility.md`, `authentication.md`,
`acceptance-scenarios.md`, `openapi.yaml`. Read relevant schema/retention references for new replay storage; account
lifecycle is context for future purge obligations, not task-6 implementation. Report contradictions explicitly; no
Android Kotlin required.

Code: `sync/PushPreflight.java`, `sync/SyncPushController.java`, `sync/RoutineUpsertExecutor.java`;
`persistence/CanonicalAggregateRepository.java`, `persistence/AccountWriteRepository.java`; domain validator/aggregate
types; `auth/SessionAuthorization.java`, `AuthErrors.java`, `AuthController.java`, `AuthService.java`; migrations V1–V5.
Existing sync HTTP tests provide fixture/SQL-fault/concurrency/second-JVM harness patterns; auth HTTP tests cover
session fencing. QA derives oracles from requirements before examining production details.

## Route scope — exact OpenAPI operations

All require live bearer authorization and `Idempotency-Key` (canonical UUID). Successful bodies are canonical
**resources**, not sync outcome/batch envelopes. DELETE success is bodyless 204. Source IDs for copies come only from
the path; new target/children only from body. No added child-command routes exist in current OpenAPI.

| Method / concrete route                         | Body / behavior                                                                                                                                          | Success      |
|-------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------|--------------|
| POST `/v1/exercises`                            | CustomExerciseInput; create a fresh private reps root only                                                                                               | 201 Exercise |
| PUT `/v1/exercises/{exerciseId}`                | Complete CustomExerciseInput, matching path ID; replace existing owned/live custom only                                                                  | 200 Exercise |
| DELETE `/v1/exercises/{exerciseId}`             | Delete owned custom; reject bundled/read-only or live-reference use                                                                                      | 204          |
| POST `/v1/routines`                             | `{id}` only; create exactly name empty, isDraft true, exercises empty                                                                                    | 201 Routine  |
| PUT `/v1/routines/{routineId}`                  | Complete RoutineInput matching path; existing owned/live only, cannot promote/demote                                                                     | 200 Routine  |
| DELETE `/v1/routines/{routineId}`               | Delete owned draft/saved routine                                                                                                                         | 204          |
| POST `/v1/routines/{routineId}/save`            | `{name}`; eligibility validation/promotion or saved validation/update                                                                                    | 200 Routine  |
| POST `/v1/routines/{routineId}/start-workout`   | `{id,startedAt,childIds}`; saved eligible source, copy to fresh active target                                                                            | 201 Workout  |
| POST `/v1/workouts`                             | `{id,name,startedAt}`; start fresh empty active root, replace prior active atomically                                                                    | 201 Workout  |
| PUT `/v1/workouts/{workoutId}`                  | Complete WorkoutInput matching path; existing owned active only                                                                                          | 200 Workout  |
| DELETE `/v1/workouts/{workoutId}`               | Discard active or delete finished owned root                                                                                                             | 204          |
| POST `/v1/workouts/{workoutId}/finish`          | `{finishedAt}`; require completed set, prune/renumber then finish atomically                                                                             | 200 Workout  |
| POST `/v1/workouts/{workoutId}/perform-again`   | `{id,startedAt,childIds}`; finished source, fresh target, preserve template/notes/rest, clear completion/recorded rest, replace prior active             | 201 Workout  |
| POST `/v1/workouts/{workoutId}/save-as-routine` | `{id,name,childIds}`; finished source/nonblank name, create fresh saved routine template without notes/completion/recorded rest; source/active unchanged | 201 Routine  |

Do not expose GET/list/catalogue/private reads (task 7), pull/pruning/snapshots (8), export/status/account
deletion/purge (9), deployment/rate thresholds/backups/general scheduler cleanup (10). No direct import endpoint, new
mutation kind, new product cap or invented child routes. DELETE tombstone effects/re-delete with a new key follow the
existing aggregate-delete shared rules; missing/foreign roots remain not_found. PUT never implicitly creates/resurrects.
New-ID collisions (including catalogue namespace on custom creation) are 409 id_collision; mutation of bundled exercise
via PUT/DELETE is 403 read_only. Missing/deleted/foreign existing targets/sources use 404 not_found without disclosing
why.

## Fixed acceptance criteria

**AC6.1 Route and domain correctness.** Account for every table row with real HTTP positive response and applicable
failure coverage. Validate supplied IDs, canonical fields/revisions/labels/null/zero/default distinctions, full
replacement/child ordering, snapshot fidelity and preserved domain semantics. Inputs contain no
owner/credentials/revision/display/volume fields; invalid fields/type coercion/canonical UUIDs/timestamps/numeric/shape
input reject. Path/payload root mismatch is 422 validation_error; copy source supplied in body is unrepresented and not
accepted. Empty drafts and legitimate blank/empty saved autosaves remain valid; creation/promotion/demotion policy is
route-specific. Finish/copy/source-shape and reservation guards remain intact. Each successful resource operation emits
the same domain changes/complete batch as its semantic sync equivalent (independently expected fixtures, not comparing
two production functions as the only oracle). Multi-root replacement is one ordered DELETE-old then UPSERT-new batch.
Rejects do not alter roots/children/active/revisions/reservations/cursor/batches.

**AC6.2 Resource identity/replay.** Namespace resource, key (authenticated account UUID, supplied Idempotency-Key);
sync/account-auth namespaces remain separate. Compare HTTP method + normalized concrete route (canonical lowercase UUID
path params) + **exact UTF-8 raw request-body bytes**, including body-internal and surrounding whitespace/key
order/number spelling. Never compare a serialization, DTO, generated internal sync envelope or complete outer request.
Absent body is empty bytes, not synthesized `{}`. Identical structurally valid authenticated retries return original
HTTP status and original response body (including 201/200/204 and original domain-rejection status/code), after later
edits/rename/delete/eligibility changes and process restart, without domain/revision/batch changes. Same key/different
method/route/body returns409 idempotency_key_reused and leaves original evidence intact. Store original convenience
labels; do not freshly materialize replay from current state. Response must not expose replay internals/foreign data.
Replayed auth still checks live session/account; key alone never authorizes. Original outcomes/evidence retained account
lifetime independently of sync-log retention, subject to future whole-account purge; no TTL/scheduler forgetting keys or
FK dependency on retained batches.

**AC6.3 Validation and non-durable failures.** Authorization and complete direct structural preflight apply before
executing/replaying domain work. Malformed UTF-8/JSON/duplicate keys/nonfinite JSON use400 invalid_request; structurally
invalid shape/unknown fields/value formats use422 validation_error. Reject unsupported mutation query syntax rather than
ignoring it (400 invalid_request). Missing Idempotency-Key follows current required-header400 invalid_request;
malformed/noncanonical UUID key/path shape uses422 validation_error, not generic500. These header details are
Coordinator operational choices consistent with existing header/UUID conventions, not a new domain policy. Bodyless
DELETE: required supported form is absent body, preserved as zero bytes; if implementation accepts an empty JSON object
as an equivalent empty semantic body, it MUST preserve its distinct bytes for replay. Do not require `{}` or silently
synthesize it for an absent body. **Initial Coordinator decision (before verification):** accept only absent/zero-byte
DELETE body; a nonempty body (including `{}`) is unsupported shape,422 validation_error before replay/execution and does
not claim the key. This matches OpenAPI's bodyless DELETE surface; body-bearing routes retain strict malformed-JSON400
handling. QA must not assert nonempty DELETE is a valid identity-conflict case; verify zero stored identity bytes and
original204 replay instead. Missing/invalid bearer401, malformed/structurally invalid input, throttling and
unexpected/transient service failures do not claim/replace a durable key. Domain rejections after structural preflight
DO persist original status/body, including not_found, read_only, id_collision,
invalid_state/source_shape_changed/exercise_in_use and valid-shape validation_error/invalid_reference. Use shared
error/status table, safe `{errorCode,message?}`, no diagnostics/SQL/credentials. Unknown integrity/constraint faults
must not become permanent domain rejections (preserve 5g F1/F2 behavior); recover unchanged request after test fault
removal. Do not invent additional error-precedence guarantees for multiple invalidities.

**AC6.4 Atomic common domain boundary and auth.** Resource mutation, per-root revisions, complete canonical sync
publication, original status/body replay evidence all commit in ONE transaction with account serialization and
live-session fencing through commit. Domain rejections persist evidence but roll back all domain/log/reservation
effects; auth/structural/server failures produce no replay/domain effects. Share existing accepted domain execution and
publication with sync, not two drifting command engines or direct exposure of the low-level row adapter. Resource
create/update scope checks occur inside that transaction without widening sync upsert semantics. No synthetic resource
calls pollute sync processed_mutations or consume client sync IDs. Extracting a focused common command
boundary/validation reuse as necessary is allowed; broad unrelated rename/redesign/refactoring is not. Maintain current
sync exact-object-byte identity, preflight/ordered continuation, all17 commands and 5g fixes.

**AC6.5 Concurrency and durable recovery.** Independently verify real concurrent identical direct requests execute
once/replay original response; distinct direct+direct and direct+sync writes serialize same-account domain state without
lost whole-root edits or multiple active workouts. No assumption that launch/device-clock order is commit order, no
lexical cursor ordering. Verify account isolation/same key in different accounts and same UUID across resource/sync
namespaces as genuinely independent operations; switching surfaces never recovers an uncertain operation. Test real
second JVM replay (not just service-object replacement) for representative created201, updated/command200, deleted204
and domain rejection after later state changes. At least one injected post-write database fault verifies total rollback
including replay/publication/reservations and unchanged retry; cover multi-root replacement and replay-write/publication
late failure risk proportionately using test-owned SQL triggers/gates. Verify protected direct authorization fails after
accepted logout/session revocation; reuse shared fence coverage plus direct gated race where needed to demonstrate the
new surface participates.

**AC6.6 Integrated verification/scope.** QA independently checks final stable combined state with targeted
new-resource + existing sync/preflight/auth coverage and full `./mvnw -q test`; maps all14 routes/AC6.1–6.6 to executed
evidence or explicitly reported gaps. Worker inspects/builds and runs relevant existing checks before handoff. Record
exact commands/counts/failures/errors/skips and source/test/resource/build-input hashes before/after. New schema must be
forward-only (V6 or later as needed); V1–V5, shared-docs, local data/secrets/build config/layout unchanged unless
explicitly authorized. Inspect baseline-relative final diff, not an empty git diff. No claim of
complete-v1/release/OAuth/Android/deployment verification.

**AC6.7 Maintainability and readability (user requirement added before acceptance).** Review changed production paths
structurally, not only through tests. Require small cohesive methods, explicit domain/result types where they clarify
behavior, meaningful names, practical separation of validation/transformation/persistence/orchestration/response
construction, and removal of obvious local duplication. Avoid materially enlarging existing complex methods with command
checks, mode flags, nullable coordination state, or deep nesting. Avoid speculative abstractions and framework-style
indirection. Ask whether the feature enlarged an already-large method, added implicit coordination, mixed unrelated
responsibilities, duplicated logic, or made the new path impossible to understand in isolation. Return meaningful
deterioration to Worker for reasonably local cleanup and repeat verification afterward. Do not broadly refactor the
existing mutation `execute(...)` god method or unrelated legacy code; record larger problems as technical debt for a
dedicated task. This criterion does not relax any functional, contract, or safety requirement.

## Work organization and ownership

This is one substantial task with three coherent implementation milestones: (A) common execution/resource replay
boundary and exercise routes; (B) routine routes; (C) workout routes plus integration acceptance. Worker chooses routine
implementation order within these milestones; no need for user approval between them because task6 is authorized. Prefer
shared architecture first rather than duplicating engines per family. Notify Coordinator of the initial focused
approach/new migration and chosen DELETE-body policy; report material contradictions/changes in scope before proceeding.
QA can design/write coverage for all milestones while production progresses. Coordinator reviews integration and fixes
at stable handoffs, not every trivial edit.

- Worker owns `src/main/**`, including **new forward-only replay migration (s)** if needed (authorized for isolated
  development/test application ONLY, never local gymtracka); `docs/task-6-worker.md` for design/findings/handoff. No
  `src/test/**`/fixtures/test config changes, no shared-docs/pom/wrapper edits. If any build dependency change is
  necessary ask Coordinator first.
- QA owns `src/test/**` and `docs/task-6-qa.md`. Prefer a dedicated direct-resource HTTP integration class instead of
  adding another huge block to the existing sync class; reuse patterns without broad test refactoring. No
  production/migration/shared-docs/build/status-doc edits. Route needed seams/production findings through Coordinator;
  keep test scenarios proportionate and contract-derived.
- Coordinator owns this contract/checkpoint/README/local status/acceptance docs; neither production nor test authoring.
  Applies approved plan, resolves findings and decides acceptance.

Both agents share working tree; concurrent Worker production edits and QA test authoring encouraged under disjoint
ownership. Builds share `target`/reports: serialize every Maven operation using
`flock /tmp/gt-backend-task6-build.lock ./mvnw ...`. **Relevant source and tests must also be frozen for any check.**
Worker may run a frozen-production build-only `-Dmaven.test.skip=true package` while QA edits tests (no test
compilation), announcing it; check slots involving test compilation/execution need Coordinator agreement and QA test
freeze. QA may plan/code while Worker works; do not treat a green run against changing code as final evidence. Keep
fault tests isolated, no competing schema/test processes; clean only test-created faults/processes.

Handoffs: Worker files/decisions/exact commands/outcomes/baseline diff/hashes/stable state/failing or unexecuted
checks/open issues; QA requirement-first initial matrix, prompt confirmed reproducible findings, final route/AC coverage
matrix/commands/counts/before-after hashes/gaps and independently verified fixes. Neither role claims final acceptance.
Preserve original tests while fixing production; route unsupported tests to QA.

## Environment and protected resources

Use Maven wrapper with Docker/Testcontainers PostgreSQL16, pinned catalogue resources unchanged, synthetic
accounts/sessions and mocked external Google only as existing tests do. Real HTTP localhost/random-port test JVMs and
/tmp are approved; terminate only test-created child processes. Do not source any local secrets for tests.

Never read/print/copy/source/overwrite/rotate `~/.config/gymtracka-backend/dev.env` or expose tokens; never
reset/destructively test local gymtracka data/schema or stop existing services. Preserve applied FlywayV1–V5 and
independent shared-docs contents/Git state; no staging/commit, submodule conversion, layout changes/agent
termination/reset or production deployment. V6 is a task6 forward-only addition, NOT permission to apply it to local
preserved DB. Known post-Testcontainers auth cleanup connection noise remains unrelated; no scheduler cleanup in task6.

## Checkpoint

Accepted after Worker implementation, independent requirement-first QA coverage, F6.1 recovery fix, bounded F6.2
maintainability cleanup, fresh post-cleanup verification and Coordinator review. Final artifacts and hashes are in the
acceptance/QA reports. Existing legacy command complexity is recorded in `docs/technical-debt.md`, not authorization to
refactor it during unrelated tasks. Worker and QA remain idle pending a new assignment. Task 7 is not authorized.
