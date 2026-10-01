# Task 6 QA — requirement-first plan

Derived from `docs/task-6-contract.md` before inspecting implementation. These are requirement-based oracles, not
predictions from production code. Test fixtures and expected canonical resources/batches will be independently
specified.

## Route matrix

| Operation                                       | Required positive HTTP evidence                               | Proportionate contract-derived negative/replay evidence                                                                      |
|-------------------------------------------------|---------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------|
| POST `/v1/exercises`                            | 201 canonical fresh private reps root                         | ID/canonical input rejection; fresh-only/collision behavior; exact-byte replay and key conflict                              |
| PUT `/v1/exercises/{exerciseId}`                | 200 complete replacement of existing owned/live custom        | path/body mismatch, missing/foreign/deleted and bundled read-only; replacement ordering/revisions; replay after later change |
| DELETE `/v1/exercises/{exerciseId}`             | 204 bodyless delete                                           | absent body is zero bytes; absent/foreign/read-only/in-use rules; exact replay and conflict; state/batch effects             |
| POST `/v1/routines`                             | 201 exactly empty-name, draft, no-exercises root              | `{id}`-only shape/ID collision; exact-byte replay                                                                            |
| PUT `/v1/routines/{routineId}`                  | 200 complete owned/live replacement                           | mismatch/missing/foreign/deleted; no implicit create or promotion/demotion; child ordering/validation; replay                |
| DELETE `/v1/routines/{routineId}`               | 204 bodyless delete                                           | absent/foreign behavior; replay/conflict and no duplicate domain effects                                                     |
| POST `/v1/routines/{routineId}/save`            | 200 promotion/update with canonical saved routine             | eligibility and valid-shape domain rejection; blank/empty saved autosave allowed where specified; replay after state change  |
| POST `/v1/routines/{routineId}/start-workout`   | 201 fresh active workout copied from eligible saved source    | source/target/children shape and eligibility guards; template snapshot fidelity, active replacement atomicity; replay        |
| POST `/v1/workouts`                             | 201 fresh empty active workout                                | shape/ID collision; active-root replacement as ordered DELETE then UPSERT; replay                                            |
| PUT `/v1/workouts/{workoutId}`                  | 200 complete replacement of existing owned active root        | mismatch/missing/foreign/deleted/finished; child ordering and completion semantics; replay                                   |
| DELETE `/v1/workouts/{workoutId}`               | 204 discard active or delete finished root                    | absent/foreign behavior; replay/conflict and exact tombstone effects                                                         |
| POST `/v1/workouts/{workoutId}/finish`          | 200 finished workout after pruning/renumbering completed sets | missing completion/invalid state and source guards; canonical revisions/batch; replay after later change                     |
| POST `/v1/workouts/{workoutId}/perform-again`   | 201 fresh active copy from finished source                    | source/child shape, preserve template/notes/rest but clear completion/recorded rest; active replacement atomicity and replay |
| POST `/v1/workouts/{workoutId}/save-as-routine` | 201 fresh saved template from finished source                 | name/source/child validation; no notes/completion/recorded rest; source and active unchanged; replay                         |

The route matrix defines planned evidence; see the executed-evidence table below for the routes each companion test
actually calls. Shared-pipeline behavior observed on one route is not attributed to every route. The direct all-route
flow uses authenticated HTTP and checks canonical responses and persisted state/batches where applicable.
Common-pipeline supplements exercise account isolation and shared-key namespace separation, raw-byte identity/conflict,
selected original-result and domain-rejection replays, and selected auth/structural/transient failures; the
executed-evidence table below limits each claim to the routes actually called. Coordinator-approved DELETE policy: only
an absent/zero-byte body is supported; any nonempty body, including `{}`, is unsupported and rejects 422
`validation_error` without claiming a key. Header policy: absent key 400 `invalid_request`; malformed/noncanonical UUID
key/path 422 `validation_error`.

## Acceptance criteria to evidence

| Criterion                             | Planned independent evidence                                                                                                                                                                                                                                                                                           |
|---------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| AC6.1 Route/domain correctness        | All 14 positive routes; applicable route-specific rejection; canonical resource fixtures, fields/null/zero/default distinctions, child ordering, revisions, source-copy fidelity, active-root replacement ordered batches, and unchanged state/publication on rejects. Existing sync/domain regression tests retained. |
| AC6.2 Resource identity/replay        | Direct replay identity and byte distinctions; original status/body including 204 and durable domain errors after state changes and real restart; conflict preserves evidence; account/resource/sync namespace independence; no replay mutation.                                                                        |
| AC6.3 Validation/non-durable failures | Missing/malformed key, malformed JSON/shape/path mismatch/query syntax, auth failures, durable valid-shape domain rejection, and recovery from transient/injected failure with unchanged request. No unsupported error-precedence assertions.                                                                          |
| AC6.4 Atomic shared boundary/auth     | SQL-observed roots, revisions, batches, reservations and replay evidence commit/rollback together; multi-root rollback; direct authorization fenced by logout/revocation; full existing sync/auth/preflight regression.                                                                                                |
| AC6.5 Concurrency/recovery            | Concurrent duplicate direct request; direct+direct and direct+sync same-account serialization; same key across accounts and resource/sync namespaces; injected post-write rollback/retry; separate-JVM 201/200/204/domain-rejection replay after changed state.                                                        |
| AC6.6 Integrated verification/scope   | Stable-state targeted and full Maven checks under shared lock; baseline-relative source/test/resource/build-input hashes and copied reports; per-route/criterion evidence and explicit gaps.                                                                                                                           |

Scenarios will be implemented proportionately using existing test harness patterns, without exhaustive cross-products.
Build execution waits for a stable/frozen check slot agreed with the Coordinator; no Maven operation while shared
production/tests are changing. This is a plan, not a claim that checks have run.

## QA implementation status and pre-verification history

`src/test/java/com/dragosfotache/gtbackend/resource/DirectResourceHttpIntegrationTest.java` includes all 14 route types,
canonical/revision/null-zero/copy/finish and active-replacement assertions, path/payload/ownership/catalogue guards,
raw-body/method/route key conflicts, DELETE zero-byte/204 replay and approved nonempty-body rejection,
original-result/domain-rejection replay after state changes, account/resource/sync/auth namespace isolation, duplicate
and mixed direct/sync concurrency, second-JVM replay of 201/200/204/404, post-publication SQL-fault rollback/retry
(including cloned child reservations and active-root replacement), transient SQLSTATE 40001 response/retry coverage, and
direct session logout fencing. After the read-only coverage audit, QA authored focused additions for
missing/promote/demote routine PUT, bundled-catalogue POST collision, path-only source binding and representative
copy-source errors, failed direct finish/no-effects/replay, and explicit source/active preservation across copies. These
additions have been compiled with tests skipped but have not been behaviorally executed. The first compile-ready QA test
file SHA-256 was `dac06c943f42b390396945d47194a61948dd0ba5a7bef08b57181da9374f2561`; after the coverage audit helper fix
it is `b77a4b36790d9b491f10cc4a0cec6bd77310098b0f19433b56e74a9c937880e5`.

Worker's stable behavioral self-check ran 113 tests: 112 passed and one failed because `latestChanges` did not project
`aggregate_type`, which the unchanged QA assertion required. Worker also confirmed F6.1 returned the expected 503 and
its rollback/unchanged-retry assertions passed. The issue was the QA helper only, not a production finding. Independent
QA still needs to re-run the full focused test after this helper correction.

### Pre-verification authoring corrections

Worker's compile-only self-check identified a Java name-shadowing compile error in the test JVM launcher (`String java`
obscured `java.nio`); renamed the local to `javaExecutable`. Coordinator source review found that a check immediately
after starting an empty workout confused total account batch count with latest-batch change count. Recounted the flow:
13 accepted direct commands have committed at that point; the latest replacement batch separately has two ordered
changes. Corrected the independent SQL count oracle to 13; the complete all-route flow asserts 19 accepted direct
commands.

A second fixture review caught that the resource replay case initially supplied unsupported `name` to the `{id}`-only
routine creation route. Replaced it with a valid whitespace-formatted `{id}` body and use a valid whitespace-only
variation for raw-byte conflict. Key-order identity is checked separately using a structurally valid complete workout
PUT, alongside valid `0` versus `0.0` numeric spelling variants. No production policy was changed. The flow contains 19
accepted direct commits: every one of the fourteen route types is exercised, with additional successful complete routine
replacements and cleanup deletes. These are QA authoring fixes, not production findings. Worker execution confirmed
F6.1: the requirement-based 40001 direct-write case returned 500 rather than expected 503; rollback/retry assertions did
not execute. Worker owns the production fix. Tests/resources are frozen again, compile-ready, and await the
Coordinator's behavioral verification slot.

## Earlier compile-only verification (superseded by independent test runs below)

- Exact command: `flock /tmp/gt-backend-task6-build.lock ./mvnw -q -DskipTests test-compile`
- Outcome: exit 0; test compilation completed and
  `target/test-classes/com/dragosfotache/gtbackend/resource/DirectResourceHttpIntegrationTest.class` exists. Zero tests
  executed; no compiler diagnostics. Maven emitted JVM/dependency restricted-access and deprecation warnings only.
- Frozen source/test/build-input manifests: `/tmp/gt-backend-task6-qa-finalcompile-before.sha256` and
  `/tmp/gt-backend-task6-qa-finalcompile-after.sha256` match exactly; both manifest SHA-256 values are
  `6e7ec35c3f20933c96ea6f2c24362fa32ab5b80412a4bfb6a6e4599a0e96e42b`.
- Current QA integration test SHA-256 before/after compile:
  `dac06c943f42b390396945d47194a61948dd0ba5a7bef08b57181da9374f2561`.
- This establishes compilation only. No direct-resource HTTP test has been run by QA; behavior awaits the independent QA
  verification slot.
- Helper correction: `latestChanges` now selects and projects `aggregate_type`; the assertion requiring it was
  preserved. Source/test/build-input manifests before and after the correction's compile-only run are
  `/tmp/gt-backend-task6-qa-helperfix-before.sha256` and `/tmp/gt-backend-task6-qa-helperfix-after.sha256`, identical
  with SHA-256 `71ca064097e44ba5389ae0ef11753145cbdd8f001422e72d9c3cce0a858e4abf`. Direct test source SHA-256
  before/after: `b77a4b36790d9b491f10cc4a0cec6bd77310098b0f19433b56e74a9c937880e5`.

## Coverage audit disposition

The approved direct-specific additions cover the audited routine PUT creation/status-transition rules, bundled POST
collision, path-only source payloads and representative copy-source errors, empty-workout finish
rejection/no-effects/replay, and copy source/active preservation. Existing sync HTTP cases are supplements for
shared-engine permutations, not direct-route execution evidence. Coverage is representative, not exhaustive.

## Independent verification — completed on pre-cleanup inputs

The green results in this historical section predate the Coordinator-approved local cleanup (named resource preflight
stages/route metadata, focused resource replay executor, explicit accepted/rejected result, and removal of nullable
resource-mode coupling). They remain evidence for the pre-cleanup state only and are superseded for final-state
verification by the post-cleanup section below. Every recorded Maven command used
`flock /tmp/gt-backend-task6-build.lock`; Testcontainers PostgreSQL was used. QA made no production changes or assertion
relaxations.

| Run      | Exact command                                                                                                                                                                                       | Result                                                                                                                                                                                                                  |
|----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Targeted | `flock /tmp/gt-backend-task6-build.lock ./mvnw -q -Dtest=DirectResourceHttpIntegrationTest,RoutineUpsertHttpIntegrationTest,PushPreflightTest,AuthHttpIntegrationTest,GoogleTokenVerifierTest test` | Exit 0; 116 tests, 0 failures, 0 errors, 0 skipped. Direct 15; routine sync 65; push preflight 24; auth HTTP 11; token verifier 1.                                                                                      |
| Full     | `flock /tmp/gt-backend-task6-build.lock ./mvnw -q test`                                                                                                                                             | Exit 0; 137 tests, 0 failures, 0 errors, 0 skipped across 8 suites. Direct 15; routine sync 65; push preflight 24; auth HTTP 11; token verifier 1; catalogue 7; account repository 7; canonical aggregate repository 7. |

Fresh Surefire XML and text reports were copied before each subsequent run to
`/tmp/gt-backend-task6-qa-targeted-reports/` and `/tmp/gt-backend-task6-qa-full-reports/`; the full-run log is also in
the latter directory. Logs: `/tmp/gt-backend-task6-qa-targeted.log` (SHA-256
`c426ffb9f483fe4680a808e33b9896f2b7ebc9ba52680d04905e4506073c4839`) and `/tmp/gt-backend-task6-qa-full.log` (SHA-256
`7975dab3518135bf546bf08e68158fa1c16827e6e8b9049a8601de17bd24c482`). Targeted and full source/test/build-input
before/after manifests match byte-for-byte. Each manifest file hashes to
`60a76665667133d313ba43bbbfdb4e849e6c7b4ee42b5c43e0564d36057b91f8`; files are
`/tmp/gt-backend-task6-qa-independent-before-targeted.sha256`, `...after-targeted.sha256`, `...before-full.sha256`, and
`...after-full.sha256`. Direct test SHA-256 remained `b77a4b36790d9b491f10cc4a0cec6bd77310098b0f19433b56e74a9c937880e5`.

### Direct route to executed evidence

Each of the 14 route types has a positive authenticated HTTP assertion in
`allFourteenMutableRoutesReturnCanonicalResourceShapesAndExpectedStatus` (direct suite, 15/15 passed). The helper flow
also checks canonical response shape and persisted changes/revisions/batches. Focused companion evidence is in the
following direct-suite test methods:

| Route                                           | Route-specific executed evidence (in addition to the all-14 positive flow)                                                                                                                                                                                                                                                                                               |
|-------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| POST `/v1/exercises`                            | `directPathPayloadBindingOwnershipAndBundledReadOnlyRulesAreEnforced` checks create collision and bundled-ID protection. No exercise-create exact-replay case.                                                                                                                                                                                                           |
| PUT `/v1/exercises/{exerciseId}`                | `directPathPayloadBindingOwnershipAndBundledReadOnlyRulesAreEnforced` checks path/body mismatch, ownership and not-found behavior. No exercise-update replay case.                                                                                                                                                                                                       |
| DELETE `/v1/exercises/{exerciseId}`             | `allFourteenMutableRoutesReturnCanonicalResourceShapesAndExpectedStatus` checks bodyless 204 and replays the keyed `exercise_in_use` 409 after references are removed. It does not test successful 204 replay for this route.                                                                                                                                            |
| POST `/v1/routines`                             | `resourceReplayUsesExactMethodRouteAndRawBodyAndKeepsOriginalDurableDomainRejection` checks exact 201 replay, raw whitespace conflict and later-state replay; the restart test also verifies 201 replay for this route.                                                                                                                                                  |
| PUT `/v1/routines/{routineId}`                  | `routinePutIsExistingOnlyAndCannotPromoteOrDemoteAndCatalogueCreateCollides` checks existing-only and status-transition rules. The restart test checks exact 200 replay for this route. In the raw-identity test this route is invoked with a key first used by POST and returns a method-conflict 409; it is not an exact replay there.                                 |
| DELETE `/v1/routines/{routineId}`               | `resourceReplayUsesExactMethodRouteAndRawBodyAndKeepsOriginalDurableDomainRejection` checks zero-byte 204 replay and durable 404 replay after state changes; the restart test also checks this route's 204 replay. This is the only DELETE route with successful keyed 204 replay evidence.                                                                              |
| POST `/v1/routines/{routineId}/save`            | The all-route flow checks valid-shape 422 domain-rejection replay after the routine changes state. No successful save exact-replay case.                                                                                                                                                                                                                                 |
| POST `/v1/routines/{routineId}/start-workout`   | `directCopySourcesComeOnlyFromPathsAndRepresentativeSourceGuardsMapToHttpErrors` checks path-only source binding, copy fidelity and representative source guards. `postWritePublicationFaultRollsBackDirectActiveRootReplacementAndUnchangedRetrySucceeds` injects a post-write publication failure on this route and verifies rollback plus successful unchanged retry. |
| POST `/v1/workouts`                             | `postWritePublicationFaultRollsBackDirectActiveRootReplacementAndUnchangedRetrySucceeds` injects a post-write publication failure and verifies active-root rollback plus successful unchanged retry. No exact successful-request replay case.                                                                                                                            |
| PUT `/v1/workouts/{workoutId}`                  | `resourceReplayUsesExactMethodRouteAndRawBodyAndKeepsOriginalDurableDomainRejection` checks raw key-order and numeric-spelling conflicts on this route after a successful keyed update; it does not retry the identical request. No exact successful replay case.                                                                                                        |
| DELETE `/v1/workouts/{workoutId}`               | The all-route flow checks bodyless 204 only. No keyed successful 204 replay case.                                                                                                                                                                                                                                                                                        |
| POST `/v1/workouts/{workoutId}/finish`          | `directFinishWithoutCompletedSetRejectsWithoutEffectsAndReplaysAfterEligibilityChanges` checks a durable 422 rejection replay after eligibility changes, then a successful finish.                                                                                                                                                                                       |
| POST `/v1/workouts/{workoutId}/perform-again`   | `directCopySourcesComeOnlyFromPathsAndRepresentativeSourceGuardsMapToHttpErrors` checks path-only source binding, copy fidelity and representative source guards. The post-write fault test does not exercise perform-again.                                                                                                                                             |
| POST `/v1/workouts/{workoutId}/save-as-routine` | `directCopySourcesComeOnlyFromPathsAndRepresentativeSourceGuardsMapToHttpErrors` checks path-only source binding, source/active-state preservation and representative source errors. No exact replay case.                                                                                                                                                               |

Shared-pipeline supplements are route-bounded: the raw replay test's successful exact request is POST `/v1/routines`; it
separately invokes PUT `/v1/workouts` for raw-body conflicts and invokes PUT `/v1/routines` / routine save for
method/route conflicts. `directAcceptedAndRejectedOutcomesReplayAcrossRealJvmRestart` covers POST/PUT/DELETE
`/v1/routines` and a durable missing-routine DELETE rejection only. These cases support the common idempotency pipeline
but do not establish exact replay for exercise or copy routes.

### AC6.1–6.6 to executed evidence

- **AC6.1 — route/domain correctness:** the direct positive flow exercises all 14 routes. Direct-specific canonical
  shape, source fidelity, ownership/path guards, routine status transitions, catalogue collision, finish
  rejection/no-effects, and source preservation are covered by the companion tests listed above.
  `RoutineUpsertHttpIntegrationTest` adds sync-path/domain regressions only; it does not substitute for direct-route
  evidence.
- **AC6.2 — identity/replay:** `resourceReplayUsesExactMethodRouteAndRawBodyAndKeepsOriginalDurableDomainRejection`,
  `sameResourceKeyIsAccountScopedAndIndependentFromSyncNamespace`, and
  `resourceAndAuthenticationIdempotencyNamespacesAreIndependent`. Exact successful 201 replay is exercised on POST
  `/v1/routines`; exact 204 replay is exercised on DELETE `/v1/routines` only. The restart test covers POST/PUT/DELETE
  `/v1/routines` and a durable missing-routine DELETE rejection; it does not establish restart replay for other routes.
  Raw-body update conflicts are specifically on PUT `/v1/workouts`. Final post-cleanup targeted run also passed sync
  supplement `durableReplaySurvivesExecutorReplacementAfterLostHttpResponse`. These are shared-pipeline supplements, not
  universal per-route replay evidence.
- **AC6.3 — validation/non-durable failures:** `structuralAndHeaderFailuresAreNonDurableAndUseContractStatuses`
  exercises structural/header examples primarily on POST `/v1/routines`, plus route-specific path/ownership/source and
  durable-domain rejection cases above. `transientSerializationFailureIs503NondurableAndUnchangedDirectRetrySucceeds`
  injects SQLSTATE 40001 on PUT `/v1/routines`: observed contract 503 and verified the routine, revision, cursor and
  batch effects were rolled back. The unchanged retry then succeeded, ruling out a permanent failure entry for that key;
  the test does not directly assert a zero row count in the idempotency table.
- **AC6.4 — atomic shared boundary/auth:** direct persisted-state/batch checks,
  `postWritePublicationFaultRollsBackDirectActiveRootReplacementAndUnchangedRetrySucceeds` on POST `/v1/workouts` and
  POST `/v1/routines/{routineId}/start-workout`, and `directProtectedWriteIsFencedImmediatelyByAcceptedSessionLogout` on
  POST `/v1/routines`. The late-fault test does not exercise perform-again. Sync, preflight and auth suites are
  regression supplements.
- **AC6.5 — concurrency/recovery:** identical-request concurrency is on POST `/v1/routines`; direct/direct and
  direct/sync replacement serialization is on PUT `/v1/routines`. JVM-restart replay is limited to the routine routes
  noted above. Transient rollback/retry is on PUT `/v1/routines`; injected post-write rollback/retry is on POST
  `/v1/workouts` and POST `/v1/routines/{routineId}/start-workout`. These passed but do not prove all-route concurrency
  or recovery.
- **AC6.6 — integrated verification/scope:** pre-cleanup and final post-cleanup targeted/full runs passed on matching
  before/after manifests, with separate fresh report sets for each state.
- **AC6.7 — maintainability:** Coordinator-owned structural review and acceptance disposition are recorded in the
  Coordinator's acceptance review; independent QA does not claim this structural sign-off.

### Observation and limits

During both pre-cleanup and final post-cleanup full-suite teardown, `AuthCleanupScheduler.cleanup` logged
`CannotCreateTransactionException` / connection-refused against an already-stopped Testcontainers PostgreSQL instance;
Hikari also logged connection validation/creation warnings for stopped containers. Coordinator classified this
recurring, pre-existing teardown anomaly as nonblocking and outside Task 6 scope. Surefire recorded all 137 tests
passing with no failures/errors; no contract assertion failed. Coverage is representative rather than an exhaustive
negative cross-product; existing sync tests supplement shared-engine behavior only, and cannot establish every sync/auth
permutation for direct routes.

## Final independent verification — post-cleanup

Worker's post-cleanup resource/sync/preflight self-check was green, and Coordinator's structural review passed the
bounded cleanup. The results below are QA's sole final independent slot, against the frozen post-cleanup state. Test
source/assertions were unchanged; the direct test SHA-256 remained
`b77a4b36790d9b491f10cc4a0cec6bd77310098b0f19433b56e74a9c937880e5`.

| Run      | Exact command                                                                                                                                                                                       | Result                                                                                                                                                                                                                  |
|----------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Targeted | `flock /tmp/gt-backend-task6-build.lock ./mvnw -q -Dtest=DirectResourceHttpIntegrationTest,RoutineUpsertHttpIntegrationTest,PushPreflightTest,AuthHttpIntegrationTest,GoogleTokenVerifierTest test` | Exit 0; 116 tests, 0 failures, 0 errors, 0 skipped. Direct 15; routine sync 65; push preflight 24; auth HTTP 11; token verifier 1.                                                                                      |
| Full     | `flock /tmp/gt-backend-task6-build.lock ./mvnw -q test`                                                                                                                                             | Exit 0; 137 tests, 0 failures, 0 errors, 0 skipped across 8 suites. Direct 15; routine sync 65; push preflight 24; auth HTTP 11; token verifier 1; catalogue 7; account repository 7; canonical aggregate repository 7. |

All four post-cleanup source/test/resource/build-input manifests match byte-for-byte (SHA-256
`5efdeb16cd7cd45049201da37a1016560cb0ddba8b3a34170fa04c1e89c9219f`):
`/tmp/gt-backend-task6-qa-postcleanup-before-targeted.sha256`, `...after-targeted.sha256`, `...before-full.sha256`,
`...after-full.sha256`. Each covers `src/main`, `src/test`, `.mvn`, `shared-docs/catalogue`, `pom.xml`, and `mvnw`.
Reports and captured logs are new and separate from pre-cleanup evidence: targeted directory
`/tmp/gt-backend-task6-qa-postcleanup-targeted-reports/` (report-manifest SHA-256
`e627077617077330d8818ce17cf9d8d403d71d9066939cd2a1bc240f88f8442c`; log `gt-backend-task6-qa-postcleanup-targeted.log`,
SHA-256 `a11168fcf3aeb7242dfbf0135d8b0f50c42c921365fc03c658b632cfe788a7a5); full directory `
/tmp/gt-backend-task6-qa-postcleanup-full-reports/` (report-manifest SHA-256 `
8df6e64e7cd3c0a0ebeeb87a3f9f91cf535d76fd629ffd8719c1b91621d1632d`; log `gt-backend-task6-qa-postcleanup-full.log
`, SHA-256 `67ddcd03f1ebc4dd0949c55ced69d43cdc747dfd4feaa29a87aa8123e873b2f5). The report manifest files are
`/tmp/gt-backend-task6-qa-postcleanup-targeted-reports.sha256` and
`/tmp/gt-backend-task6-qa-postcleanup-full-reports.sha256`.

### Final-state criterion evidence and limits

The all-14 positive HTTP flow and all 15 direct-resource tests passed on final inputs. F6.1's
`transientSerializationFailureIs503NondurableAndUnchangedDirectRetrySucceeds` again passed: contract 503, persisted
routine/revision/cursor/batch rollback checks, then unchanged successful retry (no direct idempotency-table row-count
assertion). The sync suite's `durableReplaySurvivesExecutorReplacementAfterLostHttpResponse` passed after replay
extraction. Existing route-to-test table above provides specific evidence boundaries: successful zero-byte 204 replay is
only directly evidenced for routine DELETE; late publication-fault retry covers POST `/v1/workouts` and routine
start-workout, not perform-again; raw replay and restart cases remain limited to listed routes. Sync/auth suites
supplement shared behavior but do not prove every route permutation. AC6.7 disposition is Coordinator-owned as noted
above.
