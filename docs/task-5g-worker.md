# Task 5g Worker findings and handoff

## Inspection finding — database check failures can become durable domain rejections

**Confirmed production defect; narrow fix implemented after Coordinator authorization (QA baseline F1 reproduced all
three copy cases).**

`src/main/java/com/dragosfotache/gtbackend/sync/RoutineUpsertExecutor.java:303-315` catches every
`DataIntegrityViolationException` raised while persisting a mutation and maps SQLSTATE `23514` to `invalid_state` via
`sqlDomainCode` (`:714-724` in this file). The handler rolls back only to the command savepoint and then follows
`:317-320` to insert a durable rejection outcome for the original mutation bytes. A check-constraint failure is not
necessarily a domain rejection: a transient/injected post-write database constraint fault must abort the transaction,
not be frozen as a permanent rejection.

**Contract:** task-5g AC5 says injected post-write constraint failures for each copy command must leave no partial
effects and the original request must be executable once the transient test fault is removed. The task contract also
says unexpected infrastructure faults must not become durable domain rejections; service-protocol.md requires domain
rejections to record their effective result while unexpected top-level failures may leave a committed prefix and must be
recoverable by unchanged retry. In this implementation, a `23514` raised after writing a copy target causes rollback of
rows to the savepoint, followed by durable `invalid_state` evidence. Removing the test fault and retrying the same
mutation then replays `duplicate`/`invalid_state`; it cannot execute.

**Reproduction path:** submit a valid `workout.performAgain`, `routine.startWorkout`, or `workout.saveAsRoutine`
mutation whose persistence performs a write; install a test-only PostgreSQL trigger on the corresponding inserted
root/child table that raises a constraint/check error with SQLSTATE `23514`; the command handler catches it, returns
HTTP 200 with a rejected `invalid_state` result, and inserts `processed_mutations` evidence. Remove the trigger and
retry the identical bytes/ID: replay returns the stored rejection rather than executing. A test trigger raising `XX000`
does not expose this bug because it bypasses the SQLSTATE mapping and reaches the top-level sanitized 500 path.

**Fix:** `RoutineUpsertExecutor` no longer maps integrity SQLSTATEs wholesale or catches every `DuplicateKeyException`
as a collision. It maps the V3 root reservation unique constraint, child reservation PK, and custom
child-reservation-conflict primary signatures to `id_collision`; the explicit exercise-visibility guard primary
signature to `invalid_reference`; and the canonical adapter's exact `workout identity already reserved` exception to
`id_collision`. For PostgreSQL trigger errors it compares the exact primary diagnostic line (not trailing `Where`
context), together with SQLSTATE; it does not classify by SQLSTATE alone or broad diagnostic substring. Unrecognized
integrity errors return no domain code and are rethrown, allowing the transaction boundary to roll back all
writes/evidence and the HTTP layer to produce a sanitized top-level failure. This preserves recognized guard outcomes
without treating arbitrary unique/check/FK errors as permanent mutation results. No diagnostic is returned to clients.

**Second confirmed issue (F2):** QA's focused real HTTP/Testcontainers test
`workoutRootIdCannotClaimAnIdReservedForAnExistingWorkoutChild` confirmed a new workout root whose UUID had a prior
child-family reservation failed with sanitized HTTP 500 instead of rejected `id_collision`. V3 reserves a new
workout/routine root in `root_id_reservations`, then inserts its UUID in the shared `child_id_reservations`; a
pre-existing entry/set reservation raises native constraint `child_id_reservations_pkey`. The narrow classifier now
recognizes that exact PostgreSQL primary diagnostic under SQLSTATE 23505 for both workout and routine families. QA owns
strengthened no-effect/replay assertions; the final authorized Worker targeted run below passed F2.

## Authorized production change and stable-check handoff

- Changed only `src/main/java/com/dragosfotache/gtbackend/sync/RoutineUpsertExecutor.java`.
- Baseline-relative production diff: only that file differs
  (`diff -qr /tmp/gt-backend-5g-baseline-BUCeOL/src/main/java src/main/java`); no other production files changed.
- Current hashes: `RoutineUpsertExecutor.java` `02b9018484ff6b4b6811817195a2f26ace7c4cc1c599f413db01bf5d90399a95`;
  `CanonicalAggregateRepository.java` `b3ff5bf36871a5555a0e3725b65e7b23183ec41d1cf7f6a10e044b8341107998`;
  `PushPreflight.java` `f3f4e72d78af4417f68edb2a725608420c0c9cdccc6716cdd5f9ccfb491508bc`.
- Granted build-only command executed twice, before and after F2:
  `flock /tmp/gt-backend-5g-build.lock ./mvnw -q -Dmaven.test.skip=true package` — both runs **exit 0 / success**. The
  latest run was after the F2 classifier edit. Maven emitted Java/Jansi, Guice Unsafe, and reflective
  final-field-mutation warnings; no build failure. `maven.test.skip=true` deliberately skipped test
  compilation/execution while QA edits tests.
- Granted targeted test command executed:
  `flock /tmp/gt-backend-5g-build.lock ./mvnw -q -Dtest=RoutineUpsertHttpIntegrationTest,PushPreflightTest test` —
  **failed**: `RoutineUpsertHttpIntegrationTest` 61 tests, 1 failure, 0 errors, 0 skipped; `PushPreflightTest` 24 tests,
  0 failures/errors/skips. Combined: 85 run, 1 failure, 0 errors, 0 skipped. The F1 unknown-constraint regression cases
  passed in this run; the single failure is `concurrentIdenticalPerformAgainCreatesOneCopyAndReplaysSameCanonicalBatch`
  at test line 1677. The fixture helper `finishedWorkout(...)` calls `workoutUpsert(...)`, whose input payload hardcodes
  source name `"W"`; therefore actual copied name `W` matches source and contract, while the new assertion expects the
  helper's earlier start name `"Concurrent copy source"`. This is a QA test expectation/fixture mismatch, not a
  production defect; Worker did not change test files. Reports:
  `target/surefire-reports/TEST-com.dragosfotache.gtbackend.sync.RoutineUpsertHttpIntegrationTest.xml` and
  `...PushPreflightTest.xml`.
- Production executor hash during the test slot: `a3dad0189016efebd6f124a8a4b5091d5d42833ee16b40775b64c5bbe3871ee0`;
  QA's frozen integration test hash: `41eb0044328aced364047d8bda0ac860c5ceefd6e54c50436bff8211eee0a1b7`.
  Baseline-relative production diff remains only `RoutineUpsertExecutor.java`. No production edits during the check.
  Return this test defect to QA; no production regression identified by this run.
- QA's baseline reproduction and the post-fix targeted run are recorded in `docs/task-5g-qa.md` and the report section
  above. The targeted run confirmed F1 cases pass; one unrelated new concurrency test has the fixture-name assertion
  mismatch described above.
- F2 role-collision classification is addressed in the authorized one-file production change; evidence and details are
  recorded above.

## Initial scope/status

- Read the complete 5g assignment, applicable README/local implementation notes, shared behavioral/wire documents
  required by the contract, and linked compatibility/schema/authentication references relevant to sync semantics. No
  repository/checked-parent `AGENTS.md` was found.
- Independently inspected sync preflight/executor, account transaction boundary, canonical row adapter, V3
  reservation/tombstone triggers and sync integration coverage. Other copy/import/deletion/replay interactions examined
  did not yet establish another production defect.
- At initial inspection, no files under `src/main/**` had changed and no Maven command had run; the later authorized
  implementation/build/test-slot evidence is recorded above.
- Baseline-relative production source diff: none
  (`diff -ru /tmp/gt-backend-5g-baseline-BUCeOL/src/main/java src/main/java` produced no output).
- Initial inspected production hashes: `RoutineUpsertExecutor.java`
  `5657f47b3beb7e003e7a09d1d8989a264d8d46094eb4eecb0edc1248218639d1`; `CanonicalAggregateRepository.java`
  `b3ff5bf36871a5555a0e3725b65e7b23183ec41d1cf7f6a10e044b8341107998`; `PushPreflight.java`
  `f3f4e72d78af4417f68edb2a725608420c0c9cdccc6716cdd5f9ccfb491508bc`.

## Final Worker verification handoff

- Final targeted command:
  `flock /tmp/gt-backend-5g-build.lock ./mvnw -q -Dtest=RoutineUpsertHttpIntegrationTest,PushPreflightTest test` — exit
  0. Isolated Testcontainers PostgreSQL 16. Reports: `RoutineUpsertHttpIntegrationTest` 65 run, 0 failures/errors/skips;
  `PushPreflightTest` 24 run, 0 failures/errors/skips; total **89 run, 0 failures/errors/skips**. F1 unknown post-write
  check failures for all three copy paths and F2 workout-root-vs-child reservation collision passed; added acceptance
  cases in this frozen test set also passed. No compile failures.
- The immediately preceding run against a prior QA freeze had 1 test-fixture assertion failure (documented above); QA
  corrected that helper/expected source name before this final run. This final run used
  `RoutineUpsertHttpIntegrationTest.java` hash `b90e370f1f8066c63543da4fbaf98659a6bd7136346bac00c1125f2cfddc01ea` and
  `PushPreflightTest.java` hash `a2057f413915f334db360b123357e79823455a41c36edcf14fd03209caaa82e0`.
- Final production executor hash: `02b9018484ff6b4b6811817195a2f26ace7c4cc1c599f413db01bf5d90399a95`.
  `diff -qr /tmp/gt-backend-5g-baseline-BUCeOL/src/main/java src/main/java` reports only `RoutineUpsertExecutor.java`
  differs. The F2 addition is confined to the exact `23505`/`child_id_reservations_pkey` primary diagnostic; unknown
  constraints remain top-level failures. The preceding `-Dmaven.test.skip=true package` command also passed (exit 0)
  after this production change.
- No production edits during final test run. Worker did not run full `./mvnw -q test`; QA owns final integrated/full
  regression verification. Worker returns the test slot and remains production-frozen for independent QA verification.
