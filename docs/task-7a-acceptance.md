# Task 7a — Coordinator acceptance

**Accepted for the three individual resource GETs and their common canonical/auth/consistency boundary.** This does
not complete task 7 or verify deployment. The user authorized starting 7b after this checkpoint with fresh Luna Worker
and QA contexts, briefed on team roles. Current 7a agents must not roll directly into 7b.

## Scope and structural gate

- GET `/v1/exercises/{exerciseId}` returns visible bundled or live owned custom data and ordered named relationships;
  bundled revision is omitted.
- GET `/v1/workouts/{workoutId}` returns complete live owned active/finished aggregates.
- GET `/v1/routines/{routineId}` returns complete live owned draft/saved aggregates, including legitimate blank/empty
  states. Missing/deleted/foreign roots share safe 404; new reads require live access/session/account authorization.
- `ResourceReadService` owns an explicit read-only repeatable-read transaction enclosing non-locking authorization,
  lookup and materialization. Existing write authorization/locking is unchanged. Fresh labels share the aggregate read
  view; original mutation/replay/batch labels remain frozen. GETs publish no changes or replay evidence.
- `ResourceReadController` owns HTTP validation and safe response mapping; `CanonicalResourceMapper` is pure with
  named root/entry/set helpers; persistence remains in the repository. Explicit Found/NotFound replaces nullable
  coordination, route delegation is typed, and UUID validation is shared. No new command modes, controller SQL,
  speculative framework or broad mutation refactoring. **Mandatory AC7a.6 clean/readable-code review passed** on the
  final verified state, independently of test success.

Coordinator reviewed baseline-relative production/test scope: seven changed/added production files and one new read
suite. Existing tests, V1–V6, main resources, Maven inputs/wrapper and supplied shared contract/catalogue compare
unchanged to `/tmp/gt-backend-task7a-baseline-903cic`. Backend files remain untracked; no empty-git-diff claim was used.
The pre-existing dirty independent shared-docs checkout was preserved, not reset or converted to a submodule.

## Findings and corrections

Draft review settled the read mapper's Boot-4/Jackson-3 choice, fixed read-result factory call sites, improved explicit
results/mapper readability, and corrected nested SQLTransientException handling and locale-independent status mapping.
Coordinator's initial assumption that Jackson 2 had no bean was mistaken: the baseline already had authObjectMapper;
it was not a confirmed startup defect. Production package and final real HTTP runs verified the selected read path.

QA authoring corrections included domain-valid completed-set fixtures, tombstone creation, exact pinned UUID, stored
SyncChange envelope parsing, null-safe expected equipment data, spy varargs, snapshot SQL bind count, and millisecond
business timestamps. None relaxed production requirements. Independent QA then identified a remaining finished
workout tombstone fixture that was invalid before deletion. Coordinator required a valid completed finished root;
QA made that bounded fixture-only correction and repeated both final runs. The unchanged 404 assertions passed.
No unresolved blocking finding remains. Details: `docs/task-7a-review.md`, `docs/task-7a-worker.md`, `docs/task-7a-qa.md`.

## Fresh verification

All Maven operations were serialized through `/tmp/gt-backend-task7-build.lock`; relevant inputs were frozen.

| Evidence | Result | State/artifacts |
|---|---|---|
| Untouched-current-input baseline full suite | 137 / 8 suites; zero failures/errors/skips | `/tmp/gt-backend-task7a-qa-baseline/` |
| Worker build-only package | Passed; no test compilation/execution | `/tmp/gt-backend-task7a-build-only-package.log` |
| Worker behavioral self-check | 115 / 4 suites; zero failures/errors/skips | Pre-final-tombstone-fixture state; `/tmp/gt-backend-task7a-selfcheck-reports/` |
| Independent QA final post-fix target | **127 / 6 suites; zero failures/errors/skips** | `/tmp/gt-backend-task7a-postfix-targeted/` |
| Independent QA final post-fix full suite | **148 / 9 suites; zero failures/errors/skips** | `/tmp/gt-backend-task7a-postfix-full/` |
| Coordinator final real HTTP/PostgreSQL spots | **5 / 1 suite; zero failures/errors/skips** | `/tmp/gt-backend-task7a-coordinator-spot/` |

QA targeted command:

`flock /tmp/gt-backend-task7-build.lock ./mvnw -q -Dtest=ResourceReadHttpIntegrationTest,DirectResourceHttpIntegrationTest,RoutineUpsertHttpIntegrationTest,PushPreflightTest,AuthHttpIntegrationTest,GoogleTokenVerifierTest test`

QA full command: `flock /tmp/gt-backend-task7-build.lock ./mvnw -q test`.

Coordinator selected five methods in `ResourceReadHttpIntegrationTest` with the Surefire `#method+method` filter:
`visibleExerciseReadsReturnPinnedBundleAndCompleteCustomCanonicalFields`,
`privateRootsAreIndistinguishableWhenMissingDeletedOrForeignAndAuthRemainsLive`,
`customRenameRefreshesAllReadLabelsWithoutChangingParentsOrRewritingPriorEvidence`,
`workoutReadKeepsRootAndChildrenFromTheSnapshotEstablishedBeforeWholeRootReplacement`, and
`transientAndUnexpectedReadFailuresMapSafelyAndRetryWithoutChangingState`. Exact invocation/output are in the spot log.

Coordinator independently parsed fresh copied XML, checked input/path membership and compared final QA/spot manifests.
The same 91-path relevant-input before/after manifests for both final QA runs and Coordinator spots hash to
`9dd8ca3ba78174a2ea2c97e3b7ea385b2e9545e30ba838a957469c7abacff17b`.
They retain production/tests/resources/build/README/IDE inputs and supplied normative shared-docs/catalogue, excluding
backend coordination/status docs. Copied reports are fresh; current target contains only the later spot subset.
Pre-fix green results and the earlier compile-only run are historical, not final-fixture evidence.

Final read-test SHA-256: `7872fc4b3e3f2ac4f750abfb3116838621df800bf8c950965f5dda15a5538ba2`.

## Criteria and limits

AC7a.1–7 passed: all three routes have independent canonical/visibility positives and missing/deleted/foreign negatives;
live auth/logout/disablement and other-session behavior are verified proportionately; rename/replay/no-effect evidence
passes; real gated reads retain consistent root/child/name or metadata/relationship views; validation/failure recovery
is safe; final structure and scope pass; independent targeted/full regression and Coordinator spots are green.

The read suite has 11 tests: ten exercise real HTTP/PostgreSQL and one is a clearly identified pure-mapper tertiary
supplement. The pinned seed contains no tertiary role, so no nonexistent pinned HTTP permutation is claimed.
Auth states are not exhaustively repeated on every route. Concurrency covers whole-root replacement and rename,
not every finish/delete permutation. Read persistence faults are injected after real queries, not an actual database
outage. These are reported coverage boundaries, not behavioral waivers. Optional derived volume was not added.

The existing post-Testcontainers auth-cleanup/Hikari lifecycle noise remains nonblocking and outside this task.
No local gymtracka access/migration/reset, secret-file access, staging/commit, deployment, service shutdown or layout
change occurred. HTTPS/OAuth/Android/release, pull/pruning/snapshots, account lifecycle and broad operations remain
unverified later work. Larger legacy mutation refactoring remains separately recorded and unauthorized.
