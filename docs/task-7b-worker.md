# Task 7b — Worker production handoff

## Scope and status

Coordinator approved the design checkpoint and released the production freeze. The bounded 7b implementation is complete and held stable for Coordinator review. Worker changes are limited to `src/main/**` and this report. QA retains `src/test/**` ownership; no tests were edited. The first build-only package slot and first targeted behavioral self-check have passed on matching frozen inputs. The targeted Maven run compiled and executed the selected tests; broader independent/full verification remains outstanding.

Starting baseline: `/tmp/gt-backend-task7b-baseline-w7uYPj`, input manifest SHA-256 `ccb07dd557579fe0c4f238f9cbebf28745ffe5308b4b50d5e6a806d2eee61bb7`. A baseline-relative source-tree comparison shows only the following production paths added or changed; every other `src/main/java/**` file matches the baseline:

- Changed `src/main/java/com/dragosfotache/gtbackend/GtBackendApplication.java` — adds a UTC `Clock` bean.
- Changed `src/main/java/com/dragosfotache/gtbackend/resource/CanonicalResourceMapper.java` — adds a focused `WorkoutSummary` mapping; existing canonical full-resource mappings are reused.
- Added `src/main/java/com/dragosfotache/gtbackend/persistence/ResourceBrowseRepository.java`.
- Added `src/main/java/com/dragosfotache/gtbackend/resource/BrowseQueries.java`.
- Added `src/main/java/com/dragosfotache/gtbackend/resource/ListPageTokenService.java`.
- Added `src/main/java/com/dragosfotache/gtbackend/resource/ResourceBrowseController.java`.
- Added `src/main/java/com/dragosfotache/gtbackend/resource/ResourceBrowseService.java`.

SHA-256 of the stable production files:

| File | SHA-256 |
|---|---|
| `GtBackendApplication.java` | `63690e8f3e3c46d11f3d03b55b9b703d10ed749ec94e433347220c7ab9e291e1` |
| `CanonicalResourceMapper.java` | `a142a22a2e94bafa27a976d18f03e4164d262a69c37ae318ce1dbcb3deddb06c` |
| `ResourceBrowseRepository.java` | `36be67f47d648a6c826b14bce8f2c35950df43aabcaf238a80ec73175493da8e` |
| `BrowseQueries.java` | `1c5cac4514a1e973c6fd458e4aa36794f8a48691a59a2861dfa72a2d2996fc6e` |
| `ListPageTokenService.java` | `1b8bae3923f36e9cd9dbcf148c6853d894d0ce863b876387ff2436d4361d33be` |
| `ResourceBrowseController.java` | `5840ce5c2267851c61eac654d5a07c688d9d52822ae953751689e47493fc33d0` |
| `ResourceBrowseService.java` | `5771eefb9928088d529ae159dd498f1ac3713d9ba6024fcf17fa626ccf6adfb0` |

## Implementation boundaries

- `ResourceBrowseController` exposes only GET `/v1/catalogue/exercises`, `/v1/workouts`, and `/v1/routines`. It validates known/repeated query syntax, scalar cardinality, exact status values, canonical UUIDs, integer/range limits, and nonempty GET bodies; safe token/auth/validation/transient/unexpected errors map to the agreed typed status/code pairs. Canonical JSON is serialized with the configured Boot 4 Jackson mapper.
- `BrowseQueries` normalizes absent and empty q to the same empty string, lowercases nonempty q with `Locale.ROOT` without trimming, and sorts/deduplicates UUID filter sets using canonical UUID text order. Effective limit is bound, so omitted and explicit 100 are equivalent.
- `ListPageTokenService` is the single token primitive for all endpoints. It obtains a stable page-only key using the existing public `AuthCrypto.digest("GymTracka list page signing key v1")`; `AuthCrypto` and accepted auth behavior were not changed. It signs a fixed-version binary payload with HMAC-SHA256 and constant-time MAC comparison. Context bytes use standard int-length-prefixed UTF-8, grouped sorted UUID sets, and a SHA-256 digest; there is no `writeUTF` cap. The fixed-size URL-safe payload decoder rejects unsupported versions, malformed/noncanonical encoding, wrong anchor shape, invalid timestamp nanos, and trailing bytes. It binds owner, endpoint, digest, limit, sort anchor and original expiry; context comparison is constant-time and occurs before expiry classification. The returned `Continuation` carries both anchor and original `expiresAt`. A first page takes expiry from the injected service UTC clock plus exactly 900 seconds; continuation tokens preserve that instant and expire at `now >= expiresAt`. No ephemeral key, secret/config read, token store or new dependency/configuration was added.
- `ResourceBrowseService` authorizes every page with the existing non-locking live read authorizer inside one read-only `REPEATABLE_READ` transaction. Every candidate/summary/root/child/current-name read for a page shares that snapshot. Token authentication is not treated as request authorization. The service emits a next token only when a real extra matching row exists; the anchor is the last returned row, never the extra match or last scanned candidate.
- `ResourceBrowseRepository` keeps summary and list query SQL separate from full aggregate materialization. Workout summaries select only root summary columns (no children) and use descending `(started_at,id)` strict-after keysets. Saved routines keyset over native PostgreSQL UUID ascending, then fully materialize displayed routine roots only. Exercise candidate SQL applies exact UUID filters with OR inside each `ANY(uuid[])` category and AND across categories, in native UUID ascending keyset order; no SQL lower/LIKE/regex decision is used for q. Candidate chunks are internal scanning batches only, not a result cap.
- Exercise candidate name matching uses Java `toLowerCase(Locale.ROOT).contains(normalizedQ)` on exercise, muscle and equipment names. This deliberately avoids assuming PostgreSQL casing/collation equals Java Unicode lowercase, and treats `%`, `_`, and escape characters literally. The scan continues until limit+1 matching candidates or exhaustion. Returned Exercise values are then fully materialized through the accepted canonical repository/mapping in the same snapshot. Empty q matches all SQL-filter candidates. Filter values with unknown valid UUIDs naturally match no candidates.
- Workout and routine responses use canonical sort order and required shape; routines exclude drafts and are never split/truncated. Catalogue responses carry exactly `gymtracka-catalogue-v1`; optional volume is omitted. All lists are live page-by-page, with no database schema/session/result-copy changes and no domain/revision/reservation/sync/replay/credential effects. No accepted mutation algorithms or V1–V6 were touched.

## First build-only slot

Coordinator granted one package-only slot with QA authoring tests concurrently. No files were edited during the slot. Exact command:

`flock /tmp/gt-backend-task7-build.lock ./mvnw -q -Dmaven.test.skip=true package`

Exit code: **0**. Maven package completed successfully with no compiler findings. Because `-Dmaven.test.skip=true` was supplied, test sources were not compiled or executed. Log: `/tmp/gt-backend-task7b-build-only-package.log` (SHA-256 `dc0778b1bfa725a25f5b27ecb1e068bcf12bde29006e94c2eb6dc90d668883aa`). Before/after manifests each contain the same 66 stable production/runtime/build/normative paths (all `src/main/**`, `.mvn/**`, `pom.xml`, `mvnw`, root `README.md`, supplied `shared-docs/docs/backend-contract/**` and `shared-docs/catalogue/**`; backend coordination docs deliberately excluded). They are byte-identical; both manifest SHA-256 values are `fbeaa972f33b39647c435e2a94d0c30544656c2278b917c27fd8887512ea3a8b`. Manifest files: `/tmp/gt-backend-task7b-build-before.sha256` and `/tmp/gt-backend-task7b-build-after.sha256`.

## First behavioral self-check slot

Coordinator granted the first frozen-input behavioral slot. The exact invocation was:

`flock /tmp/gt-backend-task7-build.lock ./mvnw -q -Dtest=BrowsingHttpIntegrationTest,ResourceReadHttpIntegrationTest,DirectResourceHttpIntegrationTest,RoutineUpsertHttpIntegrationTest,PushPreflightTest test`

Exit code: **0**. Before and after manifests each contain the same 76 source/test/resource/build/normative paths (`src/main/**`, `src/test/**`, `.mvn/**`, `pom.xml`, `mvnw`, root `README.md`, supplied `shared-docs/docs/backend-contract/**` and `shared-docs/catalogue/**`; backend coordination docs deliberately excluded). They are byte-identical; both SHA-256 values are `661cb5b5949f4bb339e0339db5d341da505ed500d46f5675fbcfff24efe3f9a1`. The targeted `BrowsingHttpIntegrationTest` hash before the run was the supplied `2d0371227412371ddc6cac4c6eecf229f512901e8a16d7623e251dbe5bee1463`.

Fresh copied XML totals (zero failures/errors/skips in each suite):

| Suite | Tests |
|---|---:|
| `BrowsingHttpIntegrationTest` | 17 |
| `ResourceReadHttpIntegrationTest` | 11 |
| `DirectResourceHttpIntegrationTest` | 15 |
| `RoutineUpsertHttpIntegrationTest` | 65 |
| `PushPreflightTest` | 24 |
| **Total** | **132** |

The actual report total is **132**, not the nominal 133; Surefire recorded 17 browsing cases. Coordinator resolved the nominal 18-case count: the source has 17 exact `@Test` annotations, and naive substring counting included `@Testcontainers`. There is no missing testcase or source change; all executed testcases passed. Reports were copied before any subsequent Maven operation to `/tmp/gt-backend-task7b-selfcheck-reports/` (five XML and five text reports). Log: `/tmp/gt-backend-task7b-selfcheck.log` (SHA-256 `b1f9b57ada4895c33c505aa42af475b5fc926eab29f954d811fe2b607427952d`). Input manifests: `/tmp/gt-backend-task7b-selfcheck-before.sha256` and `/tmp/gt-backend-task7b-selfcheck-after.sha256`.

This was Worker self-check only. It is not independent QA or final acceptance.

## Navigation, blockers and unexecuted verification

`mcpls` document-symbol calls succeeded for the new token, query, repository, service and controller classes. Diagnostic calls initially returned an actual Java-server initialization error and then internal errors; subsequent successful diagnostic calls marked the new classes “non-project file, only syntax errors are reported” with no syntax diagnostics. Diagnostics for the modified application and mapper returned an actual `-32603 Internal error`. I used read-only source/schema review as fallback; no tooling/configuration changes were made. The LSP results do not establish semantic compilation.

No production blocker was observed in the passing selected suites. Coordinator resolved the 17-versus-18 testcase count as annotation counting, with no coverage gap. The self-check ran real HTTP/Testcontainers PostgreSQL suites and included the browsing suite's authored ordering, filters, token, expiry, restart, authorization, state-effect and list-read consistency cases; exact executed testcase names are in the copied XML. This is not independent QA or complete 7b/task-7 acceptance. Full regression, independent review/verification, Coordinator spots and final structural sign-off remain outstanding. Shared-docs, migrations, build inputs, auth crypto implementation, tests and unrelated production code remain untouched.
