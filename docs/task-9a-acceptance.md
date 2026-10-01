# Task 9a — accepted

The Coordinator accepts **bounded slice 9a** under `docs/task-9a-contract.md`: reusable non-signup fresh Google proof and direct consistent JSON account export. The mandatory structural gate passes. This is not complete Task 9, complete-v1, deployment, live Google, Android or deletion/purge acceptance. **9b, 9c and Task 10 remain unauthorized.** All 9a Maven windows are closed.

Worker and QA ran in Pi using **Muse Spark 1.3 Contributor Medium** (`meta/muse-spark-1.3-contributor`, `medium`). Worker owned production; QA owned requirement-first tests; Coordinator owned contracts, findings, source/coverage review, evidence verification and real HTTP/PostgreSQL spots, not production or test authoring. Existing panes/cwd were preserved; no context/compaction management or further agent replacement occurred. mcpls Java navigation reported initialization in progress; read-only fallback was used without tooling changes.

## Accepted behavior

- `POST /v1/account/export` accepts strict `{accountId, challengeId, idToken}` with an export-purpose challenge and no service-bearer prerequisite. Purpose, nonce, signature, issuer, audience, Google expiry, subject and specified live ownership are checked. Rejection does not consume valid proof, provision an account or issue credentials. Successful proof is one-use, with exact stored 300-second expiry checks, including after provider and SQL-lock waits.
- The actual streaming thread establishes a read-only PostgreSQL repeatable-read view before admission. Separate short raw-connection consumption serializes challenge, provider-subject and account locks; the final nonce/purpose/consumption/expiry checks precede its conditional update and commit. An admitted interrupted read does not roll back that consumption.
- Export version 1 includes all live owned customs, routines including drafts/blank saved autosaves, and active/finished workouts. IDs, relationships, ordering, labels, metadata, nulls/zeros, rest, notes, completion and timestamps are preserved. Arrays are always present and ordered by canonical UUID text; catalogue version is `gymtracka-catalogue-v1`. Catalogue roots, tombstones, foreign data, revisions, volume and sync/credential internals are excluded.
- Whole roots are read in bounded root-ID chunks and emitted as a direct JSON attachment named `gymtracka-export.json`. No export artifact, public link, continuation token, response replay, credential extension, domain revision, change batch or cursor metadata is created.
- Independent current-view checks guard first output, outward segments of at most 8 KiB, closure and success flush without blocking account write locks for a slow client. Observed disablement stops later output. Pre-byte errors are sanitized and no-store; post-byte abort is handled without appending an error envelope, finishing partial JSON or flushing failure data. Already emitted bytes cannot be recalled.
- Real Google parser failures normalize to credential 401; provider certificate I/O becomes lifecycle 503; unexpected faults remain 500. Signature and claim checks remain intact. AuthService, accepted grants/replay/session behavior and long-term key configuration are unchanged.

## Scope and findings

Baseline is backend commit `36fe88d8951d17b66a45a537497fcd1b5dacf8d3`; supplied uncommitted plans and independent dirty shared checkout were preserved. Production changes are six new classes, the narrow existing no-store filter extension, and the explicitly approved Google verifier parse/provider split. QA adds seven HTTP suites and one conditional test-only child gate, plus one additive verifier test. No dependency/build/runtime configuration, schema, applied V1–V7 migration, shared input, secret or local database was changed.

`docs/task-9a-review.md` records the no-edit design refinements and resolved F9a.1–F9a.9/Q9a.1–Q9a.6 findings. Important corrections included real read admission before irreversible consumption, original verified nonce/final expiry checks, VOID-safe advisory locking, explicit rollback, cohesive proof separation, correct SQL/provider errors, and the export-specific async no-body abort handler. QA fixed incorrect pre-commit retry expectations and strengthened deterministic races, actual originator restart and real hash/snapshot evidence without weakening requirements.

Earlier compile/fixture/failing runs are historical/debug evidence. In particular, stale original-manifest OK/FAILED lists do not prove changed-test freezes and are not acceptance evidence. The one-byte-reader restart fixture was superseded by a gate in the actual admitting process after consumption. Parent observes that gate and consumed SQL, stops A before starting plain B, retries the exact original proof to 401, then fresh proof succeeds. The blocked test-created originator required forced SIGKILL (exit 137); that outcome is recorded honestly and death-before-recovery is asserted.

## Fixed criteria

| Criterion | Final evidence |
|---|---|
| AC9a.1 | Real challenge issuance, default/all purposes, ownership/no-bearer/no-signup, exact Clock equality and provider/account-lock expiry, nonce/consumption/concurrency; real parser/signature/claim regressions. |
| AC9a.2 | Hand-authored request/catalogue oracle, rich/empty/draft/saved/active/finished shapes, full values and IDs, canonical UUID order, exclusions and production-pruning survival. |
| AC9a.3 | Real HTTP attachment/no-store/errors, intact large aggregate, recursive minimization, no new durable export/replay/domain/credential state and released connections. |
| AC9a.4 | Certain pre-commit rollback with unchanged-proof retry, confirmed post-consumption failure with old-proof denial, and interrupted admitting-process recovery against the same isolated database. |
| AC9a.5 | Deterministic real writers and coherent view/labels, admitted-before-first-byte and mid-large-root/final-closure disablement, independent current checks and no circular fixture waits. |
| AC9a.6 | Coordinator source/baseline-relative structural gate: separate proof/HTTP/mapping/streaming boundaries, actual transaction participation, checked writes and sanitized failure handling; no legacy mutation/auth growth. |
| AC9a.7 | Final dedicated, Worker, independent integrated/full and eleven Coordinator spots on identical corrected frozen inputs, with fresh copied reports, exact commands, exits, timestamps, hashes and matching snapshots. |

## Final frozen verification

Every Maven operation used `flock /tmp/gt-backend-task7-build.lock` and an explicit relevant-input freeze. Final corrected inputs have **115 paths**, manifest SHA-256 **`963fd084c24e2010a95de059738a0a2454fe6501cbeb4ac3072b45a5a59dee34`**. The Coordinator independently checked fresh copied XML totals, exits/timestamps, equal actual before/after manifests and matching live/source snapshots.

| Run | Result | Evidence directory |
|---|---|---|
| Starting baseline (historical starting inputs) | 208 tests / 18 suites | `/tmp/gt-backend-task9a-qa-baseline-9a01/` |
| Final combined dedicated | **41 tests / 8 suites** | `/tmp/gt-backend-task9a-qa-google-boundary-ihxdf5tg/qa-boundary-dedicated-01/` |
| Final Worker self-check | **204 tests / 17 suites** | `/tmp/gt-backend-task9a-worker-final-selfcheck-bdr30vtx/` |
| Final independent integrated QA | **227 tests / 22 suites** | `/tmp/gt-backend-task9a-qa-final-zlszs3g5/qa-final-integrated-01/` |
| Final independent full QA | **248 tests / 25 suites** | `/tmp/gt-backend-task9a-qa-final-zlszs3g5/qa-final-full-01/` |
| Coordinator HTTP/PostgreSQL spots | **11 tests / 5 suites** | `/tmp/gt-backend-task9a-coordinator-spot-isxls3co/` |

All listed green runs exited 0 with no failures, errors or skips. The baseline uses its separate 98-path starting manifest `5419d57da52ae5b76b8431c4f94b4efc91272c407352e190976b56953b8f2bbf`; all five final corrected runs use 963fd084 above. Exact commands/logs are in evidence and role reports. Coordinator spots independently re-ran proof expiry, full-value oracle, pre/post-admission failure, actual process restart, coherent writer and pre-byte/mid-root/closure cancellation, malformed credentials and provider recovery. Current mutable Surefire reports contain only those eleven spots, not the full-suite evidence.

## Explicit limits and handoff

The truly uncertain commit-acknowledgment instant was not fault-reproduced. Certain rollback, confirmed commit, code-reviewed in-doubt 503/no-restoration and actual interrupted-originator durability are distinct evidence; no exhaustive fault claim follows. Tests use isolated PostgreSQL 16 and real HTTP, with synthetic keys/Google boundary doubles or stub certificates, not live/deployed Google verification. No capacity/availability, explicit provider-timeout or atomic network-erasure guarantee is claimed. A large root is materialized in memory and a long stream retains a pooled connection/MVCC view; finite-pool exhaustion fails closed in tested scenarios.

Controlled disablement must be reverified through actual deletion in separately authorized 9b/9c. Do not enable ordinary deletion without verified purge readiness or relax the separate 24-hour physical purge deadline. Status, permanent evidence, crypto key rings, purge, new-account isolation, backup/restore and operations remain later work. Existing stopped-Testcontainers cleanup/Hikari warnings remain separate debt.

Durable checkpoint: `/home/dragos/.local/state/gymtracka-backend/coordinator-checkpoints/task-9a-20261001T150307Z/`. Original evidence directory names are retained underneath; substitute that prefix for `/tmp/` in evidence links. `project-state/` contains curated accepted inputs/docs. Verify `checkpoint-files.sha256` from the checkpoint root. This is local handoff evidence, not deployment/disaster-recovery acceptance; no Git checkout, secret file, local database, mutable target or stale packaged application JAR is included. The legitimate Maven wrapper input is retained.

Only status/acceptance documentation updates follow final verification; the 115 frozen inputs remain unchanged. No staging/commit, push, deployment, shared reset/edit, applied local migration or unrelated shutdown occurred. Worker and QA are idle awaiting explicit further authority. No 9b/9c, Task 10 or automatic future commit is authorized.
