# Task 8c — final Coordinator review

## Decision and scope

The mandatory structural gate passes, and the Coordinator accepts bounded Task 8c under `docs/task-8c-contract.md`. Final acceptance, commands, evidence and limits are recorded in `docs/task-8c-acceptance.md`. The Worker authored production code; QA authored tests; the Coordinator reviewed contracts, structure, findings and evidence without authoring production code or tests.

Baseline-relative scope is six new production classes, one forward-only V7 migration and the scheduler-only `application.yaml` block. QA added three snapshot suites and a test-clock configuration helper. Existing production code, tests, build files, dependencies and V1–V6 are unchanged. Only the user-approved shared retention and service protocol clarifications differ from the supplied normative baseline; shared HEAD and other supplied changes are preserved.

The Coordinator's mcpls symbol requests reported that Java initialization was still in progress. Read-only source inspection was used without changing tooling. Worker and QA reported successful mcpls navigation in their own sessions.

## Resolved findings

### F8c.1 — Check capture expiry before committing

The initial expiry check ran only after the transaction returned. The Worker added a guard inside capture after materialization and first-page construction, preserving the final response-time check. A capture that expires before commit now rolls back its new storage and cursor registration. A later response failure cannot retroactively erase an already committed complete capture. The deterministic expiry test passed in independent QA and Coordinator verification.

### F8c.2 — Reject invalid token lengths before decoding

A preliminary moving-source read did not show the guard, but the completed Worker handoff already included fixed 132-character validation before Base64 decoding. No post-handoff correction was needed. Canonical encoding, decoded length, MAC, version and context checks remain explicit. Independent malformed and oversized-token evidence passed.

### F8c.3 — Reject missing JSON fields in database constraints

PostgreSQL accepts CHECK predicates that evaluate to SQL NULL. V7 now explicitly checks required keys and types, rejects a NULL predicate, bounds revisions to positive signed 64-bit values, and checks envelope and record agreement. Isolated PostgreSQL tests for missing keys and out-of-range revisions passed. Applied migrations were not edited.

### F8c.4 — Map exhausted serialization retries to HTTP 503

The first compiled target showed that the service's exhausted-retry marker was mapped to HTTP 500. The controller now maps it explicitly to `503 temporarily_unavailable`, without changing retry count or rollback semantics. Independent QA and Coordinator injected-fault tests passed, including unchanged retry after removing the test-owned fault.

### F8c.5 — Correct the test-imposed circular wait

The original test paused capture after header insertion, then waited synchronously for a writer before releasing capture. Header foreign-key checking, and cursor registration when inserting metadata, can take account key-share locks. The writer's account `FOR UPDATE` boundary conflicts with those locks, so that ordering made the test wait for an operation it was itself preventing.

The corrected gates establish a real repeatable-read view before those foreign-key locks. They allow controlled overlap with a committed writer, an allocated-but-uncommitted multi-root publication and production pruning while retaining complete-view and baseline-to-pull assertions. No foreign key or authorization fence was removed. Independent tests and Coordinator spots passed. Long capture transactions can still delay same-account writes; no production latency SLA is claimed.

### F8c.6 — Restart the process that created the snapshot

The earlier test proved recovery in a newly started reader process, but not restart of the creating process. The strengthened test has child process A create the snapshot, stops A, then starts process B against the same isolated database and stable test key. It independently verifies continuation contents, root identities and names, envelope revisions, baseline, replay and unchanged expiry. The existing expired-token-after-cleanup process evidence remains. Dedicated, integrated, full, Worker and Coordinator verification all passed on the corrected source.

## Structural assessment

Production responsibilities are cohesive and practical:

- The controller owns query and body validation and safe HTTP status mapping; it contains no SQL.
- The token service owns a distinct versioned, domain-separated HMAC format and validates authenticity and request context before typed expiry.
- Capture and page orchestration use explicit transaction templates. Capture combines live authorization, committed state, canonical projections, frozen persistence and baseline registration in one repeatable-read transaction. Narrow registration and serialization conflicts retry the whole transaction.
- Persistence reads bounded root-ID chunks in canonical UUID order and validates complete page ordinals. Capture inserts whole mapped records individually rather than accumulating the account's payloads in memory.
- Cleanup and scheduling are separate from read orchestration. Expired-header selection is bounded, deletion cascades transactionally, and runner failure text is fixed and sanitized.

Existing mapping, accepted mutation algorithms, replay storage, list and pull formats, and authorization primitives were not refactored. There is no controller SQL, legacy command growth, speculative handler framework, nullable execution mode or new product cap. Immutable continuation reads use repeatable-read views without row locks, preventing mixed header and payload views during cleanup.

The user explicitly approved exact 24-hour access expiry with bounded physical cleanup afterward. The cleanup budget bounds header count, not payload bytes, runtime or deletion delay. Backlog, downtime and large cascades remain operational limits, not evidence of a physical-erasure deadline. Account deletion and its separate purge deadline remain outside this slice.

## Final independent evidence

All final verification used the shared Maven lock and explicit source freezes. Fresh reports were copied before target was overwritten.

- Independent dedicated QA: 20 tests across 3 suites.
- Independent integrated QA: 187 tests across 15 suites.
- Independent full QA: 208 tests across 18 suites.
- Final Worker self-check: 147 tests across 11 suites.
- Coordinator risk-based HTTP/PostgreSQL spots: 11 tests across 3 suites.

All exited with code 0 and had no failures, errors or skips. The final 107-path before and after manifests match at SHA-256 `440efc494f7fffda03e74c6ef304fc27052a78d81a910e50bb0001974817a148`. The Coordinator independently checked fresh XML, report timestamps, current hashes, baseline-relative source scope and the strengthened restart source. The acceptance document lists the final directories and distinguishes them from earlier failed or superseded evidence.

No blocking finding or unmet bounded criterion remains. Coverage is representative rather than exhaustive. Cleanup capacity, key rotation, broader privacy-safe logging, backup/restore, deployment and account lifecycle are not accepted by these tests. Final status-only README and documentation changes follow the verification manifests; production, tests, build and normative inputs are unchanged afterward.
