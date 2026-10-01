# Task 7b — independent QA

## Requirement-first coverage plan

This plan is derived from `docs/task-7-contract.md`, current shared backend contract/OpenAPI and the pinned catalogue
specification, before inspecting the 7b implementation. Expected values will come from explicit fixture inputs and
contract rules, never from a production mapper or response reused as an oracle. Tests will use authenticated real HTTP
against isolated PostgreSQL 16, keep business timestamps at millisecond precision, and preserve existing assertions.
Focused new browsing/token integration tests and small reusable fixture helpers are preferred over expanding legacy
suites. No Maven run is authorized until Coordinator grants a frozen-input slot.

- **Exercise search / AC7b.1:** independent pinned-bundle and owned-custom examples; other-account custom invisibility;
  exact catalogue version and full canonical fields/order/nulls. Exercise name, muscle name, and equipment name searches
  each receive Unicode/case-fold and literal `%`, `_`, and escape-character cases. Assert category OR and cross-category
  AND semantics, q conjunction, sorted/deduplicated filter equivalence, unknown valid IDs yielding no matches, and
  absent/empty q equivalence without trimming whitespace. Include query validation and safe typed errors.
- **Workout browsing / AC7b.1:** independently specified active and finished summary fields (including nullable
  finish time, revision and exact status filtering); exclude foreign/deleted roots. Assert summaries are not full
  aggregates and order by startedAt DESC then canonical UUID DESC, including timestamp ties and UUIDs on both sides of
  the signed-bit boundary.
- **Saved routines / AC7b.1:** include complete populated and valid empty saved routines (nested ordered entries/sets,
  current exercise labels); exclude drafts, foreign roots and deleted roots. Never split or truncate an aggregate.
  Assert UUID ASC order including signed-bit boundary values.
- **Keyset / AC7b.2:** for each route assert empty, limit 1, default 100, limit 200, partial and exact terminal pages,
  `hasMore`/nullable token consistency and a full traversal with no duplicate/omitted IDs. Verify strict tie-aware
  progress and matching order. Invalid, repeated, noninteger and out-of-range limits/status/unknown/scalar duplicates
  and malformed repeated UUID syntax must return contract errors without read side effects.
- **Token binding / AC7b.3:** test malformed/empty/tampered tokens and validly signed context mismatches for account,
  endpoint, normalized filters and effective limit; each rejects `400 invalid_page_token` without silently restarting or
  disclosing data. Verify q Unicode normalization, sorted/deduplicated UUID sets, omitted/default limit and equivalent
  contexts continue. Same-account second live session continues; token without bearer, foreign account, revoked/logout
  session, or disabled account cannot authorize. Exercise continuation across a representative real JVM restart with
  the same configured signing key, not a process-generated secret.
- **Fixed expiry / AC7b.4:** with the narrow pagination service Clock seam, assert first-page expiry is exactly service
  time + 15 minutes; continuation does not slide it; at `now == expiresAt` the authentic matching token returns
  `410 page_expired`. Wrong signature/context remains `400 invalid_page_token` even once expired. A tokenless request
  after expiry starts a new page. Tests must not use sleeps or alter auth clocks.
- **Live membership and no effects / AC7b.4:** mutate/create/delete/rename or status-change domain data between
  pages and verify the continuation is a current-view strict keyset read (no frozen result/session), while complete
  aggregates have current labels and no hybrid/truncation. Snapshot before/after relevant root/revision/reservation,
  cursor/batches, resource/sync replay and credential lifetime for successful/repeated/terminal/invalid/unauthorized
  reads; supplied Idempotency-Key must not create evidence. Use controlled transaction gates where a within-page
  consistency interleaving can be deterministically demonstrated.
- **AC7b.5 / structure and integrated regression:** keep fixtures/helpers and test methods cohesive/readable; report
  coverage actually achieved, direct HTTP evidence vs supplements/gaps, structural concerns, exact final frozen QA
  target/full commands and reports/manifests after an authorized slot. Preserve all 7a and accepted mutation/auth
  assertions. QA reports findings and verification, not acceptance.

Fixture/oracle constraints: use fixed explicit lowercase UUIDs chosen to exercise UUID ordering across the high-bit
boundary; choose millisecond-aligned UTC timestamps with deliberate tie groups; seed references from the pinned
catalogue or valid same-account custom exercises. Expected exercise relationships, summaries and full routines are
constructed from supplied fixture values and normative canonical rules, not response-to-response comparisons. Mutations
between pages use existing accepted APIs/commands and assert their actual effects separately. Service-time expiry is
controlled only through an approved narrow pagination Clock seam. Do not mock domain/service behavior.

## Authoring and verification state

- Read task 7b assignment, common task-7 contract and the required supplied normative backend/OpenAPI/catalogue docs.
- Coverage plan was recorded before new implementation inspection. QA owns only `src/test/**` and this handoff. No
  production or build edits were made; production is now frozen per Coordinator.
- Initial tool discovery queried `jdtls ...`, which was incorrect because jdtls is the underlying language server, not
  an MCP tool name. Corrected discovery using `mcp search` for `get_document_symbols` on `mcpls`; the gateway returned
  registered `mcpls_get_document_symbols` and related navigation tools. The first real symbols call on the accepted
  read integration test returned `LSP server 'java' is still initializing (large project load in progress)`; a retry
  on `BrowsingHttpIntegrationTest.java` succeeded and returned its class/method outline. No tooling/configuration was
  changed. Use mcpls for subsequent precise Java navigation and `rg`/`read` for broad or direct content inspection.
- Worker approach checkpoint proposed one shared HMAC-signed token service, stable purpose-derived AuthCrypto key,
  injected service `Clock`, Java `Locale.ROOT` literal Unicode matching with keyset candidate scanning, and independent
  endpoint queries within the consistent read transaction. Coordinator reports the production build-only package passed
  and production is stable/frozen; no behavioral tests have run in QA's slot.
- Corrected three independent-review issues before freeze: verified `MIDDLE_BACK` against the supplied pinned exercises
  JSON (`cf81b51f-97d4-4cb2-ab3c-1d59472481ed`), repeat the `qa-keyset-fixture` q filter on every continuation,
  and release the temporary `ServerSocket` port reservation before starting the real child JVM. Broad global search/
  relationship assertions traverse all continuation pages rather than assume a 200-item page contains every match.
## Frozen compile-only handoff

`src/test/java/com/dragosfotache/gtbackend/resource/BrowsingHttpIntegrationTest.java` is frozen at SHA-256
`2d0371227412371ddc6cac4c6eecf229f512901e8a16d7623e251dbe5bee1463` with 17 exact `@Test` methods. (A prior naive
substring count included the class-level `@Testcontainers`; exact source annotations and Surefire both confirm 17.) The
methods cover:
exercise Unicode/literal search and boolean filters; exercise UUID ordering, default/200 limits and terminal boundaries;
workout timestamp/UUID order and status filters; full saved-routine shapes/draft exclusion/fresh labels plus the
within-page snapshot interleaving; foreign/deleted visibility; live create/rename/delete/status-change/draft-save between
pages; no-GET-effects and query validation;
normalized filter and second-session continuation; real child-JVM restart with stable signing configuration; exact fixed
15-minute expiry/context-before-expiry; live logout/disabled authorization; malformed/tampered/account/endpoint/filter/
limit token isolation. Pinned bundle assertions derive from the supplied seed and catalogue README; fixtures use valid
millisecond-aligned times. Broad global searches traverse keyset pages.

The Coordinator-granted compile-only slot ran only:

`flock /tmp/gt-backend-task7-build.lock ./mvnw -q -DskipTests test-compile`

- First attempt failed to compile at test line 339 because a custom-exercise fixture call omitted its UUID argument. QA
  corrected the fixture invocation only; no expectation was changed. Log:
  `/tmp/gt-backend-task7b-qa-first-compile/maven-test-compile.log`; exit code 1; initial source hash
  `8be8b8344024fd1156328a2180df83448bbb7a9926a1508d66832b454248f336`; 89-path before/after manifest hash
  `9cc2fe7fe1813e387c4271838890bff27ffc4f76423e58c3702d576af60e03b6`.
- Repeated on frozen corrected inputs and passed (exit 0). Log:
  `/tmp/gt-backend-task7b-qa-repeat-compile/maven-test-compile.log`; the 89-path before/after manifests match, each
  manifest SHA-256 `b038ffd1a6ab0a247a485e6bc3eabc3fa91b8b273fabc58e21274ec74ec9c379`.
- After adding the focused saved-routine list interleaving below, re-ran the same compile-only command on frozen inputs;
  exit 0. Log: `/tmp/gt-backend-task7b-qa-interleaving-compile/maven-test-compile.log`; 89-path before/after manifests
  match, each manifest SHA-256 `41b939b4d58fa5bb12a8f584e57bbbb49ecdbca69ac3005b2cdd9b62ced5e646`. Final test-source hash is
  above. Each run directory contains `verification-input-paths.nul`, `inputs-{before,after}.sha256`, source hashes,
  method count, exit code and log. Manifests include README/build wrapper and pom, `.mvn`, all `src/main/**` and
  `src/test/**`, and supplied `shared-docs/**` including normative docs/catalogue seed; exclude `.git` metadata and
  backend coordination docs.

Added `savedRoutineListKeepsRootChildrenAndExerciseNameFromOneSnapshotDuringAcceptedReplacement`: a test-only spy
one-shot latch targets the exact canonical saved-routine root SELECT by SQL and root UUID, and pauses only after its
real ResultSetExtractor has returned root fields/revision. While the actual list HTTP request is paused, a whole-routine
replacement commits through accepted `PUT /v1/routines/{id}`. After release, the test requires the old complete root,
entry, set and exercise label; a fresh list requires the complete replacement and incremented revision. A `finally`
block always releases the latch, clears the static gate and shuts down its executor. The interleaving passed in both
independent final runs below. No production hook/configuration was added.

## Independent final verification and evidence review

Worker's earlier self-check is separate evidence: Coordinator reports actual 132 tests across 5 suites, zero
failures/errors/skips; Coordinator independently parsed its fresh XML. My authorized final runs used frozen inputs and
no source/fixture edits:

| Run | Exact command | Actual Surefire XML | Fresh copied evidence |
|---|---|---:|---|
| Target | `flock /tmp/gt-backend-task7-build.lock ./mvnw -q -Dtest=BrowsingHttpIntegrationTest,ResourceReadHttpIntegrationTest,DirectResourceHttpIntegrationTest,RoutineUpsertHttpIntegrationTest,PushPreflightTest,AuthHttpIntegrationTest,GoogleTokenVerifierTest test` | 144 tests, 7 suites; 0 failures, 0 errors, 0 skipped | `/tmp/gt-backend-task7b-qa-final-target/surefire-reports/`; log `/tmp/gt-backend-task7b-qa-final-target/maven-target.log` |
| Full | `flock /tmp/gt-backend-task7-build.lock ./mvnw -q test` | 165 tests, 10 suites; 0 failures, 0 errors, 0 skipped | `/tmp/gt-backend-task7b-qa-final-full/surefire-reports/`; log `/tmp/gt-backend-task7b-qa-final-full/maven-full.log` |

Target XML per suite: Browsing 17, ResourceRead 11, DirectResource 15, RoutineUpsert 65, PushPreflight 24,
AuthHttp 11, GoogleTokenVerifier 1 (144 total). Full adds CatalogueImporter 7, AccountWriteRepository 7 and
CanonicalAggregateRepository 7 (165 total). Both copied browsing XML files contain 17 passing cases, including
`savedRoutineListKeepsRootChildrenAndExerciseNameFromOneSnapshotDuringAcceptedReplacement`. This is actual direct
HTTP/isolated-PostgreSQL evidence of the root/children view interleaving; not a simulator-only assertion.

For EACH run, fresh before/after path lists and SHA-256 manifests are saved under the corresponding output directory.
The 89 relevant input paths and file hashes match before/after in both runs; manifest SHA-256 is
`41b939b4d58fa5bb12a8f584e57bbbb49ecdbca69ac3005b2cdd9b62ced5e646` for each. Both records show the frozen source
hash above. Included inputs: root README/build wrapper/pom, `.mvn`, all `src/main/**` and `src/test/**` (including
resources), and supplied `shared-docs/**` including normative contract and catalogue seed. Excluded `.git` metadata and
backend coordination docs (including this report). Stale `target/surefire-reports` was removed before each exclusive
run; target's fresh report copy was saved before starting full. No other target data was removed.

### Direct evidence by requirement and boundary

- **AC7b.1:** direct list HTTP evidence covers pinned bundled plus owned/foreign custom visibility, canonical exercise
  and full saved-routine fields, workout summaries, literal `%`, `_`, backslash, dotted-I Unicode lowercase, whitespace
  preservation, name/muscle/equipment search, OR within filter category, AND across categories, unknown IDs, status,
  catalogue version, saved-draft exclusion, and fresh exercise labels. Expected pinned data comes from the supplied seed.
- **AC7b.2:** exercise/routine UUID ordering and workout timestamp/UUID tie ordering include signed-bit boundaries;
  limit 1 and terminal/partial pages are directly exercised; exercise traversal covers default 100, max 200, empty
  filtered results and multi-page no-duplicate progress. Workout/routine fixture-size continuations and terminal pages
  are directly exercised. Boundary: default-size 100 and filled max-size 200 are not separately stressed on workout
  and routine endpoints; the 200 request on those routes has only small fixture populations. Empty full result lists
  are explicit for exercise filtering, not independently for workouts and routines. The large all-pages helper is used
  for exercise searches, not a >200-item workout/routine traversal.
- **AC7b.3:** direct tokens cover malformed/empty/tampered, wrong account, endpoint, filter and limit; sorted/deduped
  filter and equivalent q/default-limit contexts, same-account second-session continuation, stable-key real JVM restart,
  and signature/context-before-expiry behavior. Token alone, logout and disabled-account checks are direct HTTP.
- **AC7b.4:** controlled Clock asserts fixed 15-minute expiry, non-sliding continuation, exact-boundary `410`, and
  invalid context/tampering before expiry classification. Between-page membership/name/delete/status/save changes and
  no-read-effects are direct HTTP/PG. The newly added within-page saved-routine replacement uses the accepted HTTP
  writer while the canonical root SELECT is paused, then asserts complete old and fresh new root+children views.
- **AC7b.5 / integrated scope:** read-only structural review found one shared signed `ListPageTokenService`, normalized
  immutable `BrowseQueries`, separate endpoint SQL in `ResourceBrowseRepository`, and a cohesive `ResourceBrowseService`
  wrapping authorization, keyset assembly and canonical reads in read-only repeatable-read transactions. The canonical
  reader is reused rather than simulated. No specific new-path structural blocker was found in this review; Coordinator
  retains mandatory structural/scope review and acceptance.
- Fresh regression evidence integrates both read slices with accepted direct resource, sync/upsert, push preflight and
  auth suites. The three additional full-only catalogue/persistence suites are supplements, not list HTTP evidence.
  7a's tertiary-role mapper test remains a supplement, not pinned-seed HTTP evidence (the supplied pinned seed has no
  tertiary relation); accepted 7a historical results remain distinct from these fresh target/full runs.

**Finding disposition:** no target/full failures or QA test/fixture defects were found; no expectation was weakened.
The initial compile-only fixture overload error and its correction remain documented above. The source hash and 17 exact
method/XML counts resolve the earlier 18-count discrepancy without a source change. Reported route-specific page-size
and empty-result boundaries are coverage limitations, not observed production failures. No acceptance claim: Coordinator
still owns final spots, baseline-relative structural review and task-7 acceptance. No local DB/secrets, shared-docs,
production, build/config or test changes were made during final verification.
