# Task 8a — Coordinator review (accepted)

Scope and fixed acceptance: `docs/task-8-contract.md`. Coordinator owns contracts/integration/findings/structural review/acceptance, not production or test authoring. 8a only is authorized; no pruning jobs/snapshots/later work.

## Baseline and navigation

Baseline copy: `/tmp/gt-backend-task8a-baseline-qntn15_q` (121 paths, manifest `3d9c88e024d5df34939afbeeba1154a8d668f1fa4ef03ac0553da2378de4478f`). Fresh QA full-suite baseline: `/tmp/gt-backend-task8a-baseline-20260930T150123Z`, exit 0, 165 tests / 10 suites, zero failures/errors/skips. Coordinator independently parsed copied XML, checked before/after equality and rehashed current relevant inputs. 85-path behavioral manifest `c9a0227f08f66fc2b6fe7d6e9f14f0bd33d1012cfe8e379b9e4ae76aafcf998c`; its path set deliberately excludes backend status docs/unrelated shared Android docs. Root README authorization update preceded the run. Baseline production/test/build/shared-docs inputs match the supplied copy.

Coordinator prefers mcpls for Java navigation. Initial requests reported server initialization; retries returned document symbols for account writes, mutation executor and new pull/cursor classes, with reference and hover navigation. Empty workspace references are not proof that no callers exist; baseline-relative source inspection supplements LSP. No tooling/configuration changes.

## Final structural review

- Shared opaque cursor registry reuses V5 storage and keeps account/boundary lookup separate from HTTP policy and read orchestration. Whole RR transaction retry handles the concurrent registration visibility conflict rather than inventing a second token.
- Pull uses live-read authorization and one non-read-only RR transaction (token registration is the explicitly permitted metadata effect). It bounds rows to the snapshotted committed boundary, orders batches/positions, checks contiguous expected cursors and nonempty changes, and fails closed for required missing history.
- Upserts use stored JSON, not current aggregate rematerialization; deletes use stored envelope metadata. No current-domain read, product cap, schema change or applied-migration edit.
- Publisher baseline-relative change is limited to cursor dependency/delegation and removing the old private issuer. The compatibility constructor supports existing isolated restart tests; no new command branch/mode or mutation algorithm change.
- New production paths are small cohesive methods. HTTP validation is separated from repeatable-read orchestration; persistence window/order checks and stored-change decoding have distinct private methods, while cursor registration is shared in its own class. A further repository/framework layer is not warranted for this bounded path. Coordinator has now completed the production structural gate after the local classification fix: no blocker. Final behavioral verification and acceptance are recorded below and in `docs/task-8a-acceptance.md`.
- Full baseline-relative content inventory confirms exactly three new production classes, the bounded executor edit, and three new QA classes. All existing tests, src/main resources/migrations, build files and supplied shared-docs files are byte-identical to the baseline. Empty git diff is not used as evidence.

## Findings

**F8a.1 — incorrect failure classification (production, bounded fix).** Initial controller caught every RuntimeException during query parsing as 400 invalid_request. Coordinator requested known validation failures be distinguished from unexpected/transient failures. Worker changed to known InvalidRequest → 400, other runtime → safe 500/503. Source correction reviewed; fresh Worker/QA regressions and Coordinator spots passed afterward.

**F8a.2 — inaccurate verification claim (Worker report).** git diff --check cannot establish an edited tracked file in this entirely untracked backend. Worker corrected the report and used direct baseline-relative comparison plus actual whitespace/newline checks. No staged files/commits were introduced.

**Q8a.1 — invalid finished-workout replacement fixture (QA).** Initial rich-history test finished its purported old active root, then expected a subsequent start to tombstone that finished root. Coordinator requested a separate legitimate active replacement fixture while retaining finished-history assertions; expected batches/revisions must be independently adjusted, not weaken the replacement requirement.

**Q8a.2 — overly strict DELETE record oracle (QA).** Initial helper required record to be explicit null; OpenAPI permits omitted or null. Coordinator requested both valid representations while preserving deletion envelope assertions.

QA added dedicated retained-prefix and consistency suites covering test-only atomic watermark/prefix fixtures, concurrent zero registration (observed PostgreSQL unique-index wait), gated actual HTTP multi-change publication, two real child-JVM service processes and database trigger faults. Instrumentation delegates to actual JdbcTemplate; it gates calls rather than simulating persistence. Coordinator reviewed those source paths read-only after mcpls retries reported Java initialization. No production seam was required. Direct saved-routine and nontrivial child-order pull coverage was missing in the initial suite; QA added a focused case under the original AC8a.1 requirement, verified in all final runs.

**Q8a.3 — test compilation.** Initial target failed before tests because local `java` variable shadowed the java package in its own initializer. Approved rename to javaExecutable; subsequent target compiled.

**Q8a.4 — boundary/count fixture errors.** Retention fixture originally captured zero after publishing (actually a tail boundary); pagination counted a two-change replacement as two batches. Approved authentic zero capture before publication and six-batch expectation while retaining atomic-batch assertions. These tests passed in the subsequent run.

**Q8a.5 — opaque/strict-after cursor oracle.** Rich lifecycle test used internal numeric SQL cursor as API token and sought a change at/behind the supplied checkpoint. Coordinator requested actual HTTP-issued checkpoint before the desired revision, inclusion of every subsequent commit (including push), and exclusion only of genuinely earlier batches. QA-only correction is resolved and passed in all final runs; no production behavior waiver.

## Build slots and status

Initial baseline is closed. Worker build-only `flock /tmp/gt-backend-task7-build.lock ./mvnw -q -Dmaven.test.skip=true package` exited 0 with unchanged build-relevant inputs: `/tmp/gt-backend-task8a-worker-build-20260930T151836Z`. It compiled/packaged main only, not behavioral verification.

Coordinator resumed after an interruption without restarting either agent. Initial dedicated QA target `/tmp/gt-backend-task8a-qa-initial-target-20260930T160703Z` failed testCompile with no tests; 91-path manifests unchanged at `6acd2ad4c6aae83a2508c4d2a8f0f38645f8b7087ca69b96732bcdfa9e19343a`. After approved QA-only fixes, `/tmp/gt-backend-task8a-qa-initial-target-20260930T161021Z` ran 11 tests / 3 suites: 1 failure, 0 errors/skips; unchanged manifests `a674b7d29dae23fe5da3d3c7207c9b5dd6087a2d2b05ba1868c7a4a7278150aa`. Failure is the numeric-cursor fixture described above. After QA-only cursor corrections and saved/ordered-routine coverage, `/tmp/gt-backend-task8a-qa-initial-target-20260930T161603Z` passed all 12 tests / 3 suites, zero failures/errors/skips, unchanged 91-path manifests `b0b9df36e20f4e231c86f98335c7e860c183bf9eaaa8d0c140a716e8105de2aa`. Coordinator independently parsed XML and rehashed all current inputs. QA fixtures are resolved without production changes or weakened requirements.

Worker six-suite self-check passed 116 tests with zero failures/errors/skips: `/tmp/gt-backend-task8a-worker-selfcheck-20260930T162018Z`; unchanged 91-path manifests match the dedicated green input hash above. Coordinator independently checked fresh XML and all current hashes.

Final independent QA target `/tmp/gt-backend-task8a-qa-final-target-20260930T162426Z` passed 156 tests / 10 suites; full `/tmp/gt-backend-task8a-qa-final-full-20260930T162611Z` passed 177 tests / 13 suites. Coordinator spots `/tmp/gt-backend-task8a-coordinator-spot-9jWYnT` passed seven tests / four suites. All exited 0 with zero failures/errors/skips and identical unchanged 91-path manifests `b0b9df36e20f4e231c86f98335c7e860c183bf9eaaa8d0c140a716e8105de2aa`. Coordinator independently parsed copied reports and rehashed relevant inputs before final status-only README/docs updates. Source/test/build/normative inputs remain identical afterward.

**8a accepted:** `docs/task-8a-acceptance.md`. No blocking finding remains. Coverage limits include representative auth/interleavings, source-reviewed corruption checks rather than exhaustive destructive corruption, and no separate concurrent missing retained-start permutation. 8b production pruning and 8c frozen snapshots remain unauthorized. All verification slots are closed; Worker/QA await assignment.
