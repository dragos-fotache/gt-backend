# Task 9c — QA baseline and requirement-first plan

## Authority and status

Assignment 1 from the Coordinator: exclusive frozen baseline full-suite run, then a requirement-first 9c plan. QA holds the only Maven slot for this assignment; Worker runs no Maven. This document records both the baseline evidence and the plan. No test authoring, production change, migration, configuration change, or further Maven run follows from this assignment. Slices 9a and 9b remain accepted; 9c implementation remains unauthorised except for this baseline and plan.

QA owns requirement-first tests and this report. Worker owns production. Coordinator owns contracts, findings, design approval, structural review, and acceptance. V1–V7 migrations are immutable. No commit, deployment, shared-docs edit, layout change, local `gymtracka` database access, or `~/.config/gymtracka-backend/dev.env` / secret access occurred.

Model and settings observed without change: this QA session runs as `meta/muse-spark-1.3-contributor` with high reasoning (`PI_PROVIDER=meta`, `PI_MODEL=muse-spark-1.3-contributor`, `PI_REASONING_LEVEL=high`). No tool or model setting was changed.

## Exclusive frozen baseline — green

Evidence root: `/tmp/gt-backend-task9c-qa-baseline-9c01/`.

Frozen inputs: 149 paths, manifest SHA-256 `a0e4379ccb87fb312b20fe1ceaf30bc270296822d2ffebff3f7c94937d33cb79`. The before manifest and after manifest are byte-identical. The manifest covers all executable, build, fixed, and shared inputs: every file under `src/` and `.mvn/`, plus `pom.xml`, `mvnw`, `docs/task-9-contract.md`, `docs/task-9a-contract.md`, `docs/task-9b-contract.md`, `docs/task-9c-contract.md`, `docs/shared-docs-inputs.sha256`, and every pinned path listed in `docs/shared-docs-inputs.sha256`. The capture script is `manifest.py` in the evidence root; it verifies each pinned shared input hash before recording and refuses to overwrite an existing manifest. Reports, evidence files, and this QA document are outside the behavioural freeze; fixed contracts are frozen.

Source control state, recorded before the run:

- Backend HEAD `af6e1f59a537112a2efe3585f8ddcb90c0e4885b` (`af6e1f5 sync work`); parent `36fe88d Establish accepted backend baseline through task 8c`. Branch `master`.
- Working tree holds the accepted 9b changes uncommitted: 10 modified paths (`README.md`, `docs/coordinator-handoff.md`, `docs/task-9-contract.md`, `docs/task-9b-contract.md`, four production files under `auth/`, `persistence/` and `sync/`, `application.yaml`, one auth test) plus 39 untracked paths (four 9b reports/worker files and the 9b account/ledger/V8/test additions). Exact lists are in `git-status.txt`. No input was edited during the run.
- Shared-docs HEAD `0bd414b6c1b819a26e2d5fca0bed6b6b5acf2cea`, working tree clean. All 27 pinned shared inputs verify (`shared-inputs-verify.txt`, every line OK). Exact records are in `git-log.txt`, `git-head.txt`, `git-status.txt`, `shared-head.txt`, and `shared-status.txt`.

Exact command, run exactly once:

```text
flock /tmp/gt-backend-task7-build.lock ./mvnw -q test
```

Start: `2026-10-02T13:34:06Z`. End: `2026-10-02T13:43:58Z`. Exit code: `0`. The exact command, UTC start, UTC end, and exit are retained in `exact-command.txt`, `start-utc.txt`, `end-utc.txt`, and `exit-code.txt`; the full log is `maven-baseline.log`.

Stale Surefire reports (34 XML from an earlier run) were copied to `stale-target-reports/` and removed from `target/surefire-reports` before the run. No `mvn clean` was executed. Fresh reports were copied to `fresh-reports/` immediately after the run.

Result parsed from fresh copied XML (`TEST-*.xml`): 304 tests across 34 suites, with 0 failures, 0 errors, and 0 skips. These are actual fresh results, not an oracle reuse of the 9b count; the count coincides with the accepted 9b full-suite total because no production or test input changed, only the added fixed `docs/task-9c-contract.md` manifest entry. The post-Testcontainers Hikari connection warnings in the log are the known separate lifecycle observation and did not affect the green result.

Freeze discipline: all 149 inputs frozen during the run with no edits, no assertion weakening, and no rerun in this slot. The after-manifest matches the before-manifest. The baseline slot is closed. The report was sent to the Coordinator without `--wait`.

## Requirement-first principles

Expected data will be derived independently from the normative shared contracts, never from production mappers or observed responses. Independent oracles use the backend-contract `README.md`, `account-lifecycle.md`, `authentication.md`, `retention.md`, `service-protocol.md`, `openapi.yaml`, `schema.md`, `domain-rules.md`, `client-compatibility.md`, `android-client-protocol.md`, `sync.md`, `acceptance-scenarios.md`, the deletion-receipt and export fixtures as illustrative shapes only, and `docs/task-9-contract.md` with `docs/task-9c-contract.md` for slice boundaries and common proof, wire, privacy, deadline, inventory, and approval rules. Existing assertions will be retained and never weakened to make fixtures pass.

All future 9c tests will use isolated Testcontainers PostgreSQL 16 and real HTTP for the implemented routes. External Google behaviour may use deterministic doubles with synthetic keys and stub certificates for protocol tests only; mocked Google results are not live-provider evidence and will be reported as such. Live Google, deployed HTTPS, release OAuth, and Android integration remain unverified external work.

No new production implementation was inspected for this plan. File-name level working-tree awareness from `git status` was unavoidable, but no Worker source, design, migration, or configuration content was read. The cases below come from the fixed 9c contract and shared contracts alone.

Ordinary deletion stays fail-closed without verified purge readiness. Isolated tests may explicitly enable acceptance paths only after an approved no-edit design and an explicit slot. A queue alone never satisfies the physical deadline. The separate 86,400-second physical purge deadline is not relaxed by snapshot-cleanup lag, backup lifetime, retries, restarts, skip-locked work, or temporary failures. If the deadline cannot be demonstrated under documented operating assumptions, QA will report a release blocker rather than reinterpret the policy.

Every future Maven operation requires an explicit slot, frozen inputs, and `flock /tmp/gt-backend-task7-build.lock`. Locking alone is not a source freeze. Before and after manifests will be captured with this window's `manifest.py` and compared with the frozen starting manifest. Exact commands, logs, exits, fresh copied XML, and actual before and after hashes will be preserved. Reports go when green or genuinely blocked, with one fire-and-forget Herdr message and no `--wait`.

Java navigation will prefer mcpls with read-only fallback and without tooling changes. The local `gymtracka` database and `~/.config/gymtracka-backend/dev.env` or real secrets will never be accessed. Only test-created processes and test-owned faults will be stopped or removed. Panes, working directory, layout, and services will be preserved. No context or compaction management will be performed.

## Requirement-first plan by acceptance criterion

### AC9c.1 Complete physical data removal

Build account A through accepted surfaces with rich live and tombstoned state: active and finished workouts with exercises and sets, saved and draft routines with children, owned custom exercises with muscle-group and equipment links, accepted and rejected sync and resource replay rows, several credentials and sessions, sync log/state/token rows, and frozen snapshots irrespective of normal TTL. Accept deletion through the real endpoint and invoke the actual purger. Then inventory every account-bearing store from the contract table (workout/routine/exercise rows and descendants, `processed_mutations`, `resource_mutations`, `sync_changes`, `sync_change_batches`, `account_sync_state`, `sync_cursor_tokens`, `sync_snapshot_records`, `sync_snapshots`, `auth_idempotency`, `auth_credentials`, `auth_sessions`, `google_identity_bindings` for A only, `root_id_reservations`, `child_id_reservations`, the ordinary `accounts` row, and any new 9a/9b lifecycle job, proof/admission, or cache state) and assert all of A's payloads, credentials, ordinary identity/state/reservation rows, and terminal transient state are gone. Only the permanent shared whitelist may remain. Assert the global catalogue and a populated unrelated account are intact by value comparison, not merely by row counts. Assert deletion order respects foreign keys (children before restricted parents, snapshot storage before restricted cursor references) and that V3 trigger boundaries, constraint enforcement, and the disabled-account anchor are honoured rather than bypassed. Anonymous pre-verification V4 challenges keep their own five-minute/consumption policy and other users' challenges are never swept. New 9a/9b account-bearing tables will be enumerated at the authorised baseline rather than assumed from the current list.

### AC9c.2 Deadline, bounds, and actual scheduling

Use controllable service time and backlog fixtures to verify exact boundary behaviour: well before the deadline, at the deadline edge, and after `deletedAt + 86,400 seconds`. Verify `purgeBy` is recorded once at acceptance and never moves on retry, restart, or partial progress. Verify finite per-invocation account and row budgets, deadline-prioritised ordering, measurable large-account and large-child-collection progress without new product caps, and busy-account retry that still completes before the recorded deadline in tested scenarios. Verify the actual Spring scheduled path invokes the production purger rather than a test-only runner. Verify temporary failures recover before the deadline in tested scenarios, and that an intentionally missed deadline is visibly reported as failure rather than recorded as success. Distinguish controlled capacity evidence from production capacity claims: document the backlog, budget, scheduling, priority, retry, concurrency, and deadline-breach reporting assumptions under which the tests pass, and keep any remaining deployment capacity, alerting, or outage constraint as an explicit release blocker. Never claim an unconditional production guarantee from bounded tests.

### AC9c.3 Crash, rollback, and durable resume

Persist purge intent at deletion acceptance, never in process memory alone. Inject test-owned failures after representative deletions and before phase or completion advancement, and assert the failed transaction rolls back while earlier valid committed phases stay committed. Assert retry resumes from durable progress without gaps, regressions, double-completion, skipped phases, reopened access, or false completion. Assert unexpected missing required state, no-op deletes, and transient faults are never presented as successful purge, and that completion commits only after an explicit completeness check over every account-bearing store. Stop the service process that accepted or advanced purge, restart against the same isolated database, and resume from durable intent and progress. Assert original `deletedAt`, `purgeBy`, digest versions, and receipt bytes are unchanged. Only test-created processes and faults are stopped or removed. Concurrent workers must not double-complete or regress progress; verify this with real lock and progress behaviour, not with a unit mock of the scheduler.

### AC9c.4 Recovery after actual purge and rollover

After purge completes, verify real HTTP returns the original receipt for identical accepted-deletion retries, including after proof/session expiry and after process restart. Verify fresh status can confirm old A after more than 90 days and after B signs up, using only minimal evidence and without restoring payloads. Verify changed identity against the same key conflicts, and that a new deletion key against owned deleted A yields the specified invalid state rather than a second record. Verify historical verification keys survive tested rollover (synthetic version 1 to version 2 preserves verification of old records) and that missing required keys fail closed. Verify no recovery path exposes credentials or domain data, issues a grant, changes the original deadline, depends on surviving ordinary auth/session/challenge/account rows, or implicitly signs in. The permanent ledger is the only durable source for these checks.

### AC9c.5 New-account and delayed-work isolation

Verify a distinct fresh sign-in creates empty B with a new internal account UUID for the same Google subject, both before A's purge finishes and after it completes. Run delayed A purge, proof, retry, and read activity while B holds its own rich state and sessions, and assert B's binding, sessions, roots, reservations, and replay remain intact. Scope every destructive statement by old account UUID; never sweep by provider identity, credential key, resource UUID alone, or latest binding, and never delete B's replacement binding by matching provider subject alone. Verify old A credentials, original exchange retries, cursors, snapshot and list tokens, and accepted-deletion replay cannot authorise or implicitly create B. Establish and test the fresh-attempt boundary for an old in-flight signup attempt so pre-deletion signup attempts are not silently reinterpreted as consent to create B after acceptance, using the real provider/account locks. Distinguish backend credential and replay fencing from Android cache responsibilities; backend tests will not claim Room cache clearing or remote device wiping.

### AC9c.6 Integrated races and no late copies

Use deterministic actual database and HTTP gates for purge and acceptance versus direct writers, sync writers, refresh and replay, snapshot capture and continuation, export before and during output, history/snapshot/auth cleanup, and a second purger. Verify only valid serialised outcomes: no post-disablement write commits, no new durable account-bearing copy after completion is declared, no hybrid successful export or snapshot, and no resurrected grant. Verify admitted exports stop later release when deletion is observed and that deletion before first output prevents release, without finalising partial JSON as a successful file. Recheck all account-write and read/export/snapshot boundaries so a request that began before disablement cannot create a new durable copy after purge completion; explain the live fence with real foreign-key and transaction effects rather than relying on a frozen read view. Respect implicit foreign-key locks and avoid artificial circular waits. Source review of participation and completeness is required; a representative green race alone does not substitute for it.

### AC9c.7 Minimal evidence, structure, and operational limits

Support Coordinator structural review of every new or changed production boundary, finite SQL work, checked progress, account filters, key-version handling, and fixed sanitised failure reporting. Permanent evidence must contain only the whitelist; in particular the ordinary old account record is removed or its strictly whitelisted minimal representation is explicitly justified, with no retained profile/creation data disguised as ledger content. Prohibit controller SQL, broad legacy refactors, request-body logging, mode-heavy auth coordination, unkeyed provider-subject hashes, raw token persistence, and any bypass of database protections. Privacy-safe system-wide logs, backup replication, and actual disaster restore remain explicit Task 10 work and are not accepted by a scheduler-log test. Operational limits, key-ring domain separation, retained verification versions, rollover behaviour, and failure on missing required keys will be reviewed against the approved no-edit design before authoring.

### AC9c.8 Frozen independent Task 9 acceptance

Close 9c with Worker self-check, independent dedicated 9a/9b/9c and all accepted auth/API/sync regressions, the full suite, and Coordinator structural review, inventory/deadline/coverage review, and real HTTP/PostgreSQL spots, all on identical final frozen inputs. Preserve exact commands, fresh XML counts and exits, manifests, and copied reports. Re-run final targets after any changed test or production input. Close all blocking findings and explicitly distinguish functional acceptance from deployment release readiness. No complete-v1, legal-compliance, Android, or backup/restore claim follows from this slice.

## Independent oracles and retained assertions

Oracles will be hand-authored from the OpenAPI `DeleteAccountRequest`, `AccountDeletionReceipt`, and `AccountStatus` shapes, strict UTF-8 object parsing without duplicate keys or coercion, canonical lowercase UUID rules, shared error-code mappings (`invalid_google_credential`, `not_found`, `invalid_request`, `validation_error`, `idempotency_key_reused`, safe 503/500 classification), exact-byte request identity including whitespace and property order, service-time boundaries (`now >= expiresAt` for fresh proof; `purgeBy = deletedAt + 86,400 seconds`; status and ledger evidence with no age TTL), and the contract inventory and order constraints above. `Cache-Control: no-store` and sanitised error bodies apply to every lifecycle response including errors.

All accepted 9a export, 9b deletion/status/ledger/fence, and earlier sync, resource, browsing, pull, pruning, and snapshot assertions will be retained as regression supplements and distinguished from direct 9c route evidence, controlled database fixtures, and external Google doubles. 9b acceptance and review documents will be used as executed-behaviour references, never as oracles for purge behaviour the 9b slice explicitly did not implement.

## Limits of assignment 1

Assignment 1 covered the frozen full-suite baseline and the requirement-first plan above only. The baseline slot is closed.

## Authored suite inventory (assignment 2)

Authored 8 new test files, 21 tests total, covering AC9c.1 through AC9c.7. No production, migration, configuration, shared-docs, or existing-test edit occurred; all existing assertions are retained unweakened. No Maven execution occurred in this authoring slot; compilation is verified later in the dedicated window. All suites use isolated Testcontainers PostgreSQL 16 with real HTTP and mock Google only at the external boundary with synthetic tokens, reported as such in each file header.

| File | Criterion | Tests |
|---|---|---|
| `AccountPurgeTestDriver.java` | Seam for all suites | Test-owned purge invocation; reflection-only, no production purge import |
| `AccountPurgeRemovalHttpIntegrationTest.java` | AC9c.1 | `purgeRemovesAllAccountBearingRowsAndKeepsLedgerAndCatalogue`, `purgeKeepsUnrelatedAccountStateByteIdentical` |
| `AccountPurgeDeadlineSchedulingHttpIntegrationTest.java` | AC9c.2 | `receiptDeadlineIsExactly86400SecondsAfterAcceptanceAndNeverMoves`, `scheduledPurgeOnEmptyBacklogIsSafeNoOp`, `largerAccountDrainsWithinFiniteInvocations` |
| `AccountPurgeCrashResumeHttpIntegrationTest.java` | AC9c.3 | `unknownAccountPurgeIsSafeNoOp`, `repeatedPurgeIsIdempotentWithUnchangedReceipt`, `scheduledPurgeAfterCompletionIsStableNoOp` |
| `AccountPurgeRecoveryRolloverHttpIntegrationTest.java` | AC9c.4 | `identicalRetryAfterPurgeReturnsOriginalReceipt`, `freshStatusConfirmsDeletedAccountAfterPurgeWithoutNewState`, `changedIdentityAgainstSameKeyConflictsAfterPurge`, `newKeyAgainstDeletedAccountDoesNotAcceptAfterPurge` |
| `AccountPurgeNewAccountIsolationHttpIntegrationTest.java` | AC9c.5 | `freshSignupBeforePurgeCreatesEmptyBWithNewUuid`, `delayedPurgeKeepsBRichStateIntactAndFencesOldCredentials`, `oldAcceptedReplayAfterBSignupReturnsAReceiptWithoutTouchingB` |
| `AccountPurgeRaceFencingHttpIntegrationTest.java` | AC9c.6 | `postAcceptanceWriteIsDeniedAndPurgeStillCompletes`, `concurrentIdenticalDeletesYieldOneLedgerRow`, `gatedAcceptanceDeniesRacingWriterWithoutSideEffects` |
| `AccountPurgeLedgerMinimalityHttpIntegrationTest.java` | AC9c.7 | `ledgerExposesOnlyWhitelistedColumns`, `ledgerHoldsNoRawIdentityOrPayloadAfterPurge`, `rejectedDeletionWritesNoLedgerRowAndLeavesAccountLive` |

Seam assumption: suites invoke production purge only through `AccountPurgeTestDriver`, which resolves the production entry point reflectively from the candidate bean names `accountPurgeService`, `accountPurger`, `purgeService` (overridable with `account.purge.bean` / `account.purge.method` system properties). If the approved Worker design names the entry point differently, only the driver is realigned in the verification window; no suite file changes. A missing entry point fails loudly instead of passing silently. New Worker production files observed as present in the working tree were not read; oracles remain independent of them.

Oracles are hand-authored from the fixed 9c contract and shared contracts: exact `purgeBy = deletedAt + 86,400 seconds` receipt and ledger checks, full per-store inventory emptiness for A, catalogue and unrelated-account value integrity, keyed-digest (never raw) identity evidence, exact ledger column-set equality, `409 idempotency_key_reused` / `409 invalid_state` / `401 invalid_google_credential` wire codes copied from retained 9b behaviour, `no-store` on every lifecycle response, and old-account UUID scoping for all destructive expectations.

Explicit gaps for the verification window: phase-level fault injection inside purge internals awaits Worker purge-phase hooks; exact non-2xx writer-denial codes are asserted as a class (`/100 != 2`) until the frozen run confirms them; v1-to-v2 key rollover relies on the retained 9b status suite; production capacity beyond the tested backlog stays a release question.

## Test-only fixture corrections (assignment 3)

Source: Worker self-check `/tmp/gt-backend-task9c-worker-selfcheck-9c01/fresh-reports/` (21 new tests: 4 failures, 17 errors pre-correction; remaining errors are the systematic purge `internal_error` the Worker is diagnosing). No Maven execution occurred in this correction slot. Changed files, all test-only:

- `AccountPurgeRemovalHttpIntegrationTest.java`: the empty-workout finish fixture returned `422 validation_error`, which is correct shared behavior (finish fails without a completed set per `domain-rules.md`). The fixture now records one completed set through the accepted PUT shape before finishing and asserts `200` with `finished` status.
- `AccountPurgeRaceFencingHttpIntegrationTest.java`: the racing routine create is not denied by the held deletion scoped-key gate (it takes none of deletion's keys), so demanding denial was a wrong premise. Renamed to `gatedAcceptanceLetsNonContendingCreateSerializeThenPurgeDrainsIt`: it now asserts the racing create as a valid `201` serialization with the accepted routine shape and asserts purge drains the created-then-deleted routine.
- `AccountPurgeDeadlineSchedulingHttpIntegrationTest.java`: the bulk `INSERT INTO workouts` violated the applied `workouts_one_live_active_per_account` partial unique index. The footprint is now one active row plus eleven finished rows with `finished_at`, matching the schema `CHECK`.
- `AccountPurgeCrashResumeHttpIntegrationTest.java`: `unknownAccountPurgeIsSafeNoOp` renamed to `unknownAccountPurgeFailsClosedWithoutDeletes`. Purge with no intent row fail-closes per the approved design; the test now expects the fail-closed `internal_error` signal (cause-chain scan, no production type coupling) with zero ledger rows and the live account untouched.

Follow-up count correction (assignment 4): `delayedPurgeKeepsBRichStateIntactAndFencesOldCredentials` pinned the B-session count to 1 while the fixture creates two (signup grant plus test bearer). The assertion now pins the pre-purge count of 2 and requires it unchanged after purge, keeping the isolation property without weakening it.

Second-round corrections (assignment 5, self-check 9c02 at 18/21 with all production 500s gone): `unknownAccountPurgeFailsClosedWithoutDeletes` and `rejectedDeletionWritesNoLedgerRowAndLeavesAccountLive` used global ledger/intent counts that see sibling-test rows in the shared class database; both oracles are now scoped to the accounts under test. The removal-suite workout PUT sent `repetitions`, `weightKg`, and `restSeconds` as JSON strings and strict parsing returned 422; they now travel as JSON numbers.

Explicitly not changed: the `scheduledPurgeOnEmptyBacklogIsSafeNoOp` no-arg `purgeDue` entry (Worker is adding it; driver signatures preserved), the systematic purge `internal_error` at the valid-account path (Worker diagnosing), and the `rejectedDeletionWritesNoLedgerRowAndLeavesAccountLive` expected-0-but-was-1 leftover (fallout to re-verify after the production fix). No production, driver seam signature, shared-docs, or normative-behavior assertion was touched. New Worker production files in the tree were not read.

## Dedicated verification window (assignment 6) — green

Evidence root: `/tmp/gt-backend-task9c-qa-dedicated-9c01/`. Exclusive QA slot; Worker ran nothing.

Frozen inputs: 162 paths, manifest SHA-256 `cd88c3beca88b6b3f06bb24189467327758b4b6db3ffc8ccdcf2c7221e6f2a1e`. The before-manifest equaled the required hash before anything ran; the after-manifest is byte-identical to before. Stale Surefire reports (26 XML) were preserved in `stale-target-reports/` and cleared from `target/surefire-reports` before the run; no `mvn clean` was executed.

Exact command, run exactly once:

```text
flock /tmp/gt-backend-task7-build.lock ./mvnw -q '-Dtest=AccountPurge*Test' test
```

Start: `2026-10-02T14:20:28Z`. End: `2026-10-02T14:21:03Z`. Exit code: `0`. Full log in `maven-dedicated.log`; fresh reports copied to `fresh-reports/`.

Result parsed from fresh copied XML: 21 tests across 7 suites, with 0 failures, 0 errors, and 0 skips (Removal 2, Deadline 3, CrashResume 3, Minimality 3, Isolation 3, Races 3, Recovery 4). No input edits, fixes, or reruns occurred in this window. The window is closed.

## Final regression (assignment 7) — green

Exclusive QA slot; Worker ran nothing. Both runs Sequential on the identical frozen inputs: 162 paths, manifest SHA-256 `cd88c3beca88b6b3f06bb24189467327758b4b6db3ffc8ccdcf2c7221e6f2a1e`, before-manifest verified equal before each run and after-manifest byte-identical after each run. No input edits, fixes, or reruns in this window.

Run 1 integrated (`/tmp/gt-backend-task9c-qa-final-integrated-01/`): selector `AccountDeletion*Test,AccountStatusHttpIntegrationTest,AccountExport*Test,AccountPurge*Test,AuthHttpIntegrationTest,*Resource*Test,BrowsingHttpIntegrationTest,RoutineUpsertHttpIntegrationTest,Sync*Test`. Start `2026-10-02T14:22:22Z`, end `2026-10-02T14:28:04Z`, exit `0`. Fresh XML: 278 tests across 36 suites, 0 failures, 0 errors, 0 skips.

Run 2 full (`/tmp/gt-backend-task9c-qa-final-full-01/`): `flock /tmp/gt-backend-task7-build.lock ./mvnw -q test`, executed only after run 1 passed with manifests matching. Start `2026-10-02T14:28:26Z`, end `2026-10-02T14:38:40Z`, exit `0`. Fresh XML: 325 tests across 41 suites, 0 failures, 0 errors, 0 skips.

Per-run exact commands, full logs, UTC stamps, exits, fresh copied XML, and before/after manifests are preserved in each evidence root. The window is closed.

## Limits of assignment 3

No purge design, migration, production code, dedicated/integrated/full 9c verification, commit, deployment, or shared-docs change follows from this authoring. No release-readiness, capacity, backup/restore, deployment, HTTPS/OAuth, or Android claim follows. Any Maven run needs a new explicit exclusive slot with fresh frozen manifests.
