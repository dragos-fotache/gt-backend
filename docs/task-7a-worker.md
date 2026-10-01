# Task 7a — Worker implementation handoff

## Status and scope

Coordinator released the initial production freeze after QA's unchanged-input full-suite run: 137 tests across 8 suites, zero failures/errors/skips; before/after manifest `ca8199beb5745cafde3db96c905abba92797d5cee1382b9e2ef53bb7791298d0`. The specific unresolved `ReadResult` factory call sites reported by Coordinator are corrected. Coordinator granted the first **build-only** slot. Production/resources/build/shared-docs inputs were frozen for the package operation; QA continued editing only `src/test/**`.

Authorized scope remains 7a and Worker-owned `src/main/**` plus this report. No test edits, shared-docs/build configuration changes, migrations or 7b work. The sole Maven invocation was `flock /tmp/gt-backend-task7-build.lock ./mvnw -q -Dmaven.test.skip=true package`; it exited successfully. This compiled/packaged main code only; tests were neither compiled nor run. Inputs were captured for 73 paths (production/resources, Maven wrapper/build files and supplied shared-docs). Before/after manifests match exactly; their SHA-256 is `20331f2a09741291d342aedeb459a5eeafcbffcc7ec0e2d0fc93190313a0c6d2`.

## Implementation

- `src/main/java/com/dragosfotache/gtbackend/resource/ResourceReadController.java` adds only the three individual GET routes. It validates bearer presence, nonempty query syntax, nonempty body and canonical path UUIDs; reuses `ResourcePreflight.isCanonicalUuidText`, uses typed route method references rather than string dispatch, injects Boot 4's `tools.jackson.databind.ObjectMapper`, and maps safe 401/404/422/503/500 responses.
- `src/main/java/com/dragosfotache/gtbackend/resource/ResourceReadService.java` runs each read in an explicit Spring `TransactionTemplate` configured read-only and `REPEATABLE_READ`, checks live authorization in that transaction, and returns a sealed `Found`/`NotFound` result.
- `src/main/java/com/dragosfotache/gtbackend/auth/SessionAuthorization.java` adds non-locking live access validation for read transactions. Existing `requireLiveAccess` and its write-lock query are unchanged; write callers still use that path.
- `src/main/java/com/dragosfotache/gtbackend/persistence/CanonicalAggregateRepository.java` adds visible bundled/owned exercise loading and resolved, ordered relationship labels. It reads root, muscle and equipment values within the caller transaction. Existing workout/routine query SQL is unchanged. Workout status parsing uses `Locale.ROOT`.
- `src/main/java/com/dragosfotache/gtbackend/domain/CanonicalAggregates.java` adds immutable resolved exercise-read values. `src/main/java/com/dragosfotache/gtbackend/resource/CanonicalResourceMapper.java` is a pure mapper with cohesive root/entry/set helpers; it maps resolved labels and relationships. Bundled exercise revision is omitted; custom revision is included. Workout status wire conversion uses `Locale.ROOT`.
- `src/main/java/com/dragosfotache/gtbackend/sync/RoutineUpsertExecutor.java` delegates existing workout/routine canonical publication mapping and custom-exercise mapping. `applyCommand` and write algorithms were not redesigned. Existing authorization and aggregate write fences remain in place.

Coordinator's bounded readability/correctness notes were addressed: explicit result variants, typed controller dispatch, shared UUID validation, named nested workout/set mapping helpers, nested `SQLTransientException` recognition, Jackson-3 mapper injection, and `Locale.ROOT` for status conversion/parsing. Default-English mapping fields/order are retained by moving existing mappings; mutation response/replay serialization has not yet been behaviorally verified.

## Verification and navigation

Build-only package passed as recorded above. The first behavioral self-check then ran exactly:

`flock /tmp/gt-backend-task7-build.lock ./mvnw -q -Dtest=ResourceReadHttpIntegrationTest,DirectResourceHttpIntegrationTest,RoutineUpsertHttpIntegrationTest,PushPreflightTest test`

Exit 0. Fresh XML/text reports were copied before any later Maven operation (none has been run) to `/tmp/gt-backend-task7a-selfcheck-reports/`. Parsed fresh XML totals: **115 tests, 0 failures, 0 errors, 0 skipped across 4 suites**: ResourceReadHttpIntegrationTest 11 (includes one mapper supplement), DirectResourceHttpIntegrationTest 15, RoutineUpsertHttpIntegrationTest 65, PushPreflightTest 24. QA's read-test source hash at run time matched the supplied `fc215eb37eea2ef4abda71f1a57e469583dc6d5eba6b75dc93145541a06200cd`.

Complete source/build/resource/contract/shared-docs verification-input hashes are `/tmp/gt-backend-task7a-selfcheck-before-inputs.sha256` and `/tmp/gt-backend-task7a-selfcheck-after-inputs.sha256`; they are byte-identical. Manifest SHA-256: `f1536b24772074b4300224d185fc5e453a7f1371b377914208b5ace1e190e5b9`; 107 files. The complete path lists are `/tmp/gt-backend-task7a-selfcheck-input-paths-before.txt` and `...-after.txt`, identical. The raw broader capture differed only at QA's `docs/task-7a-qa.md` handoff report, which was updated during the run; that coordination report is not a production/test/resource/build/seed/contract input and was excluded from the verification-input manifest. No relevant input path or hash changed. Exact command log: `/tmp/gt-backend-task7a-selfcheck.log`; exit code: `/tmp/gt-backend-task7a-selfcheck.exit`. Old `target/surefire-reports` was removed before this exclusive slot, preventing historical XML from inflating totals.

The Java LSP was used per `README.md`'s Code navigation preference. `mcpls` initially reported Java-server initialization; later document-symbol queries worked. Diagnostics on edited/new classes marked them “non-project file, only syntax errors are reported,” and some definition/reference/diagnostic calls returned internal errors or no locations. No tooling/configuration was changed. Where LSP results were unavailable, I used read-only `read`/broad `rg` inspection.

This is Worker self-check, not independent QA or acceptance. It verifies compilation and the selected four suites only; full regression, independent coverage/criterion review, Coordinator structural review and AC7a.6 sign-off remain outstanding. Production and tests are frozen at the verified state; no more Maven runs are authorized by this slot. 7b remains unauthorized.
