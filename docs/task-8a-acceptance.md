# Task 8a — accepted

**Coordinator accepts bounded slice 8a**: incremental pull and shared durable account-bound cursors, including mandatory structural/readability review. Fixed criteria and three-slice boundaries: `docs/task-8-contract.md`. Production pruning (8b), frozen snapshots (8c), later account/operational work and legacy refactoring are not authorized or accepted.

One fresh openai-codex **gpt-6-luna High Worker and QA** completed this slice. Coordinator owned contracts, integration/findings, structural review and acceptance, not production/tests. All roles preferred mcpls Java navigation; successful symbols/references/hover were supplemented by read-only inspection during initialization/non-project/internal-error responses. No tooling changes or agent context/compaction management. A Coordinator interruption was resumed without replacing either actor.

## Accepted behavior and scope

- `GET /v1/sync/pull` returns complete ordered retained batches strictly after an account-bound committed cursor. Limits count whole batches (1–200, default 100); actual lookahead determines hasMore. Empty pages preserve the supplied/retained-start boundary and always return a nonempty cursor.
- Absent cursor starts at durable retained-start, not full bootstrap. Unknown/foreign/malformed cursor is 400 invalid_cursor; a known boundary before pruned_through is 410 cursor_expired with fullResyncRequired:true. Equality remains valid; token age does not expire usable history.
- One actual REPEATABLE READ transaction includes live read authorization, sync state/watermark, selected batches/changes and response assembly. Rows are bounded by snapshotted committed visibility; cursor gaps, empty batches and positional holes fail closed rather than silently advancing. Historical upserts use stored JSON/labels, never current aggregate rows.
- Shared `SyncCursorRegistry` reuses the V5 opaque token registry, including stable zero/retained-start issuance. Concurrent first registration retries a fresh whole transaction after metadata conflict/40001, with bounded exhaustion returning 503. Only token metadata may be added by a read; domain, revision, publication, replay and credential state remain unchanged.
- Existing publisher delegates cursor issuance without changing mutation algorithms, account/session fencing or stored original replay. No migration/build/configuration/shared-contract change.

Baseline-relative production scope is three new files (`SyncPullController.java`, `SyncPullService.java`, `SyncCursorRegistry.java`) and bounded `RoutineUpsertExecutor.java` injection/delegation/removal of its former issuer. QA added only `SyncPullHttpIntegrationTest.java`, `SyncPullRetentionHttpIntegrationTest.java`, `SyncPullConsistencyHttpIntegrationTest.java`. Existing tests, all main resources/applied migrations, build files and the supplied shared-docs checkout are byte-identical to the starting baseline. Backend remains untracked; empty git diff is not evidence.

## Fixed acceptance evidence

| Criterion | Evidence |
|---|---|
| AC8a.1 | Direct and sync publication of all three root kinds; draft/saved routines, active/finished workouts, rich custom metadata, ordered multi-entry/multi-set saved routine, null/zero/rest distinctions, historical data after rename/replacement/deletion, indivisible active replacement. Independent request/domain-derived oracles. |
| AC8a.2 | Empty account/tail; limits 1/default/200 and 201 batches; exact/partial terminal pages, no-gap traversal; push and same-account cross-session cursors; concurrent first zero issuance with one stable token. |
| AC8a.3 | Atomic isolated test-only prefix/watermark fixtures establish before/equal/after/null/all-pruned outcomes; aged valid and unknown/foreign tokens remain distinct. Not evidence of a production pruning job. |
| AC8a.4 | Pull-route missing/invalid/expired/revoked/disabled auth, query/body errors, optional read key, protected state/reservation/replay/credential checks. Existing auth suites supplement actual logout/provider behavior; no live OAuth claim. |
| AC8a.5 | Gates delegate to real JdbcTemplate and observe uncommitted HTTP publication plus PostgreSQL unique-index wait; whole committed views and stable registration. Two actual child-JVM processes preserve cursor/history. Test-owned database triggers verify safe transient/unexpected failures, rollback and unchanged retry. |
| AC8a.6 | Coordinator reviewed all changed production: separate HTTP policy, cohesive read/persistence/decoding stages and shared registry; no god-method growth, flags, controller SQL or speculative framework. Passed after bounded error-classification correction. |
| AC8a.7 | Worker build/self-check, independent fresh targeted/full QA, Coordinator manifest/XML checks and seven independent risk spots on identical frozen relevant inputs. |

Exact coverage/commands and limits are in `docs/task-8a-qa.md`; structural findings/history in `docs/task-8a-review.md`; Worker evidence in `docs/task-8a-worker.md`.

## Verification

Every Maven operation used `flock /tmp/gt-backend-task7-build.lock` with separately agreed appropriate input freezes. Reports were copied before target was overwritten.

| Run | Fresh result | Artifacts |
|---|---|---|
| Initial full baseline | 165 tests / 10 suites, exit 0 | `/tmp/gt-backend-task8a-baseline-20260930T150123Z/` |
| Worker build-only | package exit 0; no test compilation/execution | `/tmp/gt-backend-task8a-worker-build-20260930T151836Z/` |
| Corrected dedicated QA target | 12 tests / 3 suites, exit 0 | `/tmp/gt-backend-task8a-qa-initial-target-20260930T161603Z/` |
| Worker behavior self-check | 116 tests / 6 suites, exit 0 | `/tmp/gt-backend-task8a-worker-selfcheck-20260930T162018Z/` |
| Final independent QA target | **156 tests / 10 suites, exit 0** | `/tmp/gt-backend-task8a-qa-final-target-20260930T162426Z/` |
| Final independent QA full | **177 tests / 13 suites, exit 0** | `/tmp/gt-backend-task8a-qa-final-full-20260930T162611Z/` |
| Coordinator risk spots | **7 tests / 4 suites, exit 0** | `/tmp/gt-backend-task8a-coordinator-spot-9jWYnT/` |

All passing behavioral runs above had **zero failures/errors/skips**. Final relevant before/after manifests are identical (91 paths), SHA-256 `b0b9df36e20f4e231c86f98335c7e860c183bf9eaaa8d0c140a716e8105de2aa`, across corrected dedicated QA, Worker behavior, final QA target/full and Coordinator spots. Coordinator independently parsed copied XML and rehashed inputs. These hashes describe frozen verification before final status-only README/docs updates; production/test/build/normative inputs did not change afterward. Current target contains only the later seven spots; copied QA reports preserve the full totals.

Coordinator spots covered historical lifecycle, saved ordered history, retained-boundary distinctions, in-flight publication, concurrent zero registration, failure rollback/retry, and existing direct-resource durable replay. Exact selection is saved as `command.txt` with log/exit/manifests/reports in the spot directory.

Starting copy: `/tmp/gt-backend-task8a-baseline-qntn15_q`, 121-path manifest `3d9c88e024d5df34939afbeeba1154a8d668f1fa4ef03ac0553da2378de4478f`. Baseline behavioral manifest uses its separate 85-path relevant set; do not conflate those inventories.

## Findings, limits and safety

One production finding (broad parsing RuntimeException → 400) was corrected to distinguish validation from unexpected/transient failure. Worker corrected an inaccurate tracked-diff claim. QA-only compile/fixture/oracle mistakes (package shadowing, finished-as-active replacement, DELETE optional record, wrong zero/count, numeric/strict-after cursors) were fixed without relaxing requirements. Earlier failed runs remain recorded history, not final evidence. No blocking finding remains.

Coverage is representative rather than every auth/interleaving/corruption combination. Tests directly exercise concurrent zero issuance, not a separate concurrent missing retained-start permutation; the same registry handles both. Stored-history corruption detection is source-reviewed, not a comprehensive destructive-corruption suite. Pruning races/90-day scheduling belong to 8b; snapshots/baseline bootstrap belong to 8c. Existing post-Testcontainers Hikari/auth-cleanup connection noise remains recorded lifecycle debt. No deployment/HTTPS/release OAuth/Android engine/full-v1 acceptance.

No local gymtracka database/secret-file access, applied-migration change, staging/commit, supplied shared-docs edit, service shutdown or layout change occurred. Worker and QA are idle awaiting assignment; do not begin 8b/8c without authorization.
