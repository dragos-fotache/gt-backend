# Task 9b — bounded acceptance

## Decision

**9b is accepted: deletion acceptance/status/minimal evidence and lifecycle fences only.** 9a remains accepted. This is not completion of Task 9, physical purge, deadline compliance, complete-v1 or deployment acceptance.

Ordinary `AlwaysUnavailablePurgeReadiness` remains **unconditionally false**. New deletion is unavailable (`503 temporarily_unavailable`) without consuming proof or disabling an account. Only explicitly labeled **test-classpath replacements** enabled acceptance in isolated verification. A durable pending intent is not physical purge. The unchanged deadline is **`purgeBy = deletedAt + 86,400 seconds`**. No 9c, Task 10, commit, deployment, shared-checkout edit, agent/settings replacement or context-management authority follows.

## Accepted input version

- Backend HEAD preserved: `af6e1f59a537112a2efe3585f8ddcb90c0e4885b`; working changes are uncommitted.
- Independent shared HEAD preserved: `0bd414b6c1b819a26e2d5fca0bed6b6b5acf2cea`, clean; all 27 pinned shared inputs verified.
- Final **148 executable/build/fixed/shared paths**, manifest SHA-256 **`3342c617309fb62ca5380dac324e76ec274f131d846bb216133186e45317629c`**.
- Complete final source mirror: `/tmp/gt-backend-task9b-final-freeze-vgjz8ZmR/input-snapshot/`. Every final run's actual before/after/live manifest and complete pre-run mirror independently matches it. All 107 previously compiled main/build/fixed/shared inputs remain unchanged.
- V1–V7 are immutable; only approved forward V8 is authored. No dependency/build change or broad auth/domain refactor.

Acceptance/status metadata in this file, README and coordinator records is outside the frozen input inventory. Fixed contracts/source/tests are not changed to record acceptance.

## Independently verified gates

All Maven commands used their explicit exclusive slot and `flock /tmp/gt-backend-task7-build.lock`. Actual commands, logs, UTC/exits, fresh copied XML and full matching pre-run source mirrors are retained. All totals below are parsed actual XML, not predicted totals.

| Gate | UTC on 2026-10-02 | Result | Evidence root under `/tmp/` |
|---|---|---|---|
| Starting baseline, earlier inputs | 06:21:59–06:30:05 | exit0; 248 tests / 25 suites | `gt-backend-task9b-qa-baseline-full-9b01` |
| Final dedicated split-02 | 12:06:15–12:07:17 | exit0; **56 / 9** | `gt-backend-task9b-qa-split-02` |
| Worker self-check: 9b/9a/auth | 12:14:19–12:16:25 | exit0; **106 / 17** | `gt-backend-task9b-worker-selfcheck-9b01` |
| Independent integrated regression | 12:21:00–12:26:12 | exit0; **257 / 29** | `gt-backend-task9b-qa-final-integrated-01` |
| Independent full suite | 12:26:30–12:36:19 | exit0; **304 / 34** | `gt-backend-task9b-qa-final-full-01` |

All green test gates have **zero failures/errors/skips**. The final four use the identical final148 version. Baseline and main-only compile are historical earlier-version evidence, not substituted final behavioral evidence.

Coordinator manual structural/HTTP/PostgreSQL spots used existing compiled classes, no Maven or repository test/production authoring:

- `/tmp/gt-backend-task9b-coordinator-spots02-MZy1mLnM/`: exit0, 12:47:56.094463–12:48:11.338462 UTC.
- `/tmp/gt-backend-task9b-coordinator-replay-expiry-1n4ZhwhP/`: exit0, 12:51:04.987635–12:51:20.657373 UTC.
- Both preserve scripts, exact commands/allowlisted synthetic JVM environment, HTTP/SQL observations, logs, UTC/exits and complete matching pre-run source mirrors. All148 before/after/live inputs still match final. Only uniquely owned isolated PostgreSQL16 containers, bounded loopback JVMs and controlled locks were created and cleaned; no local `gymtracka` DB, dev.env or real secrets/provider credentials were accessed.

## Coverage and structural disposition

| Criterion | Accepted evidence |
|---|---|
| AC9b.1 Proof/gate | Real HTTP proof/confirmation/structural cases and gate-off test; manual main-only JVM without test classes rejects new deletion even with same-named test properties. Failed gate leaves proof/account unchanged. |
| AC9b.2 Atomicity | Rich-state/all-session acceptance, pending intent, immutable receipt/deadline, binding detachment, zero-session/cleanup and complete rollback/retry cases; manual SQL confirms committed acceptance with preaccepted routine intact. |
| AC9b.3 Exact durable replay | Concurrent identical winner, scoped same UUID in two accounts, changed exact bytes/conflict, different-key invalid state, actual process stop/restart and ring rollover. Coordinator additionally kills the actual accepting JVM, controls old challenge/all-credential expiry, then recovers the byte-identical receipt in a new main-only JVM with ordinary readiness unavailable. |
| AC9b.4 Status/minimization | Active/deleted/old-A-alongside-B, unknown/foreign and one-use status; eight permanent whitelist columns, no live-account FK/cascade, independent HMAC oracles and historical keys/no TTL. Direct provider/account/equality expiry tests plus **manual active and deleted subject-lock expiry** complete the after-wait assessment. |
| AC9b.5 Live fences | Real deletion/direct/sync/fresh-refresh/original-grant replay, competing deletion, in-flight sign-in/binding and durable-copy/export gates. Separate two-request sync and eligible fresh-refresh fixtures retain actual blocker proof and no state/credential/expiry changes. Original replay uses its exact granted 8-byte refresh-key advisory and actual deleting PID via `pg_blocking_pids`, then exact401. No pending-client-future-only claim. |
| AC9b.6 A/B isolation | Fresh post-deletion signup creates distinct empty B; old A proof/evidence remain A, old ordinary tokens/reads/grants deny, pre-deletion sign-in cannot reattach even when B exists. Manual empty-B/original-A status and untouched A routine corroborate separation. |
| AC9b.7 Structure/freeze | Coordinator source review and all final same-input gates pass. Controllers parse only; explicit acceptance/status transaction stages, checked effects, persisted receipt, independent ledger/crypto/history fence and narrow durable-copy fences. Sanitized faults and actual transaction/implicit-FK lock order are reviewed. |

Status subject-wait evidence is **manual**, not an invented JUnit case: exact subject advisory waiter and owning PID were observed while the challenge transaction was active; after real expiry and unlock, both active/deleted requests returned401 `invalid_google_credential`, no-store and unconsumed proof. Dedicated/full case inventory is retained with the manual evidence.

Typed helper cases directly classify acquisition/timeouts, definitive nested invalid-isolation/rollback/illegal-state versus transient SQL08/40 and unrelated faults; endpoint precommit500/503 rollback is direct. A truly uncertain commit acknowledgment is **source-reviewed, not fault-reproduced**. No proof restoration or different-key automatic retry is added.

## Evidence honesty and retained failures

The authoritative chronological execution/finding record is [task-9b-review.md](task-9b-review.md); owner reports are [Worker](task-9b-worker.md) and [QA](task-9b-qa.md). Initial provisional47 and corrected53 results are not final acceptance. Failed compile/dedicated versions and complete available provenance remain retained:

- Initial corrected mirrors reconstructed after execution are labeled honestly; incomplete dedicated-02 reconstruction is debug-only.
- Corrected-compile-01's old47 XML is stale/residual, not fresh evidence.
- Final-candidate dedicated-01/-02/-03 all failed before green-04; the stale test-Clock status failure is explicit. Moving concurrent sync/refresh to sequential denials did not close their overlap requirements; final separate split fixtures restored them.
- Split-01's nonexistent batch `owner_id` column error is preserved; split-02 changes only to immutable-schema `account_id`, with no weakening.
- Coordinator spot01 correctly returned422 for the Coordinator's invalid routine-create shape. It is retained; the temporary script alone was corrected to normative `{id}` and successful spots used unchanged inputs.

Committed-before direct/sync writers are **sequential preservation supplements**, not overlapping reverse-order races. Existing-token/no-insert reuse and controlled ledger-only aged fixtures are supplements, not purge evidence. Export checks collect actual emitted prefixes/completeness, not imagined output. Synthetic Google/key rings are not live-provider evidence.

## Durable handoff and remaining boundary

All 31 selected evidence roots (5079 byte-verified files), including failures, child logs and final mirrors, have durable copies at:

`/home/dragos/.local/state/gymtracka-backend/coordinator-checkpoints/task-9b-20261002T125557Z/`

`evidence-index.json` maps original roots to durable copies. Coordinator records/status/diff are retained there. Prefer durable copies if `/tmp` expires; retained source mirrors include new untracked production/tests, not merely tracked diffs.

Future 9c needs separate authorization and must establish actual recoverable deadline-aware purge/release readiness without dropping this ledger or historical external keys. Inventory for that future work includes account/binding/session/credential/auth replay and ownerless challenge metadata; owned exercises and relationship rows; workouts/routines and exercise/set children; root/child reservations; resource replay; account sync state, batches/changes/processed mutations/cursor tokens; snapshots/records; and retained minimal ledger/separate intents. Global catalogue/lookup data must not be confused with owned data. Inspect pinned schema/FK ownership before any purger design; this inventory is not purge implementation.

The eight-field ledger stays permanent identifying metadata; keys remain external/versioned with all historical subject versions required before candidate lookup. Mutable intent is separate and only `pending` exists in V8. Physical erasure, backup/replica/restore verification, purge retries/deadline compliance, release readiness, real Google/HTTPS/mobile and deployment remain unverified. **All execution slots are closed; hold both actors. No commit or 9c start.**
