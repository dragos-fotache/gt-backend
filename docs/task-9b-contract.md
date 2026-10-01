# Task 9b — deletion acceptance, permanent minimal evidence and account status

## Status, dependency and release boundary

**Planned; implementation is not authorized.** Depends on accepted 9a. Read `docs/task-9-contract.md` for common proof, wire, role, baseline and safety rules. Obtain separate slice authorization and approve concrete ledger, keys, locking, durable purge intent and configuration before authoring.

This slice implements and independently tests the irreversible acceptance/status core. **Ordinary deployment must not accept deletion before 9c supplies a verified purge capability and its release conditions are met.** A reviewed fail-closed readiness gate is required; isolated real-HTTP tests may explicitly enable the 9b acceptance path. Do not return 202 while the capability is absent, consume proof on an unavailable new acceptance, or claim that a durable queue alone satisfies physical purge. Existing accepted receipt recovery must remain available even if new acceptance is subsequently gated off.

## Fixed endpoints

### Delete account

`POST /v1/account/delete` uses purpose `deleteAccount`, required `Idempotency-Key`, and OpenAPI `DeleteAccountRequest`: `{accountId, challengeId, idToken, confirm: true}`. An ordinary service session or logout action cannot authorize it. Missing or false confirmation returns `422 validation_error` without consuming proof or claiming a key. Validate structural shape before irreversible effects.

For a new valid request, prove that the verified Google subject owns the specified account. In **one acceptance transaction**:

1. Serialize the acceptance key, verified provider identity and account against sign-in/rebinding, accepted writes, refresh, competing deletions and export admission.
2. Recheck valid unconsumed purpose-bound proof and account ownership.
3. Record exactly one permanent minimal deletion receipt and request identity.
4. Disable the account and all its sessions, preventing further ordinary access and grant replay/rotation.
5. Consume the successful challenge, detach that account's current Google identity binding, and record durable retryable purge intent with the original deadline.
6. Commit all effects together; return `202 {accountId, state:"deleted", deletedAt, purgeBy}` with no-store.

Use one recorded acceptance time and `purgeBy = deletedAt + 86,400 seconds`; neither field is supplied by the client. `deleted` means irrevocably disabled and accepted for purge, not physically erased. There is no recovery window, cancellation or restore route. Backend checks confirmation; Android warnings and optional export UI remain client work.

Inspect real lock acquisition, including AuthService's provider-subject advisory lock, account/session/credential locks, auth replay rows, challenge locks and snapshot foreign-key key-share locks. Preserve established fences and avoid circular ordering. Writes committed before acceptance remain committed; operations cannot commit new account writes after the accepted disablement boundary. Do not promise retroactive erasure of response bytes already delivered.

### Accepted deletion replay

Replay is scoped to **old account UUID and deletion operation**, separate from auth/resource/sync namespaces. Store a keyed digest of the exact original UTF-8 body bytes, not reserialized JSON, raw Google credentials or an unbounded attempt history.

Look up an identical accepted deletion before rejecting its expired Google token, consumed/deleted challenge or revoked/purged old session. It returns the original 202 receipt even after restart and later purge, without creating an account, issuing credentials, changing timestamps or restarting/rescheduling the deadline. Possession of a key or account UUID alone must not disclose a receipt. Changed bytes under the original key return `409 idempotency_key_reused` and preserve the evidence. Include whitespace and property-order differences in tests.

A different key against an already-deleted owned account requires valid fresh delete-purpose proof and returns `409 invalid_state`; it does not create a second ledger record or consume an otherwise valid rejected challenge. Recover the receipt through fresh account-status proof instead. Unknown/foreign ownership remains `404 not_found`, not an unverified deletion signal.

### Account status

`POST /v1/account/status` uses purpose `accountStatus` and `{accountId, challengeId, idToken}`. A live bearer is not required and is not proof by itself. Return `200 {accountId, state:"active"}` for the owned live account or the original deletion receipt for the owned deleted account, with no-store. Consume a successful status challenge; a lost response requires another challenge, not a stored status replay.

Resolve live ownership through the current identity binding and deleted ownership through the permanent keyed evidence. Support old account A after the same Google identity owns new account B. Status/export/delete must never invoke signup or mint a grant. Unknown/foreign account is 404; generic 401/404, missing evidence or network failure is not confirmation permitting client cache erasure. No public UUID lookup is introduced.

## Permanent ledger and key management

Retain only the shared whitelist: old account UUID; versioned keyed digest of verified provider plus subject; deletion time and original purge deadline; original accepted deletion key; keyed exact-request digest; and fields needed to reproduce the original receipt. Provider digests remain identifying metadata, not anonymous data. Do not retain email, display name, raw provider subject, raw token, access/refresh credentials, copied exports, domain records, sync responses or attempt lists in this ledger.

The ledger survives physical removal of ordinary account rows and must not cascade away with them. Keep any mutable purge progress separate from permanent evidence; operational state must not become an excuse to retain payloads or unnecessary personal metadata indefinitely. One immutable receipt per deleted account is required.

Approve a versioned, domain-separated verification-key design before authoring. Keys remain outside the database and Git; old evidence must still verify after active-key rollover. Do not casually rotate the current development auth key, silently drop old verification keys, store signing secrets in the ledger, or use plain subject hashes. Unknown/missing required key versions fail closed. Tests use synthetic keys and actual restart/rollover with retained historical verification capability. Deployment values and secret provisioning are not supplied by this plan.

Detach only account A's current binding. A subsequent fresh sign-in may already create empty B because V4 permits binding replacement. Consequently **9b must prove basic re-signup isolation now**: new UUID, no attachment of A's data or credentials, and no old auth replay creating B. Deeper purge races and after-purge evidence belong to 9c, but security cannot wait until then.

## Export and ordinary API integration

Coordinate actual deletion acceptance with 9a export. Deletion before first output prevents export release; accepted deletion observed during output stops later release, without finalizing a partial JSON document as a successful complete file. Reuse the reviewed admission/cancellation boundary, not a frozen account-row check that can never see new disablement.

After acceptance, representative new direct mutations/reads, browsing, push, pull and snapshot continuations reject ordinary access; access/refresh and old grant replay cannot renew the disabled account. Other accounts remain unaffected. Do not publish synthetic root deletes or retain a whole-account domain batch merely to signal account deletion: the fresh authenticated status receipt is the specified account-bound signal.

## Required no-edit design

Provide a concrete state transition, lock graph, ledger fields/indexes, exact-byte digest and historical-key verification scheme, replay-before-proof-expiry ordering, purge-intent durability and readiness gate. Explain fresh sign-in binding races, already-committed grant responses, export cancellation and the status path after account-row removal. Any bounded auth extraction, new forward migration or configuration change needs Coordinator approval; V1–V7 remain immutable.

## Fixed acceptance criteria

**AC9b.1 Proof, confirmation and release gate.** Real HTTP verifies valid fresh proof with no bearer, bearer-only denial, wrong purpose/subject/account, exact proof expiry, consumed/invalid proof, missing/false confirmation and structural validation. Failed requests leave proof, identity, account, sessions, domain and ledger unchanged. With purge readiness absent, a new delete fails closed without acceptance; an already accepted identical receipt can still be recovered. Test configuration enabling acceptance is explicitly identified, not deployment readiness evidence.

**AC9b.2 Atomic irreversible acceptance.** Create rich owned state and several sessions through accepted surfaces. Assert original receipt/deadline, one ledger record, durable purge intent, consumed challenge, disabled account/all sessions and detached A binding in one transaction. Inject failures before commit and at representative intermediate steps: all acceptance effects roll back, then an unchanged valid request can succeed. No false 202, partial disablement or domain deletion before authorization.

**AC9b.3 Exact retry and durable receipt.** Concurrent identical requests yield one acceptance and one immutable original receipt. Change raw whitespace/property order/credential/body under the key and observe 409 without evidence replacement. Same key in unrelated namespaces/accounts does not retrieve another outcome. Stop the service process that accepted deletion and recover the identical receipt in a new process after challenge/access expiry and revocation. Controlled minimal-ledger-only fixtures supplement the later actual-purge test; do not claim they implement purge.

**AC9b.4 Authenticated status and minimization.** Route evidence covers active/deleted status, old A while B owns the same subject, foreign/unknown targets, purpose separation and successful one-use status consumption. No proof route creates an account or grant. Inspect every permanent field and assert no raw body/token/subject/profile/domain payload. Rollover from synthetic key version 1 to version 2 preserves verification of old records; missing required keys fail closed. Minimal evidence has no age TTL, including a controlled time more than 90 days later.

**AC9b.5 Live fences and concurrency.** Deterministic real PostgreSQL/HTTP gates cover delete versus direct/sync writes, refresh and old grant replay, competing deletion, fresh sign-in/binding replacement, snapshot capture/continuation and export before/during output. Observe valid serialized outcomes, no post-acceptance write/grant resurrection, no hybrid success or fixture-imposed circular waits, and preservation of another account. Source review includes implicit foreign-key locks and actual transaction participation.

**AC9b.6 Basic new-account separation.** After A is disabled/detached, only a distinct fresh sign-in creates empty B with a new UUID. Old credentials, exchange retries, cursors, snapshot/list tokens and accepted-deletion replay do not authorize or implicitly create B. Old-A status identifies A, not B; no domain/replay/reservation ownership is reassigned. Do not claim backend enforcement of Android cache clearing that has not been implemented.

**AC9b.7 Structure and frozen verification.** Coordinator reviews separate proof, acceptance, status, ledger/crypto and persistence responsibilities, checked writes, understandable lock ordering and sanitized errors/logs. No controller SQL, enlarged auth multi-mode method, broad mutation refactor or permanent sensitive request storage. Worker self-check, independent 9a/9b and accepted API regressions/full suite, then Coordinator HTTP/PostgreSQL spots use identical frozen inputs with fresh evidence. 9b acceptance is explicitly bounded; the physical deadline and production release gate remain pending 9c.

## Completion handoff to 9c

Supply the exact permanent whitelist, digest versions and external-key assumptions, acceptance boundary, FK/lock graph, durable purge-intent schema, readiness-gate behavior and full data-store inventory. Record which deletion/export/sign-in races are already direct evidence. Do not enable ordinary deletion, claim deadline compliance or start 9c without its explicit authorization.
