# Task 7b — Coordinator acceptance and integrated task-7 checkpoint

**7b is accepted. Task 7 is accepted for its bounded six-route read/browsing surface**, integrating previously accepted
7a with exercise search, workout summaries, saved routine lists and one shared signed stateless keyset mechanism.
Mandatory clean/readable-code review passed on the final frozen state. This is not complete-v1 or deployment acceptance.
Tasks 8–10 and dedicated legacy refactoring remain unauthorized.

## Authority, team and baseline

Behavior/criteria were defined upfront in `docs/task-7-contract.md`; execution assignment is
`docs/task-7b-contract.md`. Normative supplied shared docs/OpenAPI remain authoritative and unchanged. After 7a acceptance,
the user requested fresh Luna Worker/QA. Old idle actors exited natively; fresh gpt-6-luna High processes started in the
same panes/cwd/layout, acknowledged roles/safety/mcpls/readability and received bounded assignments. Coordinator authored
only contracts/status/review/acceptance, Worker production, QA independent tests. No fixed report quota applied.

7b baseline: `/tmp/gt-backend-task7b-baseline-w7uYPj`, initial manifest
`ccb07dd557579fe0c4f238f9cbebf28745ffe5308b4b50d5e6a806d2eee61bb7`. Final baseline-relative scope is two modified
production files (UTC Clock wiring and focused summary mapper), five new cohesive production classes, and one new
browsing HTTP suite. Other accepted code/tests, V1–V6, main resources, wrapper/build inputs and supplied contract/seed
compare unchanged. Backend remains untracked; independent dirty shared-docs was preserved, never reset/submoduled.

## Accepted implementation and structural gate

- GET `/v1/catalogue/exercises`: visible bundled/live owned canonical Exercises; literal Unicode-ROOT search across
  exercise/muscle/equipment names, OR-within/AND-across UUID filters, fixed catalogueVersion. Internal candidate chunks
  are keyset scans, not result caps; returned canonical roots alone are materialized.
- GET `/v1/workouts`: owned live root summaries, optional exact status, startedAt DESC then native UUID DESC; no needless
  child hydration or authoritative volume storage.
- GET `/v1/routines`: owned live saved non-draft complete canonical routines, native UUID ASC; drafts remain available
  through accepted individual reads, not saved lists.
- One `ListPageTokenService` binds account, endpoint, normalized filter digest, effective limit, typed last key and
  original first-page +900-second expiry. Standard purpose-separated HMAC-SHA256 key derivation uses the existing stable
  configured AuthCrypto facility; no auth crypto/config change, per-process secret or token/list-session table.
  Canonical fixed-version encoding, strict shape/MAC/context checks and constant-time comparison precede expiry:
  invalid/context mismatch 400 invalid_page_token; matching authentic expiry at now >= expiresAt 410 page_expired.
- `ResourceBrowseService` uses one live authorized read-only repeatable-read page transaction. Continuation carries
  anchor AND original expiry; extra matching record determines hasMore; token anchors the last RETURNED record. Lists
  are live between requests, not frozen result copies, sync bootstrap, deletion evidence or repeatable export.
- Controller validation/response mapping, immutable normalization, dedicated endpoint SQL, sole token codec and pure
  canonical projection remain practically separate. Methods are cohesive with explicit key/continuation values,
  meaningful names, bounded loops and no controller SQL, new command modes or speculative handler framework.
  Existing mutation god method was not enlarged. **AC7b.5 / mandatory readability gate passed independently of tests.**

Draft expiry-loss/nullable-search coordination was corrected before checks. QA corrected a transcribed seed UUID,
continued query context, child-JVM port reservation and fixture overload. No expectations were weakened. A test-only
saved-list root-query gate verifies complete old root/children and fresh replacement. No production hook was added.
The 18-count claim was an accounting error: substring @Test also counted @Testcontainers; exact annotations/XML are
17, with no missing case or source change. No unresolved blocking finding remains.

## Final frozen verification

Every Maven operation used `/tmp/gt-backend-task7-build.lock` and relevant inputs were frozen. Fresh reports were copied
before later runs; stale test-generated reports alone were removed. Coordinator independently parsed copied XML and
compared final QA/spot path sets and before/after hashes.

| Check | Actual result | Artifacts |
|---|---|---|
| Worker main-only package | Passed; tests not compiled/executed | `/tmp/gt-backend-task7b-build-only-package.log` |
| QA final compile-only | Passed; no tests executed | `/tmp/gt-backend-task7b-qa-interleaving-compile/` |
| Worker behavioral self-check | 132 tests / 5 suites, 0 failures/errors/skips | `/tmp/gt-backend-task7b-selfcheck-reports/` |
| Independent QA final target | **144 tests / 7 suites, 0 failures/errors/skips** | `/tmp/gt-backend-task7b-qa-final-target/` |
| Independent QA final full | **165 tests / 10 suites, 0 failures/errors/skips** | `/tmp/gt-backend-task7b-qa-final-full/` |
| Coordinator real HTTP/PostgreSQL spots | **6 tests / 1 suite, 0 failures/errors/skips** | `/tmp/gt-backend-task7b-coordinator-spot/` |

QA target command:
`flock /tmp/gt-backend-task7-build.lock ./mvnw -q -Dtest=BrowsingHttpIntegrationTest,ResourceReadHttpIntegrationTest,DirectResourceHttpIntegrationTest,RoutineUpsertHttpIntegrationTest,PushPreflightTest,AuthHttpIntegrationTest,GoogleTokenVerifierTest test`

QA full: `flock /tmp/gt-backend-task7-build.lock ./mvnw -q test`.

Coordinator used the Surefire `BrowsingHttpIntegrationTest#method+method` filter for six cases: Unicode/literal/filter
search; workout timestamp/signed-bit-UUID ordering; normalized filters and another live session; fixed exact expiry and
context-before-expiry; gated saved-list root/children consistency; real JVM restart continuation. Exact command/log:
`/tmp/gt-backend-task7b-coordinator-spot/{command.txt,maven.log}`.

All final QA target/full and Coordinator spot relevant before/after manifests match at
`41b939b4d58fa5bb12a8f584e57bbbb49ecdbca69ac3005b2cdd9b62ced5e646` (89 paths: source/tests/resources/build/README
and supplied normative shared-docs/catalogue, excluding Git metadata and backend coordination docs). Final browsing-test
SHA-256: `2d0371227412371ddc6cac4c6eecf229f512901e8a16d7623e251dbe5bee1463`. Current target contains only later
spots; copied QA reports carry full totals. Source fingerprints/commands are in Worker and QA handoffs.

## Evidence boundaries and safety

AC7b.1–5 passed through independent route data/filter/order/token/expiry/live-auth/no-effect/concurrency/restart
coverage, explicit structural/scope review and integrated regression. Task-7 common canonical/auth rules preserve 7a,
all 14 direct mutations and 17 sync kinds; auth/persistence/catalogue suites remain integration supplements, not new
list-route evidence. Actual browser suite count is 17 real HTTP/PostgreSQL cases.

Coverage is representative, not every route/filter/state/fault cross-product. Filled default-100/max-200 and empty-result
stressing is stronger for exercise browsing; workout/routine tests use smaller populations, limit-1/200 continuation,
ordering and terminal behavior. Their shared limit/envelope handling and endpoint predicates were structurally reviewed;
large-history/empty permutations are explicit nonblocking coverage gaps, not changed behavior. No arbitrary cap was added.
Only one within-page writer permutation is gated. These lists do not provide snapshot/export guarantees. The pinned seed
has no tertiary role; 7a's tertiary mapper supplement remains distinct from actual pinned HTTP evidence.

Existing post-container auth-cleanup/Hikari lifecycle noise remains recorded. No local gymtracka connection/migration/
reset, secret-file access, staging/commit, deployment, schema or dependency change, shared-docs/layout change or existing
service shutdown occurred. Agent replacement was narrowly user-authorized and exercised at the accepted slice boundary;
no ongoing replacement permission follows. HTTPS/release OAuth/Android, pull/pruning/snapshots, account lifecycle and
broader operational/deployment acceptance remain later work. Worker and QA now await a new assignment.
