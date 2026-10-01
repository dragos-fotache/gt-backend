# Task 6 — accepted

**Coordinator accepts task 6** for the bounded direct mutation/resource replay surface, including AC6.7 maintainability
review. Scope and fixed criteria: `docs/task-6-contract.md`. Coordinator owns this review; Worker owns production and QA
owns independent tests/verification. Baseline: `/tmp/gt-backend-task6-baseline-SjcEZw`; separate shared-docs remains
clean at `3aefeb3e2eaa147793d595833217d6070eeabff2`.

## Current integration review

All 14 direct mutable routes, resource raw-byte preflight, separate V6 `resource_mutations`, and a resource entry into
the existing domain/publication transaction are implemented. Baseline-relative source scope: new resource package,
public `PushPreflight.Failure` constructor, focused `RoutineUpsertExecutor` additions, new V6, and a dedicated QA
integration test. Applied V1–V5 and build configuration are unchanged. No public resource reads, pull, account lifecycle
or deployment work is authorized.

Initial choices reviewed before checks: reuse one account/session-fenced command engine, no synthetic sync replay
writes, resource evidence independent of retained batches, and DELETE accepts only zero-byte/absent body (nonempty
payload is 422 without claiming the key). All accepted non-DELETE responses are canonical resources; original
status/body bytes are stored atomically for replay.

## Historical findings and verification

QA authoring issues were corrected without changing production policy: local Java-executable variable shadowed
`java.nio`; a checked IOException was unhandled; an early two-change assertion mistakenly counted batches; a
routine-create replay fixture supplied an unsupported name. These were compile/fixture fixes, not behavioral acceptance.
QA compile-only check passed with identical before/after input manifests.

Worker's first executed behavioral check:

```sh
flock /tmp/gt-backend-task6-build.lock ./mvnw -q -Dtest=DirectResourceHttpIntegrationTest,RoutineUpsertHttpIntegrationTest,PushPreflightTest,AuthHttpIntegrationTest,GoogleTokenVerifierTest test
```

**113 tests: 112 passed, 1 failed, 0 errors/skips.** Direct resource tests: 11 passed, 1 failed; existing sync HTTP
(65), preflight (24), auth HTTP (11), and Google verifier (1) passed. Before/after build-input manifests match at
`/tmp/gt-backend-task6-worker-behavior-{before,after}.sha256`; copied reports
`/tmp/gt-backend-task6-worker-behavior-reports/`. That directory also contains stale previously generated reports from
unselected suites; the totals above explicitly count only the five freshly selected suites, not every XML present.

### F6.1 — retryable publication failures misclassified as HTTP 500

Confirmed real HTTP/PostgreSQL reproduction:
`transientSerializationFailureIs503NondurableAndUnchangedDirectRetrySucceeds` injects SQLSTATE 40001 on batch
publication. Expected 503 `temporarily_unavailable`; observed 500 `internal_error`. The shared executor wraps late
publication failures and the direct handler did not classify transient causes in that wrapper. The failure interrupted
the test before its rollback/unchanged-retry assertions, so this run does **not** establish those effects. Coordinator
assigned a narrow resource error-boundary fix to Worker, including review of initial authorization lookup; unexpected
integrity faults must remain sanitized, nondurable 500 responses, and no sync/domain classification widening is
permitted. Resolved: the narrow controller fix recognizes nested transient causes at both initial authorization and
execution boundaries. Final independent QA and Coordinator checks verified 503, rollback and successful unchanged retry
without relaxing the oracle.

## Functional verification before structural cleanup

F6.1 was fixed narrowly in the resource controller and independently verified. QA added the identified direct-route
guard cases without changing production policy. Independent targeted verification passed **116 tests**; the full suite
passed **137 tests**, with zero failures, errors, or skips. Both runs used identical frozen input manifests
(`60a76665667133d313ba43bbbfdb4e849e6c7b4ee42b5c43e0564d36057b91f8`). Fresh artifacts:
`/tmp/gt-backend-task6-qa-targeted-reports/`, `/tmp/gt-backend-task6-qa-full-reports/`. These results establish the
pre-cleanup implementation only.

### F6.2 — local structural deterioration (resolved before acceptance)

Before acceptance, the user made maintainability/readability mandatory (AC6.7). Coordinator review identified the new
long `ResourcePreflight.prepare` mixing route resolution, structural validation, transformation and envelope adaptation;
`executeResource` mixing replay storage and HTTP construction; and nullable `resourceRequest` mode coupling resource
policy/replay into the shared executor. Passing tests alone do not resolve these concerns.

Coordinator approved Worker's bounded proposal: named preflight stages with small route/response metadata; a focused
resource executor; separate sync versus resource replay/evidence handling; an explicit accepted-or-rejected common
domain result. The existing 17-command domain algorithms may be moved intact behind that result boundary, but broad
redesign/refactoring of the legacy god method is not authorized. Remaining legacy complexity must be recorded as
dedicated technical debt. No schema/dependency/test changes are authorized for this cleanup.

## Post-cleanup structural review

Worker's build-only package passed and production was frozen. Coordinator read all changed paths and inspected the
original-baseline executor diff (89 lines including context). The shared domain algorithms, SQL, savepoints and
collision classification are unchanged; only replay/result orchestration moved. The three temporary upsert guards
introduced for resource mode were removed.

AC6.7's five questions:

1. **Did an existing large method grow?** No substantial new command block remains in the legacy body. Its domain
   application/publication is retained intact behind the common result boundary; sync replay has a short wrapper.
2. **Did mode checks or coordination state grow?** The nullable resource-request mode and resource-specific evidence
   checks are gone from shared execution. Accepted/rejected command results and response kinds are explicit. Remaining
   legacy flags are recorded debt, not new task-6 coordination.
3. **Are unrelated responsibilities mixed?** Resource preflight has named resolution, body validation, command
   adaptation and shared shape-validation stages. Resource replay lookup, identity matching, route policy, response
   mapping and persistence have focused methods in `ResourceMutationExecutor`; the account/session transaction remains
   one visible orchestration boundary.
4. **Is obvious duplication removed?** Custom-exercise create/update share one whitelist and the new resource
   header/path/body UUID checks share one predicate. Domain execution/publication is reused rather than copied into each
   direct route.
5. **Is the resource path readable in isolation?** The controller delegates to preflight and the resource executor;
   replay occurs before route eligibility inside the authorized transaction, and the typed domain result is
   mapped/persisted once. No speculative handler framework was introduced.

**Structural disposition:** the bounded cleanup resolves F6.2 and meets AC6.7; final post-cleanup behavioral
verification also passed. Remaining legacy complexity is recorded in `docs/technical-debt.md` for a separately approved
task, not authorization to refactor it now.

## Final post-cleanup verification and acceptance

All relevant source, test, resource and build inputs were frozen; Maven operations were serialized with
`/tmp/gt-backend-task6-build.lock`.

- Worker build-only package passed. Post-cleanup resource/sync/preflight self-check passed **104 tests**, zero
  failures/errors/skips; fresh artifacts `/tmp/gt-backend-task6-postcleanup-selfcheck-reports/`.
- Independent QA five-suite target passed **116 tests** and full suite passed **137 tests across 8 suites**, zero
  failures/errors/skips. Exact commands and route/AC evidence are in `docs/task-6-qa.md`. Fresh reports:
  `/tmp/gt-backend-task6-qa-postcleanup-targeted-reports/` and `/tmp/gt-backend-task6-qa-postcleanup-full-reports/`.
- Coordinator independently parsed the fresh XML totals, reviewed the final baseline-relative source scope and shared
  checkout/build preservation, and passed **five real HTTP/PostgreSQL spot tests** on the identical final inputs.

Coordinator command:

```sh
flock /tmp/gt-backend-task6-build.lock ./mvnw -q '-Dtest=DirectResourceHttpIntegrationTest#allFourteenMutableRoutesReturnCanonicalResourceShapesAndExpectedStatus+resourceReplayUsesExactMethodRouteAndRawBodyAndKeepsOriginalDurableDomainRejection+concurrentDirectAndSyncReplacementsSerializeWholeSameAccountAggregates+postWritePublicationFaultRollsBackDirectActiveRootReplacementAndUnchangedRetrySucceeds+transientSerializationFailureIs503NondurableAndUnchangedDirectRetrySucceeds' test
```

Spot reports: `/tmp/gt-backend-task6-coordinator-spot-reports/`; log `/tmp/gt-backend-task6-coordinator-spot.log`.
Coordinator before/after manifests match each other and final QA's full-run manifest. All four QA final manifests hash
to `5efdeb16cd7cd45049201da37a1016560cb0ddba8b3a34170fa04c1e89c9219f`; paths
`/tmp/gt-backend-task6-qa-postcleanup-{before,after}-{targeted,full}.sha256`. Coordinator manifests:
`/tmp/gt-backend-task6-coordinator-{before,after}.sha256`. Current `target/surefire-reports/` includes the later
five-test resource subset; use the copied full reports for full-suite totals.

Final source hashes:

| File                                   | SHA-256                                                            |
|----------------------------------------|--------------------------------------------------------------------|
| ResourceController.java                | `56ba0bb1793fd1aa292ef2078c27de8fe6ca6670192fd2b24ee6c126ff33cc04` |
| ResourcePreflight.java                 | `d811dc559e6aef8d423b154b7d2d2a40a796ab0439c74888ca553e82ed72c8ed` |
| ResourceMutationExecutor.java          | `d8e68e185e9aa4c5e992a434ffea0af15743337a7451951cc80745038300af0e` |
| RoutineUpsertExecutor.java             | `23c39e2bca1bf6f2017a7827fce9d80156a2e2a64a4c85db0a80cdb981c02425` |
| PushPreflight.java                     | `8595acb0c7f5dea32219d8c888a7df65aab1b27974193293e1d4c70d7a896a8d` |
| V6__resource_idempotency.sql           | `a7fd5660687cf4b3c5fd5f6d72ba2f037e923f434b94cbaee86db38063f38792` |
| DirectResourceHttpIntegrationTest.java | `b77a4b36790d9b491f10cc4a0cec6bd77310098b0f19433b56e74a9c937880e5` |

### Scope and limits

All 14 mutable route types have positive HTTP evidence; direct-specific guards, exact method/route/body identity,
independent namespaces, original responses/rejections, concurrency, real-JVM replay and fault recovery have
representative independent coverage. Common replay/transaction code and existing sync/auth tests supplement repeated
permutations; they do not claim route-specific execution where none occurred. No behavioral guarantee is waived.
AC6.1–6.6 map to the final QA evidence; AC6.7 is the structural review above, not a conclusion inferred from green
tests.

No blocking finding remains. The existing auth-cleanup/Hikari connection-refused logs after disposable-container
shutdown remain a recorded, nonblocking lifecycle anomaly; no test assertion failed and no unrelated scheduler fix was
made. Legacy command complexity remains dedicated debt, not permission to extend it with new mode flags.

Applied V1–V5, build inputs, catalogue/shared-docs, local schema/data, secrets and Herdr layout were preserved. V6 was
exercised only in isolated test databases, not applied to local `gymtracka`. No staging/commit occurred. This is not
complete-v1, deployment, OAuth-release or Android integration acceptance. **Task 7 remains unassigned and
unauthorized.**
