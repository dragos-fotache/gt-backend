# Task 8a worker handoff

## Implementation

- Added `GET /v1/sync/pull` HTTP validation and safe error mapping in `src/main/java/com/dragosfotache/gtbackend/sync/SyncPullController.java`: only scalar `cursor`/`limit`, ASCII decimal limit 1–200 (default 100), absent/zero-byte body, and the contract's distinct invalid-request, invalid-cursor, expired-cursor, auth, transient and unexpected errors.
- Added `SyncPullService` using one authorized PostgreSQL `REPEATABLE READ` transaction for account sync state, pruning boundary, complete ordered batch/change rows, and response construction. It uses stored upsert JSON (including historical labels) and stored deletion revisions only; it does not query current aggregate rows.
- Pull validates every expected cursor in its page plus one lookahead batch and validates nonempty, contiguous change positions and payload/envelope identity. Missing/corrupt required history fails closed as an internal error rather than yielding partial progress or a false terminal page.
- Added `SyncCursorRegistry` and switched the accepted publisher to use it. Existing `sync_cursor_tokens` storage and `(account_id,cursor)` uniqueness are reused; no migration/schema change. Resolution is account-bound and tokens have no age expiry. Issuance returns an existing token when visible; first-registration conflict uses `ON CONFLICT DO NOTHING`, then discards/retries the entire RR read in a fresh transaction. PostgreSQL `40001` serialization conflicts from RR unique-conflict visibility receive the same bounded retry. Exhaustion maps to `temporarily_unavailable`; it never returns a random competing token. Existing push-token format/registry and mutation algorithms are preserved.

## Files and scope

Production ownership changes are limited to:

- `src/main/java/com/dragosfotache/gtbackend/sync/SyncPullController.java` (new)
- `src/main/java/com/dragosfotache/gtbackend/sync/SyncPullService.java` (new)
- `src/main/java/com/dragosfotache/gtbackend/sync/SyncCursorRegistry.java` (new)
- `src/main/java/com/dragosfotache/gtbackend/sync/RoutineUpsertExecutor.java` (cursor issuance delegation only; legacy 5-argument constructor retained for existing isolated restart checks)

No tests, build configuration, migrations, shared-docs or other role-owned files were changed. QA owns the independent behavior tests and results.

## Checks and verification evidence

- Backend files are untracked; there are no tracked production edits, so `git diff --check` is not evidence here. Compared the existing `RoutineUpsertExecutor.java` directly against `/tmp/gt-backend-task8a-baseline-qntn15_q`; the baseline-relative diff contains only shared-registry injection/delegation and removal of its former private token generator. The three new Java files and this handoff doc are absent from the baseline. Direct whitespace/newline checks of all four changed production files and this doc found no trailing whitespace and confirmed final newlines.
- **Build-only slot passed:** `flock /tmp/gt-backend-task7-build.lock ./mvnw -q -Dmaven.test.skip=true package`, exit 0. The before/after build-input manifests were equal (117 paths; manifest SHA-256 `499d09e7347e30b694aa2c3b7ad6b0a55438ffce89a09d4da76590afcec21e4c`). Log, exact command, exit, manifests and packaged JAR are in `/tmp/gt-backend-task8a-worker-build-20260930T151836Z/`; JAR SHA-256 is `6f6a2cd786e3e15b2da3fbef9fc707747d6be2c15ba854665bcbefd638a2def2`. Tests were skipped; this is not behavioral evidence.
- **Worker behavior self-check passed:** exact command `flock /tmp/gt-backend-task7-build.lock ./mvnw -q -Dtest=SyncPullHttpIntegrationTest,SyncPullRetentionHttpIntegrationTest,SyncPullConsistencyHttpIntegrationTest,DirectResourceHttpIntegrationTest,RoutineUpsertHttpIntegrationTest,PushPreflightTest test`; exit 0, 6 suites, 116 tests, 0 failures, 0 errors, 0 skipped. Per-suite XML totals are copied with the fresh Surefire reports. QA's 91-path before/after manifests are equal, SHA-256 `b0b9df36e20f4e231c86f98335c7e860c183bf9eaaa8d0c140a716e8105de2aa`. Log, exact command, exit, manifests, reports and a preserved copy of pre-run Surefire reports are in `/tmp/gt-backend-task8a-worker-selfcheck-20260930T162018Z/`.
- mcpls initially reported Java server initialization; later document symbols worked, while diagnostics described a new file as non-project and other diagnostics returned `-32603`. Read-only source inspection was used; no tooling/configuration changes were made.

These are Worker build/self-check results, not final acceptance. Independent QA and Coordinator structural/risk review remain pending.
