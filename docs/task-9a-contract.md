# Task 9a — fresh account proofs and direct JSON export

## Status, dependency and authority

**9a is authorized; baseline/design gates passed and authoring is released.** The user authorized this slice with the current Muse Spark 1.3 Contributor Medium Worker/QA pair in Pi. Read `docs/task-9-contract.md` for shared authority, the committed baseline, role ownership, verification windows and protected resources. Fresh frozen QA baseline passed 208 tests across 18 suites with zero failures/errors/skips. The Coordinator approved the amended QA plan and Worker revision 2 corrected by R3; execution/approval details are in `docs/task-9a-review.md`. Worker/QA may author disjoint 9a production/tests; no Maven window is open until stable handoffs and a new explicit input freeze. Slice 9a is first; 9b depends on its accepted proof/export boundaries and remains unauthorized. No agent replacement, unreviewed schema/configuration/build changes or another commit is authorized.

Normative sources are account-lifecycle, authentication, retention, service-protocol, OpenAPI export schemas, domain rules and compatibility requirements. Existing V4 and AuthService already support all four challenge purposes. Do not claim that adding the purpose enum is new work or migrate it unnecessarily.

## Fixed scope

Implement `POST /v1/account/export` and the reusable **non-signup** proof authorization needed by subsequent lifecycle routes. Reuse Google verification and challenge issuance safely, while preserving accepted sign-in, refresh, logout and replay behavior. No deletion endpoint, permanent deletion ledger, account purge, restore/import feature or server export artifact belongs to 9a.

The common proof service must not call the account-provisioning exchange path. Export proof for an unknown identity/account cannot create an account or issue a session. Keep a clear distinction between verified provider claims, purpose/account authorization and signup.

## Proof and HTTP behavior

- Request body is OpenAPI `AccountGoogleProof`: `{accountId, challengeId, idToken}`. No client-controlled owner, export filters, page token, destination URL or canonical fields are accepted.
- Verify fresh Google signature, issuer, configured audience, expiry, nonce and subject. Challenge purpose must be `export`; it expires after 300 service seconds and is one-use. Export and deletion/status/sign-in proofs are not interchangeable.
- Match the verified provider subject to the specified live account's current identity binding. A bearer session alone is insufficient; a live bearer is not an additional prerequisite. Missing/foreign/deleted ownership is `404 not_found`; invalid, expired or consumed proof is `401 invalid_google_credential`. Rejected proof/account validation does not consume an otherwise valid challenge.
- On success return `200 application/json`, `Cache-Control: no-store`, and an attachment named `gymtracka-export.json`. All errors also have no-store and sanitized shared envelopes.
- There is no export idempotency namespace, durable response replay, continuation token, public download link, email delivery or retained export file. An optional unrelated idempotency header must not create replay behavior. Do not persist the raw Google token, exact proof body or export payload.

## Consistent export and irreversible admission

Return version 1 with required fields `exportVersion`, `accountId`, `exportedAt`, `catalogueVersion`, `customExercises`, `routines` and `workouts`. Use the pinned `gymtracka-catalogue-v1` value, not the illustrative fixture version. All arrays are present, even when empty, and individually ordered by canonical UUID ascending.

Include every live owned custom exercise, routine including drafts and legitimate blank/empty saved autosaves, and active/finished workout. Preserve root/child IDs, references, consecutive order, nullable and zero values, configured/recorded rest, notes, completion and compatible UTC timestamps. Named relationships and convenience exercise labels come from the same consistent read view. Exclude catalogue roots, tombstones and foreign data.

Use the exact OpenAPI export shapes. Do not return sync envelopes, revisions, mutation IDs, cursors, ownership/session/credential internals, or workout fields forbidden by the narrower export schema. Removing a revision only from an outer envelope is insufficient. Do not assume that an existing GET response is already a valid export DTO.

Consume the challenge when the authorized consistent export read is admitted. **Consumption must remain durable after a lost or interrupted stream.** A failed/partial response requires a fresh challenge and new export; do not roll back consumption after releasing bytes and make the old proof reusable. Conversely, rejected authorization or failure before the admission boundary must not consume proof as if an export started. The Worker must explain this boundary and transaction participation before authoring.

One live-account view must survive the complete stream. Opening a transaction on the controller thread and accidentally reading through unrelated transactions on an asynchronous writer is not sufficient. Stream/chunk across roots where practical; do not introduce account/aggregate caps, truncate a supported root or use an on-disk export spool to avoid the consistency problem.

Design for later deletion integration now. Before releasing any bytes, observe accepted account disablement and abort an export that has not begun. Once bytes are released, stop further output when deletion is observed; already-delivered bytes cannot be recalled. A frozen repeatable-read account row alone cannot observe newer deletion. Do not hold a lock through an indefinitely slow client and call that cancellation. Explain admission, live cancellation checks, lock ordering, connection lifetime and transport failure handling. An incomplete JSON download must not be finalized into a syntactically complete success with missing records.

In 9a, controlled isolated-database disablement can test this boundary because 9b is not implemented. Repeat the races through the actual deletion endpoint in 9b/9c; fixture evidence is not deletion acceptance evidence.

## Required no-edit design

Propose cohesive proof validation/consumption, account authorization, export mapping, consistent streaming and deletion cancellation responsibilities. Explain how durable challenge consumption coexists with one export read view and an interrupted response. Identify real clock sources and an exact-boundary test seam; preserve 15-minute access and sliding 30-day refresh behavior. Any bounded auth extraction, schema/configuration change or dependency need requires Coordinator approval before authoring. V1–V7 remain immutable; no migration is assumed for the already-supported challenge purposes.

## Fixed acceptance criteria

**AC9a.1 Purpose-bound fresh authorization.** Independent real HTTP tests use two accounts and actual challenge issuance. Cover `{}` default sign-in compatibility and all represented purposes; correct export proof, wrong purpose/subject/account, absent bearer with valid proof, bearer-only denial, exact 300-second boundary, consumed challenge, nonce mismatch and concurrent same-challenge attempts. Rejected requests leave challenge and protected data unchanged. No account or grant is created by lifecycle proof validation. Mock only external Google behavior, and retain real verifier claim/signature regressions.

**AC9a.2 Complete independent export oracle.** Publish representative customs, empty/populated drafts, saved and blank/empty saved routines, active/finished workouts through accepted APIs. Assert requirement-derived complete export records, child UUIDs, relationships, labels, nulls/zeros/rest/notes/timestamps, all required arrays and version/catalogue fields. Check canonical UUID ordering across the signed UUID boundary, old unchanged live roots after production history pruning, and exclusion of tombstones, catalogue and foreign rows. Validate against export schemas and runtime rules; do not use the production mapper or a GET response as the oracle.

**AC9a.3 Stream, headers and data minimization.** Real HTTP verifies attachment, content type and no-store on success and representative errors. Include a large complete aggregate and multiple roots without byte or product truncation. Inspect that no export sessions/files, response cache, raw proof/token storage, new revision, change batch, replay result or credential extension is created. Recursive assertions exclude internal and credential fields, not only top-level owner IDs.

**AC9a.4 Admission and failure recovery.** Deterministic test-owned gates/faults distinguish pre-admission rejection from post-admission read/stream failure. Failed or cancelled admitted downloads consume the proof durably, including after stopping the admitting service process and restarting against the same isolated database. A fresh challenge can retry; the old challenge cannot resume or reproduce a stored export. No successful-looking partial JSON or credential-bearing diagnostics are released.

**AC9a.5 Coherent reads and deletion-ready cancellation.** Coordinate real writers, renames, child replacements and deletes during export; all exported authoritative fields and labels belong to one view. Controlled account disablement before first output releases no export data; disablement during output stops later release without claiming earlier bytes erased. Source review proves transaction participation and independent live cancellation observation. Gates must not impose circular waits through locks they themselves hold. Reverify with real deletion in later slices.

**AC9a.6 Maintainability and preservation.** Coordinator reviews cohesive proof, HTTP, mapping and streaming boundaries. No controller SQL, signup side effect, nullable lifecycle mode, auth god-method growth, speculative framework or mutation refactor. Existing challenge/exchange/refresh/logout and all accepted mutation/read/pull/pruning/snapshot behavior remain unchanged except an explicitly reviewed local extraction. New sensitive paths do not log proof or exported data.

**AC9a.7 Frozen independent acceptance.** Worker stable handoff and relevant self-check; QA dedicated export/proof coverage, integrated accepted auth/API/sync regressions and full suite; Coordinator structural review and real HTTP/PostgreSQL spots on identical corrected frozen inputs. Preserve exact commands, fresh XML counts, exits and manifests. Acceptance is bounded to proofs/export, not deletion, purge, real deployed OAuth, Android integration or operations.

## Completion handoff to 9b

Document the irreversible export admission point, cancellation seam, actual lock participation, verified proof API, allowed auth changes, exact evidence and remaining integration tests. Mark whether controlled-disablement races still need actual deletion-endpoint evidence. Do not enable or author 9b merely because 9a passes; obtain its separate authorization and readiness-gate approval.
