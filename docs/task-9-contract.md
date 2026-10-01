# Task 9 — account lifecycle: three-slice plan

## Status and authority

The user initially authorized **planning documents and a handoff**, then explicitly authorized starting **9a only** with the current Muse Spark 1.3 Contributor Medium Worker/QA pair. Execution begins with the exclusive frozen QA baseline, requirement-first plan and no-edit Worker design; authoring requires Coordinator approval. 9b and 9c remain planned and unauthorized. Obtain explicit authorization for each slice before assigning implementation, editing production or tests, starting or replacing agents, or authoring schema/configuration changes. This planning request does not authorize another commit. Task 10 and dedicated legacy refactoring remain outside scope.

Read this overview with the individual contracts:

1. [9a — fresh proofs and export](task-9a-contract.md).
2. [9b — deletion acceptance and account status](task-9b-contract.md).
3. [9c — complete purge, re-signup isolation and integrated verification](task-9c-contract.md).

Shared authority is `shared-docs/docs/backend-contract/README.md`, `account-lifecycle.md`, `authentication.md`, `retention.md`, `service-protocol.md`, `openapi.yaml`, `schema.md`, `domain-rules.md`, `client-compatibility.md`, `android-client-protocol.md`, `sync.md` and `acceptance-scenarios.md`. Read relevant documents completely before work. The export and deletion fixtures are illustrative, not a production seed or expected-value oracle. Resolve discrepancies explicitly; local plans do not override shared behavior. No Android source is required.

## Committed starting point

Backend baseline: **`36fe88d8951d17b66a45a537497fcd1b5dacf8d3`**, `Establish accepted backend baseline through task 8c`. The backend now has a real Git baseline; use baseline-relative Git diffs and content hashes, not the historical untracked-repository workflow. These planning documents are later, uncommitted documentation changes. Do not discard supplied working-tree changes.

Task 8 is accepted within its bounded scope. Final independent QA passed 187 targeted and 208 full-suite tests; the final Worker self-check passed 147 tests, and the Coordinator passed eleven HTTP/PostgreSQL spots. All had no failures, errors or skips. Exact evidence and limits are in `docs/task-8c-acceptance.md`. The verified behavioral manifest is `440efc494f7fffda03e74c6ef304fc27052a78d81a910e50bb0001974817a148`; later status and commit-hygiene documentation changes do not alter those production/test results. A new execution baseline is required before 9a.

`shared-docs/` remains an independent, ignored checkout, not a submodule. HEAD is `3aefeb3e2eaa147793d595833217d6070eeabff2`, with supplied working-tree changes. `docs/shared-docs-inputs.sha256` records the exact verified normative/catalogue contents; HEAD alone does not reproduce them. Preserve the checkout, its index and its edits. No shared changes are authorized by this plan.

## Slice boundaries and release safety

| Slice | Deliverable | Deliberate limit |
|---|---|---|
| 9a | Reusable non-signup fresh-proof authorization and consistent direct JSON export | No deletion acceptance, permanent ledger or purge runner. |
| 9b | Atomic deletion acceptance, minimal evidence, receipt replay, status and immediate access fencing | Acceptance logic may be tested in isolation, but ordinary deployment must not accept deletions without a verified purge capability. |
| 9c | Actual deadline-aware purge, durable recovery, re-signup isolation and all-slice integration | No backup scheduler, disaster restore, deployment or Android cache implementation. |

**Do not ship an enabled delete endpoint between 9b and 9c.** Before 9b authoring, the Coordinator must approve a fail-closed readiness/release gate. Normal configuration must not accept a new deletion when the verified purge capability is absent; isolated 9b HTTP tests may explicitly enable the acceptance path. This is staged implementation safety, not a change to the finalized deletion API. Do not expose a request parameter that bypasses the gate. Missing readiness must not consume proof, disable an account or issue a misleading receipt. A safe `503 temporarily_unavailable` is the planned unavailable-capability response; the concrete gate must be reviewed before code is written.

Identity detachment in 9b can immediately permit fresh sign-in to create account B. Therefore basic re-signup fencing belongs in 9b, not only 9c. Slice 9c proves and hardens isolation during and after physical purge. Do not defer an essential security fence simply to keep slice boundaries neat.

## Common proof, wire and privacy rules

- Lifecycle calls use service-verified Google proof for the specified account, not an existing bearer session alone. They intentionally work without a live service bearer. They do not sign in, create an account or mint a credential grant.
- Existing challenge issuance already accepts `signIn`, `export`, `deleteAccount` and `accountStatus`; V4 already stores those purposes. Extend safe consumption and account authorization, not the represented purpose enum unnecessarily. The default `{}` challenge remains `signIn`.
- Fresh proof is one-use and valid for 300 elapsed service seconds. At `now >= expiresAt`, reject a new proof use. Verify signature, issuer, configured audience, Google expiry, nonce, purpose and subject through the existing Google boundary; email/display name is not identity. Rejected authorization does not consume an otherwise valid challenge. The exception is an identical already-accepted deletion retry: recover its original receipt from the keyed request evidence before rejecting the expired or consumed original proof, without issuing credentials.
- All lifecycle responses, including errors, use `Cache-Control: no-store`. Error bodies use safe shared codes without credentials, private identifiers, payloads, SQL or stack traces. For new proof authorization, wrong/expired/consumed proof is `401 invalid_google_credential`; unknown or foreign account binding is `404 not_found`. Already-accepted identical deletion replay follows its explicit receipt-recovery exception.
- Parse strict UTF-8 JSON objects without duplicate keys or coercion. Malformed JSON/encoding is `400 invalid_request`; unknown represented fields are `422 validation_error`. Account IDs must be canonical UUIDs. Preserve the accepted authentication classification for missing or malformed credential fields. Missing/false deletion confirmation is explicitly `422 validation_error`. Confirm the remaining structural/header mappings against the shared contract before authoring; do not invent precedence across independent invalidities or alter accepted auth routes incidentally.
- Export and status require exactly the proof body represented in OpenAPI. Delete additionally requires `confirm: true` and a valid `Idempotency-Key`. These routes introduce no mutable-resource or sync replay namespace. Export/status have no stored response replay.
- Raw Google tokens and credential-bearing request bytes may exist transiently for verification but must not be persisted or logged. Only deletion's keyed exact-byte request digest and whitelisted receipt evidence survive. Do not use unkeyed provider-subject hashes or treat identifying digests as anonymous.
- Transient database/service faults use safe HTTP 503 responses; unexpected faults use safe HTTP 500 responses. After response bytes begin, do not pretend a failed stream can be replaced by a successful JSON envelope.

## Strict account-purge deadline

Accepted deletion requires complete physical removal of domain-bearing data from live storage within **86,400 seconds of acceptance**. The snapshot cleanup clarification permits lag only for ordinary snapshot expiry; it does **not** relax account deletion. Backups' 30-day lifetime is also not an extension of this deadline.

A fixed-delay job and finite work budget alone do not prove this requirement. Before enabling acceptance, design deadline-aware scheduling, crash recovery, large-account progress and sufficient capacity under documented operating assumptions. Tests must exercise failures and backlog without crossing the deadline. Missing production capacity/availability evidence must remain an explicit release blocker; do not claim an unconditional SLA from controlled tests, move `purgeBy` on retries, or silently reinterpret the policy as eventual cleanup. If the requirement cannot be met, escalate to the user before changing it.

## Known implementation boundaries to review

- `auth/AuthService.java` already issues purpose-bound challenges, performs Google exchange, serializes provider subjects with a PostgreSQL advisory transaction lock, provisions `google_identity_bindings`, and handles credential replay/rotation. Its existing successful auth behavior must remain stable.
- `auth/SessionAuthorization.java` fences writes with account/session/credential row locks and provides nonlocking authorization for coherent reads. Read-only authorization is not a deletion-aware export-stream cancellation mechanism by itself.
- `resource/CanonicalResourceMapper.java` and `persistence/CanonicalAggregateRepository.java` provide canonical projections. Export must use the narrower OpenAPI export shapes, not copy sync envelopes or retain revision/internal fields.
- V3 child/tombstone triggers permit physical child purge only after the owning account is disabled. Preserve that boundary; do not disable triggers to get purge tests green.
- V7 snapshot headers and cursor registration can acquire implicit account key-share locks. Snapshot headers reference account-bound cursor tokens with `ON DELETE RESTRICT`; remove snapshot storage before its referenced cursor metadata. Review actual locks, not just Java annotations.
- Auth replay and credentials reference sessions; domain children, changes, reservations, sync state and replay reference roots/accounts. Build an explicit account-data inventory and FK-safe purge order. The permanent ledger must survive account-row removal and cannot retain domain copies.

## Mandatory design approvals before authoring

The Worker must first return a no-edit design. The Coordinator approves concrete transaction/lock ordering, export admission and cancellation, ledger whitelist and versioned key verification, schema changes, purge scheduling/budgets and the deletion readiness gate before releasing edits. Do not pre-author V8 or choose new secrets/configuration by assumption. V1–V7 are immutable; any needed migration is forward-only. No new dependency or build change is approved by these documents.

The existing long-term auth key must not be casually reused or rotated as an unversioned deletion-evidence key. Explain domain separation, retained verification versions, rollover and failure on missing required keys. All real secret values stay outside Git and require explicit configuration approval; deterministic tests use synthetic keys. The precise key-ring format and deployment values remain pre-edit design decisions, not unspecified product behavior.

## Team, verification and reporting protocol

- **Worker:** owns production and `docs/task-9[a|b|c]-worker.md` for the authorized slice. Start with inspection/design only. No tests, shared-docs or Coordinator reports; ask before schema/configuration/build changes. Use cohesive services and explicit outcomes rather than growing AuthService or the mutation engine into a multi-mode coordinator.
- **QA:** owns tests and its slice report. Run the exclusive starting baseline before test edits or inspecting new implementation, then record a requirement-first plan with independent expected data. Do not use production mapping or observed responses as the oracle. Preserve existing assertions.
- **Coordinator:** owns scope, contracts, assignment, integration/findings, structural review, acceptance and handoff, not production or test authoring. Review live locks, irreversible boundaries, privacy, readable structure and all fixed criteria independently of green counts.

The user has replaced the historical Luna policy with **Muse Spark 1.3 Contributor Medium** in Pi. A fresh pair is running as `backend_worker` at `w4:pD` and `backend_qa` at `w4:pE`, using `meta/muse-spark-1.3-contributor` and `medium` reasoning, idle awaiting assignment. Startup was explicitly authorized, but no Task 9 slice or baseline run was authorized. Obtain separate permission for future per-slice replacement; Task 8's fresh-pair permission is not continuing replacement authority. Existing idle agent panes are not free shells. Preserve layout, working directories, focus and unrelated services; rediscover live IDs before control. Do not manage context or compaction.

Prefer mcpls for Java symbols, definitions, references and hover. Report actual initialization/tool errors and use read-only fallback without changing tooling. Use `rg` for broad search and `read` for contents. The Coordinator's planning symbol query again reported Java initialization in progress.

Every Maven operation uses `flock /tmp/gt-backend-task7-build.lock ./mvnw ...`. Locking is not an input freeze. After a stable handoff, the Coordinator may open an exclusive QA correction/retry window: freeze relevant inputs during each run, preserve exact commands/logs/exits/fresh reports and identical before/after manifests, close that run, then correct QA-owned compilation/fixture mistakes between runs. Never weaken requirements, edit production or change a frozen input while Maven runs. Batch reports when green or genuinely blocked; production or contract findings go through the Coordinator. Final target/full and Coordinator evidence must use identical corrected inputs, including any strengthened test source. Actual process-restart evidence stops the process that created the state before recovering it in another process.

Each slice ends with Worker self-check, independent dedicated/integrated/full QA, and Coordinator structural review and real HTTP/PostgreSQL spots. Preserve copied reports before another run overwrites target. Distinguish direct route evidence, controlled DB fixtures, external Google mocks and shared regression supplements. Record limits honestly; no line quota or mandatory matrix format. Write proper grammatical English with normal spacing.

## Protected resources and excluded work

Use isolated Testcontainers PostgreSQL 16 and real HTTP. Never access the preserved local `gymtracka` database or read/source/print/copy/rotate `~/.config/gymtracka-backend/dev.env` or secrets. Stop only test-created processes and remove only test-owned faults. No staging/commit, deployment, shared edits/submodule conversion, unrelated shutdown or applied-migration edit without new explicit approval.

Android cache clearing, real release OAuth/HTTPS setup, system-wide logging/rate limits, backup replication/scheduling and actual disaster restore remain separate work. Task 9 must leave durable minimal evidence usable by future restore tooling, but does not itself establish safe deployed restore. The next Coordinator's resume checklist is in `docs/task-9-coordinator-handoff.md`.
