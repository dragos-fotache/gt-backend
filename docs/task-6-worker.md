# Task 6 Worker report

## Initial implementation approach (in progress)

- Establish one account-fenced, session-authorized transactional command boundary shared by resource operations and
  accepted sync domain execution; reuse canonical domain validation/application and publication rather than create a
  second command engine or expose the row adapter.
- Add a forward-only V6 resource-replay store, separate from sync processed mutations and independent of sync-log
  retention. Persist account/key, exact request identity, and original status/body alongside domain/revision/batch
  effects in the same transaction; domain rejections retain replay evidence while rolling back domain effects.
- Build the shared boundary/replay path and exercise routes first, then routines, then workouts; preserve existing sync
  behavior and 5g fixes.
- DELETE policy: only a truly absent body (zero bytes) is supported; any present body, including `{}`, is rejected as
  structurally invalid and is not recorded. This preserves absent-body identity without synthesizing `{}`.

## Production implementation in progress

- Added the direct route/controller and strict byte-preserving structural preflight for all 14 contract operations.
  DELETE accepts zero bytes only; nonempty bodies reject before key lookup. Authorization and whole-request preflight
  precede resource replay.
- Added account/key resource replay in V6 with original method/normalized route/raw body/status/body. Resource execution
  reuses `RoutineUpsertExecutor` domain application and canonical batch generation inside the same
  account/session-fenced transaction; direct calls branch away from sync processed-mutation evidence. Accepted responses
  are returned from the just-created canonical batch; replay uses stored response bytes.
- Added direct route-specific POST-create/PUT-existing guards and preserved sync execution flow. Direct resource
  timestamps require a zero UTC offset; the existing sync preflight acceptance behavior is unchanged.

## Inspection findings / implementation refinement

- Required project and shared-contract references have been read. V1–V5 provide account-row locking, live session locks,
  canonical row writes, reservations, revision/batch tables and sync-only `processed_mutations`; V1–V5 remain untouched.
- The existing `RoutineUpsertExecutor` combines domain execution, canonical batch publication and sync replay/outcome
  storage. The focused extraction now separates resource replay/HTTP response orchestration (`ResourceMutationExecutor`)
  from sync replay/evidence in `RoutineUpsertExecutor`, while both call the same domain-command application and
  canonical publisher. The shared boundary returns an explicit accepted-or-rejected result; no nullable resource-mode
  switch remains.
- Resource request identity remains exact and separate. Structural direct parsing retains original body bytes and
  method/route identity; malformed requests and unexpected database faults remain nondurable.
- Contract-consistent route policy: DELETE accepts only an absent body. A present body (including `{}`) is 422
  `validation_error` before replay/domain execution, so the durable identity for a valid DELETE always contains zero
  body bytes.

No material contract contradiction identified in the required references. V6 is forward-only and has no FK coupling to
90-day sync batches. Build-only package passed before F6.1 and passed again after its focused production fix with
`flock /tmp/gt-backend-task6-build.lock ./mvnw -q -Dmaven.test.skip=true package`; an earlier initial compile found a
preflight exception-constructor visibility issue that was fixed before passing builds. Sync preflight acceptance
semantics remain unchanged. V1–V5, application config, `pom.xml`, and wrapper match baseline.

## F6.1 transient failure finding and production fix

- The frozen targeted HTTP/PostgreSQL run reproduced SQLSTATE `40001` during sync batch publication on a direct
  `PUT /v1/routines/{id}`. Expected `503 temporarily_unavailable`; observed `500 internal_error`.
- Cause: shared execution wrapped the late publication `DataAccessException` in `IllegalStateException`; the direct
  controller classified its top-level runtime wrapper as generic 500. The initial direct authorization lookup also
  lacked a local transient boundary and could escape to generic 500 handling.
- Fixed only `resource/ResourceController.java`: its sanitized response boundary now walks nested causes for
  `TransientDataAccessException`, `SQLTransientException`, connection SQLSTATE class `08`, and transaction-rollback
  SQLSTATE class `40`. This covers both initial auth lookup and the wrapped executor failure. It does not change domain
  SQLSTATE classification, `SyncPushController`, or tests. Other unexpected faults remain sanitized 500. The exception
  still escapes the transaction, so rollback/nondurable behavior is preserved by transaction control flow; assertions
  for post-failure state/retry were after the failing status assertion and were not observed in that run.
- Targeted command:
  `flock /tmp/gt-backend-task6-build.lock ./mvnw -q -Dtest=DirectResourceHttpIntegrationTest,RoutineUpsertHttpIntegrationTest,PushPreflightTest,AuthHttpIntegrationTest,GoogleTokenVerifierTest test`;
  113 tests, 112 passed, 1 failed, 0 errors/skips. Direct resource: 11 passed, F6.1 failed; sync HTTP 65, preflight 24,
  auth HTTP 11, Google verifier 1 passed. Copied reports: `/tmp/gt-backend-task6-worker-behavior-reports/`.
- Before/after frozen-input manifests for the combined targeted run matched:
  `/tmp/gt-backend-task6-worker-behavior-before.sha256` and `...-after.sha256`. After F6.1, the 15-case resource run
  passed the F6.1 rollback/retry assertions but had one QA helper failure: the helper omitted `aggregate_type` while the
  guard case expected it. QA corrected the helper.
- The sole authorized recheck command
  `flock /tmp/gt-backend-task6-build.lock ./mvnw -q '-Dtest=DirectResourceHttpIntegrationTest#directCopySourcesComeOnlyFromPathsAndRepresentativeSourceGuardsMapToHttpErrors' test`
  passed **1 test; 0 failures/errors/skips**. Fresh reports are `/tmp/gt-backend-task6-copy-source-recheck-reports/`;
  frozen source/test/build-input manifests match: `/tmp/gt-backend-task6-copy-source-recheck-before.sha256` and
  `...-after.sha256`. This is a single-method recheck, not a full resource-suite or full-suite result.
- QA subsequently reported 116 targeted and all 137 full-suite tests passed on the pre-cleanup production snapshot.
  Those results do not verify the AC6.7 cleanup below and must not be represented as post-cleanup verification.

## AC6.7 maintainability cleanup handoff (current snapshot)

### Baseline-relative scope and boundaries

- Relative to the pre-cleanup Task 6 snapshot recorded above, `ResourcePreflight` now has named route resolution, body
  validation, command adaptation, and canonical-envelope validation stages, with explicit route/command/response
  descriptors. The identical create/update custom-exercise whitelist is one named field set; controller key validation
  reuses the same canonical UUID predicate as path/body IDs.
- `ResourceMutationExecutor` now owns resource-key lookup/replay, route-specific eligibility, HTTP status/body mapping,
  and resource replay persistence. The shared `RoutineUpsertExecutor` no longer accepts a nullable resource request or
  stores resource HTTP concerns; sync replay/evidence is handled by the sync wrapper and the shared command path returns
  `DomainCommandResult.Accepted` or `.Rejected`.
- Final comparison to the original `RoutineUpsertExecutor` baseline confirmed that the three `workout.upsert`,
  `routine.upsert`, and `customExercise.upsert` branches did not have `&& code == null`; those added guards are removed,
  restoring the original mutually exclusive branch conditions. Sync replay identity now uses the structurally validated
  `Mutation.id()` accessor instead of reparsing raw JSON solely to recover the same UUID.
- Route-specific eligibility remains after replay and inside the same live-session/account-write transaction. The atomic
  domain changes, canonical sync publication, and durable resource response/rejection retain the same transaction
  boundary. Missing idempotency key remains 400; malformed key/path/body UUIDs remain 422.
- No changes to tests, schema/migrations, dependencies, build configuration, `PushPreflight`, V1–V5, sync behavior, or
  the existing command algorithms. V6 remains unchanged.

### Stable build and verification status

- Locked build-only command: `flock /tmp/gt-backend-task6-build.lock ./mvnw -q -Dmaven.test.skip=true package` —
  **passed** on this cleanup snapshot. Maven emitted runtime deprecation/restricted-access warnings; compilation/package
  completed successfully. No tests were run for this build.
- Do not reuse pre-cleanup QA results as acceptance evidence. Requesting a coordinated targeted self-check slot against
  the exact hashes below; production/tests/build inputs remain frozen until that slot is granted.
- Current production SHA-256: `ResourceController.java`
  `56ba0bb1793fd1aa292ef2078c27de8fe6ca6670192fd2b24ee6c126ff33cc04`; `ResourcePreflight.java`
  `d811dc559e6aef8d423b154b7d2d2a40a796ab0439c74888ca553e82ed72c8ed`; `ResourceMutationExecutor.java`
  `d8e68e185e9aa4c5e992a434ffea0af15743337a7451951cc80745038300af0e`; `RoutineUpsertExecutor.java`
  `23c39e2bca1bf6f2017a7827fce9d80156a2e2a64a4c85db0a80cdb981c02425`; `PushPreflight.java`
  `8595acb0c7f5dea32219d8c888a7df65aab1b27974193293e1d4c70d7a896a8d`; `V6__resource_idempotency.sql`
  `a7fd5660687cf4b3c5fd5f6d72ba2f037e923f434b94cbaee86db38063f38792`.

### Remaining structural debt (explicitly out of scope)

- `RoutineUpsertExecutor.applyCommand` remains a large legacy domain orchestrator that parses command payloads, applies
  eligibility/domain rules, persists rows, and assembles canonical changes. Splitting its existing command algorithms
  would expand risk and was explicitly excluded; cleanup only removed the resource/sync mode coupling around it.
- The executor still contains several command-kind booleans, inline SQL eligibility checks, and related savepoint/error
  classification. These are legacy follow-up candidates, not acceptance blockers for this bounded cleanup.
- `ResourcePreflight` still adapts the existing command protocol and uses the shared push validator as its final
  structural authority. A broader common validation framework is intentionally not introduced.
