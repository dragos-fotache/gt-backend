# Task 5g — approved bounded sync-push acceptance

## Authority and starting state

User approved execution of 5g in the current Coordinator conversation: cross-kind sync-push acceptance/coverage,
production fixes only for demonstrated contract defects. User specifically requests Worker and QA concurrent work when
safe, using the existing Luna High agents. Coordinator owns decomposition, resolution and final acceptance; Worker owns
production; QA owns test design/code and independent verification. This document operationalizes the approved slice, not
a new behavioral specification.

Repository/cwd: `/home/dragos/IdeaProjects/gt-backend`. Java 27, Spring Boot 4.1.1, Maven wrapper, Docker/Testcontainers
PostgreSQL 16. Live agents at assignment: Coordinator `w4:p7`, QA `w4:pA`, Worker `w4:pC`, tab `w4:t1`. Both agents
already received their role prompts; comply with them. No AGENTS.md was found in the repository or checked parent
directories.

Backend has no commits/tracked files/remotes; everything is untracked. Git diff cannot show pre-task changes. Baseline
copied to `/tmp/gt-backend-5g-baseline-BUCeOL` (src, docs, pom, README, wrapper/config), including `src.sha256`; use
`diff -ru` and hashes against this baseline. `shared-docs/` is a separate clean checkout at
`3aefeb3e2eaa147793d595833217d6070eeabff2`, never a submodule.

Starting implementation supports all 17 contract mutation kinds through `POST /v1/sync/push`. Prior reported suite: 112
tests, targeted sync HTTP class 55 tests; historical results are not evidence for 5g. Task 5 is not yet signed off.
Source integration boundary: `sync/PushPreflight.java`, `sync/SyncPushController.java`,
`sync/RoutineUpsertExecutor.java`, `persistence/CanonicalAggregateRepository.java`,
`persistence/AccountWriteRepository.java`, `auth/SessionAuthorization.java`, domain aggregate/validator classes and
Flyway V1–V5. Existing `src/test/java/com/dragosfotache/gtbackend/sync/RoutineUpsertHttpIntegrationTest.java` contains
real HTTP, process restart, concurrency, SQL-fault and fixture helpers; choose an appropriate test organization without
broad unrelated refactoring.

## Required references

Read completely: `README.md`, `docs/coordinator-handoff.md`, `docs/sync-push-implementation.md`,
`docs/canonical-aggregate-foundation.md`; authoritative `shared-docs/docs/backend-contract/README.md`,
`service-protocol.md`, `android-client-protocol.md`, `domain-rules.md`, `sync.md`, `acceptance-scenarios.md`, and
`openapi.yaml` (especially all mutation/input/outcome/batch schemas). Consult linked compatibility/schema/auth documents
for relevant issues; no Android source required. Shared contract wins over local implementation notes; report material
contradictions rather than silently picking behavior.

## Objective and acceptance criteria fixed before implementation

Provide evidence-based acceptance of the existing sync-push implementation across interacting commands, filling
high-risk coverage gaps. Existing adequate tests may satisfy a criterion; do not duplicate them just to raise test
counts. Map each criterion to exact tests/checks and explicitly record gaps. Tests derive expected results from
contract/fixtures, never the implementation's returned result as its own oracle.

- **AC1 Ordered cross-kind lifecycle:** real HTTP flows combine dependency-ordered custom import/upsert, workout/routine
  import, complete autosave, applicable copy commands, and deletes. Assert one outcome per mutation in submitted order,
  continuation after domain rejection, earlier committed effects retained, and later commands seeing accepted
  predecessors. Include at least one rich lifecycle combining all three copy kinds (`workout.performAgain`,
  `routine.startWorkout`, `workout.saveAsRoutine`) with imported/saved sources and later edits/deletes. Full canonical
  fields, stable supplied child IDs, null/zero distinctions, configured vs recorded rest, completion clearing, source
  immutability, positive revisions and complete batches must agree with requirements. Active replacement is a single
  batch ordered DELETE old then UPSERT new; finished imports/save-as-routine do not disturb active state.
- **AC2 Durable original outcomes across changed state:** accepted/rejected copy/import/delete results replay exactly
  (apart from status becoming duplicate) after later autosave/source deletion/custom rename/state eligibility change,
  without reexecution or displacement of later winners. Frozen convenience labels remain original while fresh canonical
  materialization uses current names, without fan-out root revisions. Account/mutation exact-byte identity includes
  internal formatting but excludes outer whitespace; changed identity cannot overwrite stored evidence. Use existing
  adequate identity tests plus new cross-kind scenarios as needed.
- **AC3 Ownership and persistent identity boundaries:** applicable copies/imports cannot read/claim foreign or
  tombstoned roots, references or reserved target IDs. Test high-risk untested family/role/root/parent permutations,
  including removed child restored by complete autosave only to its original parent versus semantic create/clone
  requiring fresh IDs; workout/routine families remain independent. Invalid source bindings (missing/extra/reordered)
  are `source_shape_changed`, reserved targets `id_collision`; choose unambiguous single-failure fixtures rather than
  inventing error precedence. Rejections leave roots/children/active state/revisions/batches/cursor/reservations
  unchanged except required original rejection evidence. No foreign data in outcomes.
- **AC4 Import fidelity and dependency deletion:** cover representative custom optional metadata/null combinations
  (omitted vs explicit null, instructions null vs empty, selected equipment quantity null vs zero, populated optional
  metadata and relationship replacement), not an exhaustive Cartesian product. Preserve saved/draft and valid finished
  import snapshots. Custom deletion remains blocked by live references including history/drafts, becomes allowed after
  referencing-root deletion, and tombstone retry/re-delete semantics remain intact. Existing 5f coverage may satisfy
  most of this.
- **AC5 Copy transaction rollback:** each of the three copy commands has real HTTP/isolated PostgreSQL evidence for an
  injected post-write constraint failure with no partial source/target/active/revision/reservation/cursor/batch/replay
  effects. Check original request can execute once the injected transient test fault is removed. Sanitize server errors;
  do not convert unexpected infrastructure faults into durable domain rejections. Existing fault tests can be reused if
  they exercise that command's path. Prefer database test injection; route any necessary production seam through
  Coordinator to Worker.
- **AC6 Selected concurrency:** real concurrent same-account copy/start transactions and concurrent identical copy
  request (s) demonstrate serialized complete commits, sole-active invariant, one execution for duplicate IDs and
  unchanged sources. Cross-account dedup/ownership isolation remains covered. Do not infer commit order from request
  launch times or compare opaque cursors lexically; use persisted committed boundaries/gates. No need for every pair of
  mutation kinds.
- **AC7 Restart:** real second JVM HTTP service using the same isolated database verifies original accepted and rejected
  outcomes for the three copy kinds across subsequent source/state changes; one combined restart scenario may cover them
  all. No in-process executor replacement alone as restart evidence. Terminate only processes created by the test and
  keep logs sanitized.
- **AC8 Integrated regression and scope:** QA independently checks the final stable combined production/tests state with
  targeted sync checks and full `./mvnw -q test`, reporting counts/failures/errors/skips and a manifest/fingerprint
  identifying state before/after. Account for all 17 mutation kinds using adequate existing/new tests and identify any
  material uncovered task-5 behavior; no requirement may be declared verified on historical results alone. Worker builds
  and runs relevant existing checks before handoff if production changes occur. No unrelated product expansion, contract
  edits, weakened tests, silently introduced limits, or protected-resource alteration.

Contractual failure codes and distinctions are in service-protocol/domain/client docs. Whole-request structural failure
precedes *all* writes; domain rejection is per-mutation; an unexpected top-level failure may leave a committed prefix,
so unchanged retries recover it. Mutations/locks/domain/revision/complete batch/replay must remain atomic and
session-authorized through commit. Replays precede current eligibility checks after preflight/auth.

## Scope and ownership

No new mutation kinds, direct resource routes/idempotency, reads/list endpoints, pull/pruning/snapshots, account
lifecycle/purge, deployment/OAuth/Android integration, broad redesign/refactoring, or generic logging/scheduler cleanup.
Existing post-container auth-cleanup connection noise is an environmental/lifecycle follow-up, not license for unrelated
fixes. A discovered requirement contradiction or scope-changing issue goes to Coordinator.

- Worker: `src/main/**` only for narrow proven production defects (applied migrations excluded);
  `docs/task-5g-worker.md` for findings/handoff. Do not author/change `src/test/**` or test fixtures/config/build files.
  Report evidence-backed production defects discovered during inspection; do not merely speculate or redesign. No
  production changes required if no defects exist.
- QA: `src/test/**` and `docs/task-5g-qa.md` only. Own test architecture/design and test-local fixtures/gates/faults. Do
  not modify production, migrations, shared docs, pom or local implementation/status docs. Report production seams
  needed to Coordinator.
- Coordinator: this contract, checkpoint, README/local implementation notes/handoff updates; inspect integration and
  decide acceptance. No production or test authoring by Coordinator.

Both agents share one working tree. Concurrent reading, Worker production edits and QA test design/editing are
encouraged under explicit ownership. Do not edit the other role's files. Before a check, freeze relevant source/test
edits; do not run tests while relevant implementation changes. Maven shares target/reports: **one build/test command at
a time**, serialized with `flock /tmp/gt-backend-5g-build.lock ./mvnw ...`. QA owns the initial verification slot and
may start targeted checks once Worker confirms no edits/informs a stable snapshot; Worker initially inspects in
parallel, reports findings, and requests the slot through Coordinator. A lock prevents competing Maven commands, not
changing-source verification; communicate edit freeze/handoff explicitly. Final tests cover an identified stable
combined state. No overlapping competing fault operations; use only test-owned disposable resources and clean
faults/processes in finally blocks.

## Safety and verification resources

Allowed: isolated Testcontainers PostgreSQL 16, pinned catalogue resources from unchanged shared-docs, synthetic service
sessions and mocked external Google only as existing tests do, temporary files under /tmp, test-created child JVMs on
random local ports. Docker available (29.8.1); Java 27 present. Run checks from repo root without sourcing local
secrets. Typical checks: `flock /tmp/gt-backend-5g-build.lock ./mvnw -q -DskipTests package`,
`flock /tmp/gt-backend-5g-build.lock ./mvnw -q -Dtest=RoutineUpsertHttpIntegrationTest,PushPreflightTest[,new-class] test`,
final `flock /tmp/gt-backend-5g-build.lock ./mvnw -q test`. Preserve detailed output in /tmp if useful; reports may
contain only test-generated credentials, never real secrets; do not print credentials in handoffs.

Preserve local `gymtracka` PostgreSQL data/schema, `~/.config/gymtracka-backend/dev.env` (do not
read/source/print/copy/rotate), independent shared-docs contents/Git state, user panes/agents/layout, existing working
files and all applied Flyway V1–V5. No staging/commits, destructive local DB checks, stopping existing services, agent
termination/reset, or schema changes without Coordinator approval. Any schema fix would be forward-only new migration,
separately discussed first.

## Required handoffs and current status

Worker handoff: changed files/significant choices; confirmed defects and requirement references; exact commands/results;
baseline-relative production diff; assumptions/open issues; ready/stable state with source hashes. QA handoff:
per-AC/all-17 coverage matrix; new/changed tests and independent expected behavior; reproducible findings with
expected/observed/requirement; exact commands/counts; failures/skips/gaps; precise tested working-tree hashes
before/after; readiness for acceptance (Coordinator decides). Send focused findings as soon as confirmed so production
fixes can overlap continued test authoring.

Status at assignment was approved/in progress; Worker implementation and QA test authoring proceeded concurrently under
ownership and build freezes. **Final status: accepted by Coordinator**, with task-5 push signed off within this bounded
contract. Worker production package/targeted checks, independent QA targeted (89 tests)/full (122 tests), and
Coordinator five-test HTTP/DB spot check passed on identical final build inputs, zero failures/errors/skips. See
`docs/task-5g-acceptance.md` and `docs/task-5g-qa.md` for evidence, explicit nonblocking symmetry coverage gap and
safety audit. No task 6 authorization.
