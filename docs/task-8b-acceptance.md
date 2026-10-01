# Task 8b — accepted

**Coordinator accepts bounded 8b**: production 90-day contiguous-prefix sync-history pruning, bounded scheduling, preserved replay/cursor/domain state, and the mandatory structural gate. Fixed criteria: `docs/task-8b-contract.md`; findings/review: `docs/task-8b-review.md`. Combined accepted 8a/8b behavior is verified. **8c snapshots and later work remain unauthorized**; this is not complete Task 8/v1/deployment acceptance.

One fresh openai-codex gpt-6-luna High Worker and QA completed 8b in the existing panes. Coordinator owned contracts/integration/findings/structural acceptance, not production/tests. All preferred mcpls; initialization/non-project failures were reported and supplemented by read-only inspection, not tooling changes. No agent context/compaction management.

## Accepted implementation

- `SyncHistoryPruningService.pruneExpiredHistory()` samples the existing service Clock once and computes an elapsed 90-day cutoff. Defaults bound each invocation to 100 candidate accounts and 500 complete batches/account; these are work budgets, not domain/API caps.
- `SyncHistoryPruningRepository` selects eligible oldest account heads, excluding first-ineligible and disabled accounts. Each account executes in a real independent REQUIRES_NEW transaction: enabled account lock first, then sync-state lock, with busy-account SKIP LOCKED. A MATERIALIZED ordered batch window is limited before change aggregation and capped at the locked committed boundary.
- Stop at the first ineligible timestamp even if later timestamps are older. Validate required contiguous/nonempty history; delete complete change rows then parents and advance pruned_through atomically with checked counts. Equality with cutoff is eligible; per-change expires_at cannot bypass batch rules. No committed cursor allocation or root revision/publication.
- Preserve all cursor-token registrations (known old token remains typed 410, not unknown 400), original accepted/rejected sync/resource evidence, live roots/children/relationships/active state, tombstones/reservations and credentials/expiry. Failed account work rolls back; prior independent commits may stand.
- `SyncHistoryCleanupScheduler` actually invokes production cleanup, defaults enabled with 60-second initial/fixed delays, and emits only a fixed sanitized failure message without throwable details. Subsequent ticks recover after faults. application.yaml adds only approved SYNC_HISTORY_CLEANUP_* properties under sync.history.cleanup.

No HTTP endpoint, schema/dependency/build change, pull/mutation/auth refactor, process-local correctness state, snapshot/export/account-purge or broader operational work was added. Baseline-relative scope is exactly three new production classes, the scheduler-only application.yaml block and two new QA classes. All existing tests, accepted 8a/mutation/auth source, applied migrations and supplied shared-docs content/HEAD/status are unchanged.

## Fixed criteria and evidence

| Criterion | Evidence |
|---|---|
| AC8b.1 | HTTP-published histories with controlled Clock/timestamps cover before/equal/after cutoff, non-monotonic order, per-change expiry irrelevance, whole active-replacement batch, empty/all-eligible/already-pruned cases and other-account retention. |
| AC8b.2 | Actual HTTP pull after production cleanup verifies authentic before/equal/after/null/all-pruned boundaries, stable empty cursor, unknown/foreign distinction and preserved cursor registry; existing 8a tests supplement aged-token behavior. |
| AC8b.3 | Test-owned PostgreSQL watermark fault proves rollback after deletes and successful retry; advisory-lock gates coordinate cleanup with real HTTP pull/publisher and second cleanup, demonstrating whole committed views, busy skip and monotonic state. Missing required history fails closed; fixture isolation prevents test contamination. |
| AC8b.4 | Accepted/rejected sync and direct operations retain original results after changed eligibility/winner and production pruning; changed identity still conflicts, account/namespaces isolate, old winners are not restored. Rich live roots/children/relationships/active state, tombstones/reservations and credential expiry remain unchanged. Sync JSON tree comparison preserves fields/array order; direct original status/body remains exact. |
| AC8b.5 | HTTP-published 501-batch backlog drains across ticks; finite 100-account budget/progress and disabled exclusion use explicitly identified synthetic DB policy fixtures. Actual scheduled child-JVM failure/recovery and separate process restart preserve watermark, pull and replay; sanitized new-runner failure logging is tested. |
| AC8b.6 | Coordinator reviewed cohesive service/repository/scheduler, explicit account-first transaction boundaries, finite selection/window and readable checked persistence. Bounded SQL-window finding corrected; no legacy god-method growth or speculative framework. Passed independently of green tests. |
| AC8b.7 | Worker self-check, independent final target/full QA, Coordinator current-hash/XML checks and eight real HTTP/PostgreSQL risk spots on identical frozen relevant inputs. |

Coverage is representative, not every timing/corruption/fairness permutation. Core publication/pull/replay uses real HTTP/isolated PostgreSQL; synthetic account-budget fixtures are operational supplements, not API publication evidence. Test clock/gates delegate to real work, not a service simulator. Disabled-account pruning exclusion is not account-purge acceptance. New runner log tests do not establish system-wide log/backup/deployment compliance.

## Fresh verification

All Maven operations used project-wide `flock /tmp/gt-backend-task7-build.lock` with separately agreed input freezes. Fresh reports were copied before later runs overwrote target.

| Run | Result | Evidence directory |
|---|---|---|
| Initial baseline | 177 tests /13 suites, exit0 | `/tmp/gt-backend-task8b-qa-baseline-evidence-20260930T171026Z/` |
| Worker build-only | package exit0; no tests; predates SQL-window correction | `/tmp/gt-backend-task8b-worker-build-20260930T172523Z/` |
| Corrected dedicated target | 23 tests /5 suites, exit0 | `/tmp/gt-backend-task8b-qa-initial-target-retry-20260930T175013Z/` |
| Worker corrected-source self-check | 127 tests /8 suites, exit0 | `/tmp/gt-backend-task8b-worker-selfcheck-20260930T175512Z/` (reports/) |
| Final independent target | **167 tests /12 suites, exit0** | `/tmp/gt-backend-task8b-qa-final-target-20260930T175941Z/` |
| Final independent full | **188 tests /15 suites, exit0** | `/tmp/gt-backend-task8b-qa-final-full-20260930T180155Z/` |
| Coordinator spots | **8 tests /3 suites, exit0** | `/tmp/gt-backend-task8b-coordinator-spot-VRGuSP/` |

All passing runs have zero failures/errors/skips. Corrected dedicated, Worker self-check, final QA target/full and Coordinator spots have identical unchanged **96-path** before/after manifests: `6eea3454693c7bd1f1623e04a9aadc8dbaab5300c1decc3528fbce63feb3d9cd`. Coordinator independently parsed copied XML and rehashed inputs. These hashes describe frozen verification before final status-only README/docs updates; production/tests/build/normative inputs remain unchanged afterward. Current target contains only eight later spots; copied reports preserve full counts. Exact commands/logs/exits/manifests are in those directories and role reports.

Coordinator spots exercised exact prefix, midpoint rollback/retry, pull/publisher/cleanup concurrency, batch budget, populated-state preservation, original replay, actual scheduled recovery/restart and accepted 8a retained-boundary behavior.

## Durable overnight evidence

Final evidence, Worker results, failed/corrected targets and the intact starting baseline were copied outside temporary storage to `/home/dragos/.local/state/gymtracka-backend/coordinator-checkpoints/task-8b-20260930T182603Z/`, preserving their original directory names. It also contains `project-state/` with accepted code/build/docs and a verified `checkpoint-files.sha256`. Follow `docs/coordinator-handoff.md` for tomorrow's resume checklist. No secrets/local DB/Git checkout/mutable target or stale build JAR were included.

## Findings and safety

F8b.1 moved the bounded window before aggregation and constrained it to the committed boundary. QA fixed authentic checkpoint/eligibility/fixture isolation gaps before verification. Initial target `/tmp/gt-backend-task8b-qa-initial-target-20260930T174405Z/` compiled and ran23 tests with three oracle failures (two-role count and JSON object-property ordering); corrected precise assertions preserve all contractual fields, array ordering and exact direct-resource replay. Earlier failed evidence is historical, not final-state acceptance. Baseline artifact/hash confusion and Coordinator's initial wrong Worker report-directory lookup were corrected against actual saved files. No blocking finding remains.

Starting copy `/tmp/gt-backend-task8b-baseline-ljes6evs`: 132-path inputs manifest `0f75550f81105565deb22c1a097091d991cb60611161c939e5f8dc258592f7c4`; actual fresh baseline run uses 91 paths/hash `682627c06af2f1801c2e84b07262649e6d762c664af2755cfcf0cf52beef7730`. Do not conflate these inventories. Existing post-Testcontainers Hikari/auth-cleanup noise remains separately recorded debt.

No local gymtracka DB or secret-file access, applied migration/shared-docs change, staging/commit, deployment, unrelated shutdown or layout change. Worker/QA await assignment; 8c requires explicit authorization and its own fresh pair.
