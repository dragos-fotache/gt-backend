# Task 9b — QA baseline and requirement-first plan

## Authority and status (historical planning authority; authoring and windows since completed it)

The user explicitly authorized starting 9b only with the existing Worker and QA pair. Accepted 9a remains unchanged. This document records the QA exclusive frozen baseline and the requirement-first verification plan. The statements below that production and test authoring are not released and that Coordinator approval is required before authoring describe the original planning gate, now historical: joint authoring, narrow corrections, initial, corrected, final-candidate and split verification windows have all been authorized, executed and closed since. Slices 9c and Task 10, commits, deployment, shared edits, agent replacement and context management remain unauthorized.

Worker owns production and its slice report. QA owns requirement-first tests and independent verification. Coordinator owns contracts, findings, design approval, structural review and acceptance. The statements below that Worker remains in read-only inspection and QA has not inspected new implementation describe the original baseline and design phase, now historical: disjoint authoring, Worker production including V8 and configuration, and QA src/test authoring with wiring reads for interfaces and hooks have all occurred since under explicit gates.

Model and settings observed without change: this QA session runs as `meta/muse-spark-1.3-contributor` with high reasoning (`PI_PROVIDER=meta`, `PI_MODEL=muse-spark-1.3-contributor`, `PI_REASONING_LEVEL=high`). The handoff records the historical Medium policy. No tool or model setting was changed.

## Exclusive frozen baseline — green

The replacement capture is `/tmp/gt-backend-task9b-baseline-freeze-FUCwi2pC/`. The superseded first capture is retained as starting history only and was not used for Maven execution. No Maven run started under the superseded freeze.

Evidence directory: `/tmp/gt-backend-task9b-qa-baseline-full-9b01/`.

Frozen inputs: 116 paths, manifest SHA-256 `f7a14a3fbdc00c9ba5751f3575160d745bc200d0c17b56c6970a3e5c5e009a21`. The before manifest, after manifest and corrected starting manifest are identical. The manifest was generated with the corrected capture `manifest.py`. Backend HEAD `af6e1f5` and shared HEAD `0bd414b` were preserved. All 27 pinned shared inputs verified. No source, test, build, shared or fixed-contract edit occurred during the run.

Exact command, run exactly once:

```text
flock /tmp/gt-backend-task7-build.lock ./mvnw -q test
```

Start: `2026-10-02T06:21:59Z`. End: `2026-10-02T06:30:05Z`. Exit code: `0`.

Stale Surefire reports were preserved separately in `stale-target-reports` and removed from `target/surefire-reports` before the run. No `Maven clean` was executed. Fresh reports were copied to `fresh-reports`. The full log is `maven-baseline.log`. The exact command, start and end timestamps and exit code are retained in `exact-command.txt`, `start-utc.txt`, `end-utc.txt` and `exit-code.txt`.

Result from fresh copied XML: 248 tests across 25 suites, with 0 failures, 0 errors and 0 skips. The historical 248/25 count was not used as an oracle; these are actual fresh results. The post-Testcontainers Hikari connection warnings in the log are the known separate lifecycle observation and did not affect the green result.

The baseline slot is closed. The snapshot path in the corrected capture remains accessible. No correction or retry was performed. The baseline was reported to Coordinator at `w4:p7` without `--wait`.

## Requirement-first principles

Expected data will be derived independently from the normative shared contracts, not from production mappers or observed responses. Hand-authored oracles will use OpenAPI wire shapes, account lifecycle rules, authentication rules, retention whitelist, service protocol error mappings, schema ownership rules, compatibility constraints, domain rules and acceptance scenarios. Existing assertions will be retained and not weakened to make fixtures pass.

All future tests will use isolated Testcontainers PostgreSQL 16 and real HTTP for the implemented routes. External Google behavior may use deterministic doubles with synthetic keys and stub certificates for protocol tests. Mocked Google results are not live-provider evidence and will be reported as such. Live Google, deployed HTTPS, release OAuth and Android integration remain unverified external work.

Ordinary deletion must remain disabled without verified purge readiness. Isolated future tests may explicitly enable the acceptance path only after an approved design and explicit slot. A queue alone does not satisfy the physical deadline. The separate 86400 second physical purge deadline must not be relaxed. Snapshot cleanup lag does not extend account deletion.

Every future Maven operation requires an explicit slot, frozen inputs and `flock /tmp/gt-backend-task7-build.lock`. Locking alone is not a source freeze. Before and after manifests will be captured with the corrected `manifest.py` and compared with the frozen starting manifest. Exact commands, logs, exits, fresh copied XML and actual before and after hashes will be preserved. Reports will be sent when green or genuinely blocked, without `--wait` messages to an active Coordinator.

Java navigation will prefer mcpls. Actual initialization or tool errors will be reported, with read-only fallback and without tooling changes. The local `gymtracka` database and `~/.config/gymtracka-backend/dev.env` or real secrets will never be accessed. Panes, working directory, layout and services will be preserved. No context or compaction management will be performed.

## Requirement-first plan by acceptance criterion

### AC9b.1 Proof, confirmation and release gate

Verify valid fresh `deleteAccount` proof with no bearer succeeds only when the readiness gate is explicitly enabled in isolated test configuration. Verify bearer-only access without Google proof is denied. Verify wrong purpose, wrong subject, wrong account, expired proof at exact `now >= expiresAt`, consumed or invalid proof, missing confirmation and false confirmation are rejected without consuming an otherwise valid challenge and without claiming a key or changing identity, account, sessions, domain or ledger. Verify malformed JSON and unknown fields follow the shared structural mappings. Verify that with purge readiness absent, a new delete fails closed without acceptance, without proof consumption and without a misleading receipt, while an already accepted identical receipt can still be recovered. Identify test-only acceptance enablement explicitly and do not present it as deployment readiness evidence.

### AC9b.2 Atomic irreversible acceptance

Create rich owned state with several sessions through accepted surfaces. Assert the original receipt, deadline, one ledger record, durable purge intent, consumed challenge, disabled account and all disabled sessions and detached current binding for account A in one transaction. Use independent time control for `deletedAt` and assert `purgeBy` equals acceptance plus 86400 seconds. Inject failures before commit and at representative intermediate steps. Assert all acceptance effects roll back, with no false 202, partial disablement or premature domain deletion, and that an unchanged valid request can then succeed. Verify already committed writes remain committed and no new account write can commit after the accepted disablement boundary.

### AC9b.3 Exact retry and durable receipt

Verify concurrent identical deletion requests yield one acceptance and one immutable original receipt. Change raw whitespace, property order, credential content and body under the original key and observe `409 idempotency_key_reused` without evidence replacement. Verify the same key in unrelated namespaces or accounts does not retrieve another outcome. Stop the service process that accepted deletion and recover the identical receipt in a new process after challenge expiry, access expiry and session revocation, without creating an account, issuing credentials, changing timestamps or moving the deadline. Use controlled minimal-ledger-only fixtures only as supplements and do not claim they implement purge.

### AC9b.4 Authenticated status and minimization

Verify active status returns the owned live account, deleted status returns the original receipt, and old account A status works while the same Google identity owns new account B. Verify foreign and unknown targets return `404 not_found`. Verify purpose separation, successful one-use status consumption and that a lost status response requires a fresh challenge rather than stored status replay. Verify no proof route creates an account or grant. Inspect every permanent ledger field and assert only the whitelist is retained: old account UUID, versioned keyed digest of provider plus subject, deletion time and original purge deadline, original accepted deletion key, keyed exact-request digest and fields needed to reproduce the receipt. Assert no raw body, raw token, raw subject, profile, credentials, export copy, domain record, sync response or attempt list survives in the ledger. Verify rollover from synthetic key version 1 to version 2 preserves verification of old records, and missing required keys fail closed. Verify minimal evidence has no age TTL, including a controlled time more than 90 days later, without restoring purged domain data.

### AC9b.5 Live fences and concurrency

Use deterministic real PostgreSQL and HTTP gates for delete versus direct writes, sync writes, refresh, old grant replay, competing deletion, fresh sign-in and binding replacement, snapshot capture and continuation, and export before and during output. Verify valid serialized outcomes, no post-acceptance write or grant resurrection, no hybrid success, no fixture-imposed circular waits and preservation of another account. Include source review of implicit foreign-key locks and actual transaction participation, including provider-subject advisory locks, account and session locks, credential locks, replay rows, challenge locks and snapshot key-share locks. Verify export admission and cancellation observe actual deletion: deletion before first output prevents release, and accepted deletion during output stops later release without finalizing partial JSON as a successful file.

### AC9b.6 Basic new-account separation

After account A is disabled and detached, verify only a distinct fresh sign-in creates empty account B with a new UUID. Verify old credentials, exchange retries, cursors, snapshot and list tokens and accepted-deletion replay do not authorize or implicitly create B. Verify old A status identifies A and not B, and no domain, replay or reservation ownership is reassigned. Verify Android cache clearing is not claimed as backend enforcement beyond the specified account-bound receipt.

### AC9b.7 Structure and frozen verification

Support Coordinator structural review of separate proof, acceptance, status, ledger and crypto and persistence responsibilities, checked writes, understandable lock ordering and sanitized errors and logs. Avoid controller SQL, enlarged multi-mode authentication methods, broad mutation refactors and permanent sensitive request storage. Verification will use Worker self-check, independent 9a and 9b and accepted API regressions and full suite, then Coordinator HTTP and PostgreSQL spots, on identical frozen inputs with fresh evidence. Acceptance remains explicitly bounded; the physical deadline and production release gate remain pending 9c.

## Independent oracles and retained assertions

Oracles will be hand-authored from the receipt fixture shape, OpenAPI `DeleteAccountRequest`, `AccountDeletionReceipt` and `AccountStatus` schemas, strict UTF-8 object parsing rules, canonical lowercase UUID rules and shared error code mappings. Exact-byte identity will include whitespace and property-order differences. Timestamps will use service time with exact boundary checks. All accepted 9a export assertions and earlier sync, resource, browsing, pull, pruning and snapshot assertions will be retained as regression supplements and distinguished from direct 9b route evidence, controlled database fixtures and external Google doubles.

## QA plan amendments — concrete cases

These concrete cases are added without inspecting Worker design or production implementation. Oracles remain independently derived from normative contracts. Controlled ledger-only fixtures below are supplementary and are not actual purge evidence.

- Independently accepted same UUID key in two accounts: two owned accounts A and C may each accept the same UUID deletion key and receive independent receipts. Same-account identical contenders recover the serialized winner rather than rejecting its now-consumed challenge or detached binding. Different keys against the same owned account still produce one receipt and leave rejected fresh proof unconsumed. Unrelated namespace reuse is supplementary evidence only.
- Serialized exact receipt recovery after consumed and detached winner: an identical accepted deletion retry recovers the original receipt after challenge consumption, session revocation, binding detachment and access expiry, including after actual originator stop and recovery in a new process. Recovery does not create an account, issue credentials, change timestamps or move the deadline, and has no credential expiry or provider call dependency.
- Provider, subject and account wait expiry and status consumption: proof equality and exact expiry are checked after provider, subject and account lock waits for both delete and status. Only successful owned status consumes its challenge. Unknown and foreign status leave the challenge unchanged. Rejected ownership, unavailable capability, rollback and consumed concurrent status leave valid rejected proof unconsumed.
- Production-unavailable readiness with test-classpath-only replacement: the gate remains unavailable under every ordinary production setting. Isolated acceptance tests use an explicit test-classpath-only readiness replacement after approved design. Accepted replay and status remain available when that replacement is removed.
- Pre-deletion in-flight sign-in denial: a deterministic pre-deletion in-flight sign-in proof is denied both with no B binding and after B is already created. Strict challenge creation after the latest subject deletion applies, with equality denied. Repeated A and B deletions, retained historical digest versions and actual restart are covered. Historical-key omission preventing latest-deletion lookup must fail closed rather than silently miss old evidence. Synthetic-key restart and rollover and missing-key tests distinguish safe startup refusal from route-level unavailable behavior.
- Explicit account, session, credential and cleanup and foreign-key gates plus zero sessions: deletion and status tests cover explicit account then session then credential ordering, deterministic session lock order, surviving-row checks, concurrent cleanup behavior and zero-session acceptance. Snapshot capture before and after account foreign-key acquisition and continuation are covered, with actual foreign-key participation rather than assumed joined-lock order. Findings are scoped to actual valid serialized outcomes.
- Ledger-only owned-deleted fresh different-key invalid state: fresh delete proof with a different key against a ledger-only owned deleted A returns invalid state without creating a second record and without consuming otherwise valid rejected proof. Fresh status for A works alongside B. These are controlled supplementary fixtures, not actual purge evidence.

## Execution journal — initial and final-candidate windows with full provenance

Initial window (closed): compile-01 exit 1 (Boolean inference, no tests, 3036f352); compile-02 exit 0 (78e8dee9); dedicated-01 exit 1 (47 tests across 9 suites, two huge-routine failures, 78e8dee9); dedicated-02 exit 1 (47 tests, one outward-bytes failure, f1ee270c); dedicated-03 exit 0 (47 tests across 9 suites, zero failures, 0810d040). Exact roots `/tmp/gt-backend-task9b-qa-verify-{compile-01,compile-02,dedicated-01,dedicated-02,dedicated-03}/` are preserved with commands, logs, UTC starts and ends, exits and fresh XML. Corrections were Boolean.TRUE.equals inference, huge-routine draft create plus valid PUT with required isDraft:true, and real JSON completeness with retained raw bytes.

Final-candidate window (closed): compile with the allowed no-row polling fix exit 0; dedicated-01 exit 1 (54 tests, replay-PID wait null); dedicated-02 exit 1 (54 tests, same replay wait plus status provider-wait 200 from a stale frozen test Clock); dedicated-03 exit 1 (54 tests, replay wait null with holder diagnostics); dedicated-04 exit 0 (54 tests across 9 suites, zero failures, 5a76f62a). The status provider-wait -02 failure was a stale test Clock (frozen startup time made a directly expired challenge look live); it is recorded as a fixture error, fixed by advancing the test Clock, not a production finding. Exact roots `/tmp/gt-backend-task9b-qa-final-dedicated-{01,02,03,04}/` plus `/tmp/gt-backend-task9b-qa-final-01/` are preserved with full 148-file pre-run mirrors captured before each changed version.

Snapshot provenance: QA run roots carry complete hash captures; contemporaneous full pre-run mirrors exist for the corrected and final-candidate runs above. The initial window relied on hash captures with Coordinator post-run reconstructions under `/tmp/gt-backend-task9b-coordinator-qa-evidence-review-UtrjQPOV/` noted as non-contemporaneous (dedicated-02 reconstruction incomplete and debug-only). `corrected-compile-01/fresh-reports` older 47-test XML is preserved and labeled stale and residual, never summed as fresh results.

## Stable scope — two separate two-request overlaps plus retained replay and supplements

No execution, Maven, child startup, production, schema, configuration, build or shared change occurred in this authoring. Git holds fourteen new `src/test` files plus the one existing auth edit. Exact new method pointers in `AccountDeletionFencesHttpIntegrationTest`:

- `deleteHoldsGateWhileValidSyncMutationWaitsThenDeniesWithoutSideEffects`: held deletion gate plus one real valid `routine.upsert` draft mutation; proves that contender waits on the captured deleting PID through `awaitBlockedOnDelete`, releases, requires 401 with no routine row, revision, batch, cursor, reservation or processed-mutation outcome; existing sequential committed-before sync case stays assessed preservation supplement.
- `deleteHoldsGateWhileEligibleFreshRefreshWaitsThenDeniesWithoutRenewal`: held deletion gate plus one currently eligible unconsumed fresh refresh rotation with JDBC eligibility proof that never consumes the tested token; exact fresh `refresh:key` advisory and PID tie through `awaitReplayBlockedOnDelete`, releases, requires 401 with `reauthentication_required`, no idempotency row, no credential insert and no expiry renewal.
- Retained `deleteHoldsGateWhileAcceptedReplayWaitsThenDeniesWithoutRenewal` (renamed to actual scope): held deletion gate plus the original accepted refresh key and exact body only, with exact advisory identity and `pg_blocking_pids` tie, release, exact 401 and no renewal; sequential sync and fresh-refresh denials after release stay denial supplements, not overlaps.
- Committed-before writer cases and existing-token reuse stay honestly labeled sequential supplements, never reverse overlaps.

## Split-verification window — provisional green with full pre-run mirrors

Split window starting hash `192d9660983a57d12b4837b321b793e1f05a7c55b8f62a5ca64e4907325cd01d` is historical after the allowed oracle additions. Before first execution QA added the promised state comparisons only: tested refresh credential `expires_at` before and after equality, sync `account_sync_state` bookkeeping with root and child reservation and cursor-token counts, plus target-root absence. No product, seam or requirement change occurred.

| Run | UTC | Outcome | 148-path manifest |
|---|---|---|---|
| split-01 compile | see evidence | exit 0 | 560c0a27 |
| split-01 dedicated | see evidence | exit 1; 56 tests, one error (`sync_change_batches.owner_id` test-owned column mistake) | 560c0a27 |
| split-02 compile | see evidence | exit 0 | 3342c617 |
| split-02 dedicated | see evidence | exit 0; 56 tests across 9 suites, zero failures, errors or skips | 3342c617 |

Exact roots are `/tmp/gt-backend-task9b-qa-split-01/` (compile green, dedicated one error, preserved closed) and `/tmp/gt-backend-task9b-qa-split-02/` (compile green, dedicated green with 56 tests, preserved). Each version captured and verified a full 148-file pre-run mirror plus before and after manifests. The column fix changed only the test oracle from a nonexistent `owner_id` to the schema `account_id`; no assertion was weakened and contenders were never moved after deletion.

## Explicit limits (historical planning text; authoring is closed)

The following paragraph is the original planning gate, now historical and superseded by the executed authoring and verification windows above: this plan did not authorize test authoring and further work, and the next step was Coordinator approval before any authoring was released. Authoring is now closed: no new tests, fixtures, Maven runs, production, migration, configuration, build or dependency changes, 9c work, Task 10, staging or commit, deployment, shared reset or edit, unrelated shutdown, agent replacement or context management follows. Later integrated and full regression with final acceptance on the same input version require new explicit slots.

Current provisional result: split-02 dedicated exit 0 with 56 tests across 9 suites and zero failures, errors or skips, UTC 2026-10-02T12:06:15Z to 2026-10-02T12:07:17Z, actual 148-path hash 3342c617309fb62ca5380dac324e76ec274f131d846bb216133186e45317629c with full pre-run mirrors; split-01 dedicated exit 1 on the test-owned column mistake is preserved closed with its own full mirror. Accurate uncovered and assessed supplements are retained: committed-before writer cases and existing-token reuse stay sequential supplements, never reverse overlaps. A separate status subject-advisory-expiry JUnit does not exist; exact equality, provider-wait and account-wait expiry cases are direct, final after-all-locks freshness is source-reviewed, and the Coordinator will exercise subject wait in later HTTP and PostgreSQL spots rather than claiming a nonexistent JUnit. The truly uncertain commit acknowledgment instant remains source-reviewed and is not claimed fault-reproduced.
