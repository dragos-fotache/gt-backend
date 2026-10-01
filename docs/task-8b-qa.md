# Task 8b — QA plan and baseline

## Authority and scope

QA owns `src/test/**` and this file. The fixed contract is `docs/task-8b-contract.md`; shared behavioral authority is the supplied `shared-docs/docs/backend-contract/README.md`, `openapi.yaml`, `service-protocol.md`, `retention.md`, `sync.md`, `domain-rules.md`, `authentication.md`, `client-compatibility.md`, `android-client-protocol.md`, and `acceptance-scenarios.md`. I read these, `shared-docs/README.md`, `shared-docs/catalogue/README.md`, `docs/task-8-contract.md`, and `docs/task-8a-acceptance.md` fully before inspecting new 8b implementation. The supplied shared checkout is normative/read-only.

This file records requirement-first coverage before new implementation inspection. No acceptance is implied by this plan or the baseline. QA will report missing callable/time/gate seams to Coordinator; Worker owns production code. No migrations, production/build/shared-docs/status edits or weakened assertions. Every later Maven command requires Coordinator agreement and a frozen slot.

## Initial full-suite baseline (untouched inputs)

The exclusive baseline completed before authoring: `flock /tmp/gt-backend-task7-build.lock ./mvnw -q test`, exit 0, **177 tests / 13 suites / 0 failures / 0 errors / 0 skipped**. Coordinator independently parsed fresh XML and rehashed inputs.

- Original starting-copy snapshot remains at `/tmp/gt-backend-task8b-baseline-ljes6evs`; QA run artifacts were copied (not moved) to `/tmp/gt-backend-task8b-qa-baseline-evidence-20260930T171026Z/`.
- Actual QA run manifests cover **91 paths** (not the snapshot's original 132-path input inventory); SHA-256 of each manifest: `682627c06af2f1801c2e84b07262649e6d762c664af2755cfcf0cf52beef7730`. Before/after manifests are byte-identical.
- The evidence directory contains `maven.log`, `exit-code.txt`, `manifest-before.sha256`, `manifest-after.sha256`, `report-files.txt`, and copied fresh `surefire-reports/` XML/text reports.
- Historical 177-test/13-suite reports in README and 8a docs are not this baseline's evidence.

## Requirement-first verification plan

**AC8b.1 — Exact eligible contiguous prefix.** Build production-like histories through accepted HTTP mutation surfaces on isolated PostgreSQL 16. With a controllable cutoff/time boundary, assert before/equal/after 90 elapsed-day eligibility, UTC duration, empty/already-pruned/all-eligible/all-ineligible behavior, indivisible multi-change batches, and a non-monotonic timestamp sequence where the first ineligible batch prevents pruning later older timestamps. Verify exact deleted and retained batches/change rows plus watermark, account isolation, and that per-change expiry cannot prune an otherwise-ineligible batch. Never create success evidence from fixture-only pruning.

**AC8b.2 — Real pull after pruning.** Use service-issued opaque tokens and real HTTP pull. Verify known pre-watermark token => typed 410 `cursor_expired` plus `fullResyncRequired`; equality/after boundaries remain usable; absent cursor starts at retained-start; all-pruned empty tail returns stable nonempty cursor; unknown/foreign tokens stay 400 `invalid_cursor`; unchanged usable old tokens do not expire by age. Assert complete-batch progress and no cleanup changes to revisions/committed boundary.

**AC8b.3 — Atomic rollback/retry and PostgreSQL races.** Inject a test-owned failure after child/change deletion but before watermark advancement; verify parent/change/watermark rollback and successful production-service retry. Use deterministic DB gates delegating to real transactions for publisher-vs-pruner, in-flight pull-vs-pruner and two cleanup invocations; assert coherent old/new views, no skipped committed publication, no watermark regression/double application, and bounded skip/retry for busy accounts. Avoid timing-sleep races. Source-review transaction participation and account-before-sync-state lock ordering.

**AC8b.4 — Replay and protected state survive.** Through real HTTP create accepted and durable rejected sync requests and direct-resource idempotency results; later change eligibility/winner, prune associated eligible history, and retry unchanged identities. Compare original accepted/rejected outcomes, canonical batch/status/body/labels; ensure changed identity conflicts and namespaces/accounts isolate. Independently assert domain roots/children, sole-active state, tombstones, root/child reservations, credentials/expiry and cursor-token registry are unchanged. Verify create/import cannot resurrect pruned tombstones.

**AC8b.5 — Bounded runner, progress, sanitization and restart.** Exercise production budgets for finite accounts and batches per call and progress across ticks. Include accounts whose leading history is ineligible so they cannot starve eligible work, disabled accounts, actual Spring scheduler invocation under deterministic controlled config, sanitized failure reporting and next-tick recovery. Restart a real service process against the same isolated PostgreSQL database and verify watermark, retained pull and replay persist. No process-local pruning state.

**AC8b.6 — Readability/maintainability evidence.** Keep tests independently requirement-oracled and cohesive. Record seams/test fixture design for Coordinator's review of small cleanup policy/orchestration/persistence responsibilities, clear outcomes, bounded SQL, explicit transaction/lock behavior and no mode flags/controller SQL/framework expansion. Report any difficult-to-test or awkward production structure; QA does not fix production.

**AC8b.7 — Frozen independent regression.** Once implementation and tests are stable, request an agreed frozen slot. Run dedicated pruning coverage, all 8a pull suites, accepted mutation/read/browse/auth regressions, then full suite; preserve exact commands, fresh XML suite/test/failure/error/skip totals, copied reports and before/after relevant manifests. Compare to baseline-relative paths/hashes. Coordinator owns structural review and independent HTTP/PostgreSQL risk spots on identical frozen inputs. No full Task 8/8c/deployment claim.

## Java navigation

Discovered and used the actual `mcpls` tools (`mcpls_get_document_symbols`, `mcpls_get_references`, `mcpls_get_hover`), not a jdtls namespace. The first symbol call returned the actual error `LSP server 'java' is still initializing (large project load in progress)`; retry succeeded and returned symbols. One references call through the batch wrapper returned a parameter-schema error; a corrected direct request succeeded (no references at the selected location). Hover and symbols worked. Diagnostics explicitly reported both new test files as `non-project file, only syntax errors are reported`; the restart-test diagnostic first returned an LSP internal error `-32603`, then succeeded on retry with that same non-project limitation. No semantic/build validation is claimed. Used read-only `read`/`rg` fallback; no tooling changed.

## Testability request to Coordinator/Worker

The Worker implementation supplies the callable Spring cleanup service and injected UTC `Clock`; PostgreSQL-owned triggers/advisory locks provide deterministic transaction gates, and test-owned child processes exercise the actual scheduled wiring. No production test hook was added by QA.

## Initial dedicated target (failed; no acceptance)

Coordinator-approved initial command:

```sh
flock /tmp/gt-backend-task7-build.lock ./mvnw -q -Dtest=SyncHistoryPruningHttpIntegrationTest,SyncHistoryPruningRestartIntegrationTest,SyncPullHttpIntegrationTest,SyncPullRetentionHttpIntegrationTest,SyncPullConsistencyHttpIntegrationTest test
```

Run directory: `/tmp/gt-backend-task8b-qa-initial-target-20260930T174405Z/`. Exact command/log/exit, fresh copied XML/text reports and before/after relevant manifests are saved there. Exit **1**; **23 tests / 5 suites / 3 failures / 0 errors / 0 skipped**. Before/after relevant manifests matched. Coordinator reviewed the copied failures:

- `pruningDoesNotChangePopulatedLiveAggregateRelationshipsChildrenOrActiveState`: the independently asserted fixture count expected one muscle row although its accepted payload deliberately creates primary plus secondary rows. Approved correction expects two and explicitly checks both `(muscle ID, role)` pairs before/after pruning.
- `acceptedAndRejectedSyncAndDirectReplaySurvivePruningWithoutRestoringOldWinner` and `scheduledRunnerRetriesSanitizedFailureAndRestartPreservesPrunedBoundaryAndReplay`: assertions compared JSON `toString()`, so serialized object-key ordering differed despite identical values and ordered arrays. Approved correction compares `JsonNode` trees for sync batches (including other-account replay), preserving every field and array order. Direct-resource original status/body comparisons remain byte-exact.

These were QA oracle defects, not production/replay findings. Production is frozen. The approved test-only corrections are recorded in the passing retry below. No criteria are waived.

## Dedicated target retry (passed; target only)

After Coordinator approved only the oracle corrections, the exact five-suite command was rerun in a separate slot. Evidence is saved at `/tmp/gt-backend-task8b-qa-initial-target-retry-20260930T175013Z/` (`command.txt`, `maven.log`, `exit-code.txt`, before/after relevant manifests, copied `surefire-reports/`, `report-files.txt`, `xml-summary.txt`). Exit **0**; **23 tests / 5 suites / 0 failures / 0 errors / 0 skipped**. Before/after relevant manifests match: **96 paths**, SHA-256 `6eea3454693c7bd1f1623e04a9aadc8dbaab5300c1decc3528fbce63feb3d9cd`. Corrections: muscle-role checks assert two rows with exact primary/secondary UUID-role pairs before and after pruning; sync replay batches (including other-account replay) compare `JsonNode` trees, preserving all values and array order. Direct-resource status/body comparisons remain byte-exact. This is targeted evidence only; combined regressions/full suite and acceptance remain pending.

## Authored QA coverage

Added only two dedicated PostgreSQL/HTTP suites under `src/test/**`:

- `SyncHistoryPruningHttpIntegrationTest` publishes batches via HTTP/direct-resource/sync routes and calls the production cleanup service. It covers exact cutoff and non-monotonic prefix behavior, multi-change deletion, pull token status/retained-start, expired per-change TTL irrelevance, empty/already-pruned accounts, rollback/fail-closed corruption, PostgreSQL advisory-lock races against real pull/publisher/second cleanup, sync and direct-resource replay, tombstone/root/child reservation and credential-expiry preservation, eligible-head fairness, disabled-account exclusion, and account/batch work budgets/backlog.
- `SyncHistoryPruningRestartIntegrationTest` launches the real scheduled Spring service in test-owned child processes against the same isolated PostgreSQL database. A test-owned PostgreSQL fault verifies atomic rollback and sanitized log output; after fault removal the next scheduled tick prunes, then a separate process restart verifies pull expiry, retained-start and original sync replay.

## Final independent frozen verification (passed; not acceptance)

Coordinator approved sequential target/full slots with production and tests frozen. Reports were copied before `target/surefire-reports` was cleared for the next invocation.

| Run | Exact command | Exit | Fresh result | Evidence |
|---|---|---:|---|---|
| Combined target | `flock /tmp/gt-backend-task7-build.lock ./mvnw -q -Dtest=SyncHistoryPruningHttpIntegrationTest,SyncHistoryPruningRestartIntegrationTest,SyncPullHttpIntegrationTest,SyncPullRetentionHttpIntegrationTest,SyncPullConsistencyHttpIntegrationTest,BrowsingHttpIntegrationTest,ResourceReadHttpIntegrationTest,DirectResourceHttpIntegrationTest,RoutineUpsertHttpIntegrationTest,PushPreflightTest,AuthHttpIntegrationTest,GoogleTokenVerifierTest test` | 0 | **167 tests / 12 suites / 0 failures / 0 errors / 0 skipped** | `/tmp/gt-backend-task8b-qa-final-target-20260930T175941Z/` |
| Full suite | `flock /tmp/gt-backend-task7-build.lock ./mvnw -q test` | 0 | **188 tests / 15 suites / 0 failures / 0 errors / 0 skipped** | `/tmp/gt-backend-task8b-qa-final-full-20260930T180155Z/` |

Both runs saved exact `command.txt`, `maven.log`, `exit-code.txt`, before/after relevant manifests, `report-files.txt`, fresh copied XML/text reports and per-suite `xml-summary.txt`. Each manifest contains **96 paths**, before/after identical, SHA-256 `6eea3454693c7bd1f1623e04a9aadc8dbaab5300c1decc3528fbce63feb3d9cd`, matching Coordinator/Worker's frozen-input hash.

Per-suite fresh totals (each had zero failures/errors/skips):

| Suite | Target | Full |
|---|---:|---:|
| `AuthHttpIntegrationTest` | 11 | 11 |
| `GoogleTokenVerifierTest` | 1 | 1 |
| `CatalogueImporterTest` | — | 7 |
| `AccountWriteRepositoryTest` | — | 7 |
| `CanonicalAggregateRepositoryTest` | — | 7 |
| `BrowsingHttpIntegrationTest` | 17 | 17 |
| `DirectResourceHttpIntegrationTest` | 15 | 15 |
| `ResourceReadHttpIntegrationTest` | 11 | 11 |
| `PushPreflightTest` | 24 | 24 |
| `RoutineUpsertHttpIntegrationTest` | 65 | 65 |
| `SyncHistoryPruningHttpIntegrationTest` | 10 | 10 |
| `SyncHistoryPruningRestartIntegrationTest` | 1 | 1 |
| `SyncPullConsistencyHttpIntegrationTest` | 4 | 4 |
| `SyncPullHttpIntegrationTest` | 6 | 6 |
| `SyncPullRetentionHttpIntegrationTest` | 2 | 2 |

## AC evidence and coverage limits

- **AC8b.1–2:** production cleanup tests use accepted HTTP/direct-resource/sync publication and opaque service-issued cursors; controlled UTC cutoff checks before/equal/after, whole multi-change batches, non-monotonic prefix stopping, expired per-change TTL irrelevance, exact watermark/history, known/unknown/foreign tokens, absent retained-start, and empty-tail behavior.
- **AC8b.3:** test-owned PostgreSQL failure after deletion/before watermark verifies rollback/retry; PostgreSQL advisory-lock gates coordinate real cleanup against HTTP pull, publisher, and a second cleanup. These are deterministic database gates, not service simulators.
- **AC8b.4:** accepted/rejected sync and direct-resource outcomes are retried after history pruning and changed eligibility/winner; tests preserve exact direct-resource status/body, compare sync replay trees including fields/ordered arrays, check account/namespace isolation, and verify rich live custom-exercise relationships, workout/routine children, active state, tombstones/reservations, credentials and expiry remain unchanged.
- **AC8b.5:** HTTP-published 501-batch backlog and bounded account-scan tests exercise per-tick budgets/progress; the account-budget policy fixture itself inserts schema-valid DB history directly. The scheduled child-process test covers sanitized failed-tick reporting, later scheduled recovery, and process restart against the same isolated PostgreSQL DB with durable watermark, pull and replay.
- **AC8b.6:** QA asserts behavior only. Coordinator's mandatory production readability/transaction/lock-order structural review remains required; these passing tests do not substitute for that gate.
- **AC8b.7:** the approved combined target and full suite passed on the same frozen 96-path manifest. Independent Coordinator risk spots/structural review and acceptance remain outside QA's claim.

The earlier first target's **3 failures** and precise test-oracle causes/corrections are retained above. They were assertion defects (primary+secondary fixture count and JSON object-key-order stringification), not production findings; the approved corrected target and final target/full runs all pass. Coverage remains representative, not exhaustive; no complete Task 8, 8c, full-v1, deployment, or acceptance claim is made. QA's assigned verification slots are complete; wait for Coordinator review/acceptance. No further test/source/build changes or Maven operations are authorized here.
