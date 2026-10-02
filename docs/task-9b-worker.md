# Task 9b — Worker production handoff (bounded authoring, no verification)

## Authority and scope

Joint authoring gate passed under final R4 in `docs/task-9b-review.md`. Revision 2 plus R3, corrected by R4, is the binding design. Bounded disjoint authoring is released. This Worker owns production and this report only. QA owns `src/test` and its report. No Maven, package, compile or test process was run. No commit, deployment, shared edit, secret or local-database access occurred. No 9c purger was authored.

Production readiness for ordinary deletion is unconditionally unavailable in 9b. Only test-classpath replacement, owned by QA, may enable isolated acceptance tests.

## Complete production path scope

New production components, all under `src/main`:

- `src/main/resources/db/migration/V8__account_deletion_ledger.sql`: forward-only minimal `account_deletion_ledger` plus separate `account_purge_intents`; no live-account cascade; no global deletion-key index.
- `src/main/java/com/dragosfotache/gtbackend/account/DeletionLedgerKeys.java`: independent external versioned ring binding for `account.deletion.ledger-keys`; absent ring fails lifecycle paths closed without breaking startup or unrelated routes; malformed present ring fails startup with a sanitized error.
- `src/main/java/com/dragosfotache/gtbackend/account/DeletionLedgerCrypto.java`: pinned HMAC-SHA256 framing with uint32 length prefixes on every string and binary component including labels, raw int32 positive version, fixed R3 order and canonical lowercase UUID text, constant-time comparison.
- `src/main/java/com/dragosfotache/gtbackend/account/PurgeReadiness.java` and `AlwaysUnavailablePurgeReadiness.java`: production gate unconditionally false; receipt recovery and status do not consult it for already accepted rows.
- `src/main/java/com/dragosfotache/gtbackend/account/AccountDeletionLedgerRepository.java`: caller-bound ledger and intent adapter with account-scoped locked lookup, versioned inserts, distinct subject versions and latest-deletion lookup.
- `src/main/java/com/dragosfotache/gtbackend/account/DeletionSubjectFence.java`: global historical completeness before candidate lookup; strictly post-latest-deletion challenge enforcement for non-replay exchanges.
- `src/main/java/com/dragosfotache/gtbackend/account/AccountDeletionService.java`: one caller-bound READ COMMITTED acceptance transaction with scoped advisory, immediate ledger replay before all denial, readiness gate for new requests only, challenge, subject and account stages, single microsecond-normalized instant and checked effects.
- `src/main/java/com/dragosfotache/gtbackend/account/AccountStatusService.java`: one caller-bound READ COMMITTED status transaction; successful owned proof only; ledger resolution after ordinary rows are gone.
- `src/main/java/com/dragosfotache/gtbackend/account/AccountDeletionController.java` and `AccountStatusController.java`: strict export-compatible parsing with no SQL; missing or malformed deletion key is 400; missing, false or nonboolean confirm is 422.
- `src/main/java/com/dragosfotache/gtbackend/account/AccountDeletionHooks.java` and `NoOpAccountDeletionHooks.java`: small deterministic no-op seams; no test endpoint and no product write.

Bounded existing edits, preserving accepted semantics:

- `src/main/java/com/dragosfotache/gtbackend/auth/AuthService.java`: explicit account-first `lockSession` stages with safe missing-session passthrough; single required-collaborator constructor with the deletion subject fence; fence call on every non-replay exchange after replay lookup and under the held subject advisory, including when B is already bound.
- `src/main/java/com/dragosfotache/gtbackend/auth/SessionAuthorization.java`: explicit account, session and credential stages in `requireLiveAccess` with checked re-reads; read-only path unchanged.
- `src/main/java/com/dragosfotache/gtbackend/sync/SyncCursorRegistry.java`: bound-Jdbc `FOR SHARE` live fence only after missing-token lookup and before a new insert; existing-token reuse unchanged.
- `src/main/java/com/dragosfotache/gtbackend/persistence/SyncSnapshotRepository.java`: bound-Jdbc `FOR SHARE` live fence immediately before snapshot header insert; continuation and read paths unchanged.
- `src/main/resources/application.yaml`: independent `account.deletion.ledger-keys` binding documentation with an empty default and no real secret.

Unchanged as required: V1 through V7, accepted export behavior, grant and provisioning algorithms apart from the fenced call, domain execution, dependencies, build and shared inputs. No `src/test` file was touched.

## Seams and API details

- `POST /v1/account/delete` requires `Idempotency-Key` and `{accountId, challengeId, idToken, confirm: true}`; new acceptance returns `202` receipt; identical replay returns the original receipt even when gated, expired or revoked; changed bytes under the key return `409 idempotency_key_reused`; a different key for owned deleted A returns `409 invalid_state` without consumption.
- `POST /v1/account/status` consumes only successful owned proof and returns active or the original deletion receipt; foreign, unknown and rejected status leave the challenge unconsumed.
- Acceptance and status each run in one caller-bound READ COMMITTED transaction with verified original nonce and purpose plus final exact freshness after waits. In-doubt commit failure is sanitized and retryable; proof is never restored and acceptance is never auto-reexecuted under a different key.
- The signup fence requires all database subject versions globally configured before candidate lookup; an empty ledger permits legacy sign-in; missing historical verification fails a fenced exchange closed, while direct receipt and old-A checks use their stored row versions.
- Snapshot and pull fences share conflicts with disablement, retain existing repeatable-read `40001` bounded retries, and yield established safe `401` for fresh disabled or missing accounts. Existing-token reuse and snapshot continuation keep coherent pre-acceptance read semantics and create no new durable copy.

## Known gaps and requests

- No compile, package or test process has been run; a new explicit freeze and verification slot is required before any behavioral claim.
- Test-classpath readiness replacement, synthetic ring wiring, deterministic race fixtures, originator-stop evidence and amended QA cases belong to QA under the approved plan.
- If verification exposes an unavoidable broader edit or a proven snapshot and pull hole beyond the two approved fences, that change returns to the Coordinator before it is made.

## Narrow F9b.1-F9b.7 correction dispositions (no verification yet)

- F9b.1: initial scoped exact replay and conflict precede gate and provider work without locks; the authoritative key-serialized replay precedes all denial inside the transaction; preliminary and provider failures recheck serialized receipt so a concurrent identical winner is recovered rather than rejected. No attempt history is stored.
- F9b.2: status follows challenge, subject advisory and account stages with bound checked ownership; final original nonce, purpose, consumption and expiry validation is immediately adjacent to successful consumption; ledger-only A needs no account anchor; rejected unknown and foreign proof stays unconsumed in one transaction.
- F9b.3: persistence and transaction completion boundaries map transient, acquisition, serialization, deadlock and in-doubt commit faults to cause-free safe 503 and unexpected faults to safe 500; typed lifecycle outcomes and commit uncertainty are preserved without proof restoration or automatic retry.
- F9b.4: gates and digest work precede the single sampled service instant; final freshness is required for new acceptance and for distinct-key owned-deleted authorization without consuming rejected proof; binding uses nonlocking discovery under the held subject advisory with account-first locked re-read.
- F9b.5: session revocation count must match the locked deterministic inventory with genuine zero valid and earlier revocations preserved; the first receipt is recomputed from re-read persisted normalized ledger columns in the same commit.
- F9b.6: the nullable fence bypass is removed and every constructed AuthService requires the collaborator; strict parsing, provider verification, receipt encoding and fault mapping are shared through cohesive non-signup helpers with small explicit transaction stages; export, auth and domain algorithms are otherwise unchanged.
- F9b.7: the ring parser uses strict duplicate detection with EOF, object and unknown-field validation; malformed configuration stays sanitized without secret causes; version, framing and key behavior are unchanged.

## Second narrow correction dispositions (no verification yet)

- Every blocking admission step, including digest work, session inventory and the pre-admission hook, precedes a single unrounded service-time freshness sample; conditional consumption immediately follows that decision and only truncated values are persisted. Owned-deleted authorization uses current final freshness after its lock waits.
- Transaction retryability positively recognizes only explicit acquisition, timeout and in-doubt completion failures, with definitive control, programming and rollback-only faults scanned over the entire chain first so neither an outer wrapper nor a nested transient cause masks them. Provider verification keeps its separate transport classification with cause-free sanitized envelopes.
- Unavailable new acceptance is screened before provider work after initial receipt recovery, with scoped serialized recovery on closed-gate and preliminary rejections; cheap preliminary purpose, consumption and expiry validation is restored without holding locks across Google, and readiness is rechecked in the final transaction.
- Ownership admission and checked-effects receipt work are named explicit stages; checked current binding and ledger rows are used under the held subject advisory with account-first locking and no stale discovery fallback. Status keeps the same simple checked stages.

## Read-side seam dispositions (no verification yet)

- Exact seam API: `persistence.DurableCopyHooks` with `beforeCursorFence(UUID)`, `afterCursorFence(UUID)`, `beforeSnapshotFence(UUID)` and `afterSnapshotFence(UUID)`; inert production bean `persistence.NoOpDurableCopyHooks` with no property, endpoint, storage, logging, decision flag or readiness effect. Stable new paths: `src/main/java/com/dragosfotache/gtbackend/persistence/DurableCopyHooks.java` and `NoOpDurableCopyHooks.java`.
- Injection: `SyncCursorRegistry` and `SyncSnapshotRepository` each keep their existing one-argument constructor delegating to a real non-null inert hook and gain an explicitly Spring-marked two-argument constructor; the legacy `RoutineUpsertExecutor` manual cursor construction keeps the fence. Callbacks run immediately before and after the existing bound `FOR SHARE` fence on new-copy paths only, never on the existing-token path, preserving SQL, same-transaction lock lifetime and insert order. No mutation-engine, auth, V8, config, export or build change.

## Third tiny clarification dispositions (no verification yet)

- Transaction retryability uses positive classification of explicit acquisition, timeout and in-doubt completion failures; definitive control, programming and rollback-only states stay unexpected. The full cause chain is scanned for a definitive fault before any outer completion wrapper counts as retryable.
- Status compares one unrounded service sample for freshness and truncates only the persisted consumption timestamp, consistently with deletion; cheap preliminary purpose, consumption and expiry screening is kept for new status proof.

## Acceptance pointer (bounded 9b only)

- Coordinator has accepted bounded 9b in `docs/task-9b-acceptance.md` on identical final inputs. That document records the dedicated, Worker self-check, independent integrated and full, and structural and manual HTTP/PostgreSQL gates. This Worker report remains diagnostic evidence; acceptance authority belongs to the Coordinator document. Ordinary readiness stays false with purge intent pending, and the 86,400-second deadline is unchanged.

## Main compile evidence (no behavioral claim)

- Worker-only main compile slot at `/tmp/gt-backend-task9b-worker-main-compile-LEXp6zj9/` ran once under the build lock with exit 0 and `BUILD SUCCESS` over 63 main Java files, 07:45:08Z to 07:45:11Z UTC. Before and after manifests match the starting 107-path freeze at `bbe067...dbd16ec`. No tests ran and no fresh XML is claimed.
- Logging variance, honestly recorded: the planned command in `command.txt` carries `-q`, while the single executed run omitted it (exact command in `actual-command.txt`; explanation in `evidence-note.txt`; exit and timestamps in `timestamps.txt`). The variance is logging-only; the slot was not rerun.

## Worker self-check evidence (diagnostic, not acceptance)

- Worker-only self-check at `/tmp/gt-backend-task9b-worker-selfcheck-9b01/` ran once under the build lock with exit 0, 12:14:19Z to 12:16:25Z UTC: `106 tests / 17 suites`, zero failures, errors or skips across the `AccountDeletion*`, account status, export and auth suites. Before and after manifests match the final 148-path freeze at `3342c617...45317629c`, with a complete matching pre-run input mirror, exact command, full log and fresh copied XML. Stale target reports were moved aside without Maven clean before the run. Earlier historical claims of untested authoring no longer describe this state, but this self-check is diagnostic evidence only: acceptance still requires QA integrated and full gates plus Coordinator structural and HTTP/PostgreSQL gates on identical final inputs.
