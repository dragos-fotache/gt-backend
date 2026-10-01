# Task 5g — Coordinator acceptance

**Status: accepted. Task 5 (implemented sync push) is signed off within the task-5g contract boundary. Task 6 is not
authorized.** This is acceptance of the implemented push surface, not the complete v1 API, deployment, or exhaustive
mutation/state combinations.

## Delivered and reviewed

- Worker changed only `src/main/java/com/dragosfotache/gtbackend/sync/RoutineUpsertExecutor.java`: unexpected integrity
  failures abort atomically without poisoning durable mutation replay. Only recognized identity-reservation/reference
  domain failures retain contractual rejection codes. PostgreSQL primary diagnostic matching ignores trailing context.
  The explicit adapter collision and both root/child reservation constraint signatures remain recognized.
- QA added **10 real HTTP/PostgreSQL integration cases** in
  `src/test/java/com/dragosfotache/gtbackend/sync/RoutineUpsertHttpIntegrationTest.java`: integrated
  import/copy/autosave/deletion; custom optional/null/zero fidelity; original labels/results through rename and later
  edits; binding/removed-child/family/parent reservations; root-versus-child collisions; duplicate and distinct-copy
  concurrency; all-three-copy post-write rollback/retry; and accepted/rejected replay in a second JVM for all three
  copies.
- F1 (unexpected CHECK fault durably rejected instead of retryable server failure) reproduced on baseline for all three
  copy kinds, fixed by Worker and independently verified by QA and Coordinator. F2 (recognized root-versus-child
  collision accidentally classified as server failure during the fix) independently reproduced, corrected narrowly, and
  independently verified. QA fixture/binding mistakes were corrected by QA rather than altering production behavior or
  weakening requirements.
- Coordinator inspected complete baseline-relative production/test changes and AC1–AC8 evidence. Existing tests supply
  sufficient per-kind evidence for all 17 mutations; no historical test result was used as final verification.
  Separation was preserved: Coordinator authored neither production nor tests; Worker and QA worked concurrently under
  separate ownership, with explicit source/test freezes and serialized builds.

## Verification on final integrated state

- Worker: `flock /tmp/gt-backend-5g-build.lock ./mvnw -q -Dmaven.test.skip=true package` passed; final targeted
  sync/preflight run passed **89 tests**, 0 failures/errors/skips.
- Independent QA:
  `flock /tmp/gt-backend-5g-build.lock ./mvnw -q -Dtest=RoutineUpsertHttpIntegrationTest,PushPreflightTest test` passed
  **89 tests** (65 HTTP + 24 preflight); `flock /tmp/gt-backend-5g-build.lock ./mvnw -q test` passed **122 tests**, 0
  failures/errors/skips. Seven suites; isolated Testcontainers PostgreSQL 16, Flyway V1–V5.
- Coordinator independently ran five selected HTTP/DB cases (F1, F2, frozen-label replay, distinct-copy concurrency,
  all-three-copy second-JVM replay): **5 tests passed**, 0 failures/errors/skips. Exact selector and reports:
  `/tmp/gt-backend-5g-coordinator-spot.log`, `/tmp/gt-backend-5g-coordinator-spot-reports/`.
- Production executor SHA-256: `02b9018484ff6b4b6811817195a2f26ace7c4cc1c599f413db01bf5d90399a95`. HTTP test SHA-256:
  `b90e370f1f8066c63543da4fbaf98659a6bd7136346bac00c1125f2cfddc01ea`. QA build-input manifests matched before/after;
  Coordinator checked the same manifest successfully before/after spot checks. Final documentation updates do not alter
  build inputs.
- Durable role/evidence reports: `docs/task-5g-contract.md`, `docs/task-5g-worker.md`, `docs/task-5g-qa.md` (AC/all-17
  matrix and exact evidence paths). Full/targeted XML reports preserved in `/tmp/gt-backend-5g-qa-full-reports/` and
  `/tmp/gt-backend-5g-qa-targeted-reports/`; current `target/surefire-reports/` can be overwritten by subsequent checks.

## Gaps, safety and next action

No blocking production finding or unmet bounded acceptance criterion remains. QA explicitly noted no separate
routine-root-versus-child collision instantiation. Coordinator treats this as nonblocking selected-coverage symmetry,
not verified routine-specific test evidence: both families use the inspected common V3 `reserve_root_identity` trigger,
the same `child_id_reservations_pkey`, and the same classifier; workout/routine namespace independence and routine
copy/import paths are separately covered. No behavior or guarantee is waived. Representative metadata/fault/concurrency
cases are not exhaustive Cartesian-product testing.

Known asynchronous auth-cleanup connection-refused noise after disposable-container teardown remains an unrelated
lifecycle follow-up; all checks passed. Android integration, deployed HTTPS/OAuth/release configuration, local V5
application and later direct-resource/read/pull/pruning/snapshot/account-lifecycle/operational work remain
unexecuted/out of scope.

Migrations/resources/build files unchanged. Independent `shared-docs/` remains clean at
`3aefeb3e2eaa147793d595833217d6070eeabff2`. Local gymtracka data and secret file were not accessed/altered. No
staging/commit, new migration, new endpoint, pane/layout/agent replacement or real deployment occurred. Baseline remains
`/tmp/gt-backend-5g-baseline-BUCeOL` because backend files are untracked.

**Next action:** report acceptance to user, then obtain authorization for task 6 (direct resource/command endpoints with
separate resource idempotency). Existing Worker/QA remain available; no later implementation begins automatically.
