# Task 8c — accepted

The Coordinator accepts **bounded Task 8c**: durable frozen sync snapshots, account-bound continuation tokens, one consistent committed baseline, exact 24-hour access expiry, bounded expired-storage cleanup, and the mandatory structural gate. Tasks 8a and 8b remain accepted, and the combined pull, pruning and snapshot behavior passed regression verification. This completes the agreed three-slice Task 8 sequence, not complete-v1 or deployment acceptance. Tasks 9 and 10 remain unauthorized.

Fixed criteria are in `docs/task-8c-contract.md`; findings and structural review are in `docs/task-8c-review.md`. One fresh openai-codex gpt-6-luna High Worker and QA completed the slice in the existing panes. The Coordinator owned contracts, coordination, review and acceptance, not production code or test authoring. All roles preferred mcpls; the Coordinator reported Java initialization failures and used read-only file inspection without changing tooling. No agent context or compaction management was performed.

## Accepted behavior

- `GET /v1/sync/snapshot` returns complete canonical upsert records for all live owned custom exercises, saved and draft routines, and active and finished workouts. It excludes catalogue roots, tombstones and foreign roots. Unchanged roots remain discoverable after their old incremental history has been pruned.
- Capture uses one PostgreSQL repeatable-read transaction for live authorization, committed state, baseline registration, root and child projections, frozen labels, and durable snapshot storage. Records are ordered by type, then canonical UUID ascending. Root IDs are read in bounded chunks; whole aggregates are never split or truncated.
- Every page has one nonempty baseline cursor. Later committed changes remain discoverable through accepted pull semantics. Account foreign-key checks can take implicit key-share locks and delay same-account writers; they do not permit mixed capture views. A large capture can therefore affect write latency.
- Distinct, versioned HMAC tokens bind account, snapshot, offset, effective limit and expiry. They are not bound to a login session. Valid continuation replay preserves contents, baseline and next token across an actual service-process restart. Limits are 1–500, default 200; changing the effective continuation limit is invalid.
- Access expires at exactly 86,400 seconds after creation, without sliding renewal. Authentic, correctly bound expired tokens return `410 snapshot_expired`, including after frozen storage is physically removed. Invalid, foreign or mismatched tokens return `400 invalid_snapshot_token`. Every request rechecks live account and session authorization.
- Failures inside capture roll back new snapshot storage and any new baseline registration. An expiry guard runs before capture commits; another check prevents expired response delivery. A response failure after a successful commit cannot retroactively erase that complete capture. Transient faults, including exhausted serialization retries, return a safe HTTP 503 response.
- Production cleanup selects at most 100 expired snapshot headers per invocation using `SKIP LOCKED` and transactionally cascades their stored records. The actual Spring runner defaults to enabled, with 60-second initial and fixed delays. Its failure message is fixed and sanitized, and later invocations recover.

V7 introduces account-owned snapshot headers and ordered records, checked payload identities and positive signed 64-bit revisions, a matching-account baseline reference, and an expiry index. Applied V1–V6 remain unchanged. Production changes are limited to six new classes, V7, and the approved scheduler-only `application.yaml` block. Existing production code, accepted tests, build files and dependencies are unchanged.

## Explicit retention clarification

The user approved and reconfirmed **exact 24-hour access expiry with bounded physical cleanup afterward**. Only shared `retention.md` and `service-protocol.md` were narrowly clarified after the initial baseline closed. All other supplied shared changes and HEAD were preserved.

Scheduling, backlog, downtime and large cascades can delay physical deletion. The tests establish bounded eventual cleanup, not a production cleanup-latency or backlog SLA. This clarification does not extend access validity, authorize intentional retention of expired payloads, or weaken the separate account-deletion purge deadline. Full account deletion and purge remain Task 9.

## Fixed criteria and evidence

| Criterion | Evidence |
|---|---|
| AC8c.1 | Real HTTP publication and independent input-derived expectations cover all root kinds, saved and draft states, active and finished workouts, child identities, relationships, metadata, nulls, zero values, rest and frozen labels. Production pruning does not remove unchanged live roots from bootstrap. |
| AC8c.2 | Empty accounts, limits 1/default/500, 501 roots, exact and partial terminal pages, stable replay, type-scoped equal UUIDs, canonical ordering across the signed UUID boundary, and an intact 256-set aggregate. |
| AC8c.3 | Deterministic real database gates cover a committed writer, allocated-but-uncommitted active replacement with two ordered changes, production pruning, and page reads racing expired cleanup. Captured views remain coherent; later batches pull from the baseline. |
| AC8c.4 | Snapshot-route evidence covers live authorization, disabled and revoked state, a second same-account session, foreign roots and tokens, malformed and tampered tokens, effective-limit context, query syntax and nonempty bodies. |
| AC8c.5 | Controlled time verifies the exact boundary and non-sliding expiry, bounded backlog progress, rollback and retry, safe failure logging, actual scheduled invocation, and expired-token classification after cleanup and process recovery. |
| AC8c.6 | Capture and middle/final-page faults preserve rollback and retry behavior. Child process A creates a snapshot and is stopped; process B resumes and replays the original continuation against the same isolated database and stable test key. Baseline, expected contents and original expiry are preserved. |
| AC8c.7 | Coordinator source review passes the mandatory structural gate: cohesive HTTP, capture, token, persistence and cleanup responsibilities; explicit transaction participation; bounded SQL selection; no controller SQL, legacy command growth, mode flags or broad refactoring. |
| AC8c.8 | Final Worker self-check, independent integrated and full QA, and eleven Coordinator HTTP/PostgreSQL spots passed on identical frozen inputs. Fresh reports, commands, exit codes and manifests are preserved. |

Coverage is representative, not every timing, corruption or fault permutation. Direct snapshot evidence is distinct from existing mutation, pull, pruning, read, browse and authentication regression supplements. Test-owned clocks, gates and SQL faults delegate to real service and database work rather than simulate the service. Scheduler coverage and child-process recovery do not establish broader logging, key-rotation, backup or deployment compliance.

## Final frozen verification

Every Maven command used `flock /tmp/gt-backend-task7-build.lock`, with separately enforced input freezes. Reports were copied before later commands could overwrite them.

| Run | Result | Evidence directory |
|---|---|---|
| Initial baseline | 188 tests across 15 suites; exit 0 | `/tmp/gt-backend-task8c-qa-baseline-evidence-tZaM3t/` |
| Final dedicated QA | 20 tests across 3 suites; exit 0 | `/tmp/gt-backend-task8c-qa-followup-snapshot-W6aRB6/` |
| Final integrated QA | **187 tests across 15 suites; exit 0** | `/tmp/gt-backend-task8c-qa-followup-integrated-tRe6jR/` |
| Final full QA | **208 tests across 18 suites; exit 0** | `/tmp/gt-backend-task8c-qa-followup-full-nKAbZq/` |
| Final Worker self-check | 147 tests across 11 suites; exit 0 | `/tmp/gt-backend-task8c-worker-final-selfcheck-PVusxP/` |
| Coordinator spots | **11 tests across 3 suites; exit 0** | `/tmp/gt-backend-task8c-coordinator-spot-qgsy9pph/` |

All passing runs above have no failures, errors or skips. The final dedicated, integrated, full, Worker and Coordinator runs have identical **107-path** before and after manifests, SHA-256 `440efc494f7fffda03e74c6ef304fc27052a78d81a910e50bb0001974817a148`. The Coordinator independently parsed fresh copied XML, checked report timestamps and rehashed current inputs. These manifests describe verification before final status-only README and documentation updates; production, tests, build and normative inputs remain unchanged afterward. Current mutable reports contain the later Coordinator subset; copied evidence preserves the full counts.

Exact commands, logs, exit codes and per-suite reports are in each evidence directory. Starting baseline: `/tmp/gt-backend-task8c-baseline-yxs7moqp/`, 130 paths, manifest SHA-256 `a04290f49a9a8cd5a782397d95ba5c3ba632fac1f89f1749db70b0aa21af8e55`. Do not confuse that starting inventory with the final behavioral manifest.

## Durable handoff checkpoint

The checkpoint location is `/home/dragos/.local/state/gymtracka-backend/coordinator-checkpoints/task-8c-20261001T074849Z/`. It retains original temporary evidence directory names and a curated `project-state/` copy. Follow `docs/coordinator-handoff.md` and verify `checkpoint-files.sha256` from that directory. This local handoff does not establish deployment or disaster-recovery acceptance. Git checkouts, the local database, secrets, mutable target files and stale packaged JARs are excluded.

## Findings and safety

F8c.1 added the in-transaction capture-expiry guard. F8c.2's token-length check was already present in the completed handoff. F8c.3 strengthened the V7 JSON constraints. F8c.4 corrected the exhausted-retry HTTP status. F8c.5 corrected a test-imposed circular wait without removing foreign keys or authorization fences. F8c.6 strengthened actual process-restart evidence. All are resolved in the accepted verification state. Earlier compilation, fixture, context-load and assertion failures remain preserved as historical evidence, not acceptance results; the role reports list their directories.

No local `gymtracka` database or secret file was accessed. No applied migration, unrelated shared content, dependency, existing test assertion, pane layout or working directory was changed. No staging, commit, deployment or unrelated service shutdown occurred. Only test-created service processes and isolated databases were used. Existing post-Testcontainers authentication-cleanup/Hikari noise remains separate recorded debt. Worker and QA are idle awaiting assignment; later work requires explicit user authorization.
