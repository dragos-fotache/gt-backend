# Task 7b — browsing and shared pagination assignment

**Completed/accepted.** Final integrated task-7 checkpoint: `docs/task-7b-acceptance.md`. This records the historical
assignment, not permission for Worker/QA to continue or start later tasks; both await a new assignment.

## Authorization and accepted baseline

The user explicitly authorized 7b after finishing 7a, with fresh Luna Worker/QA briefed on team roles. **7a is accepted**
(`docs/task-7a-acceptance.md`): final independent QA 127 targeted / 148 full tests, five Coordinator HTTP/PostgreSQL
spots and the mandatory readability gate, zero failures/errors/skips. This is not complete task 7 or deployment.

At this boundary old idle agents exited through native `/quit`; their shell cwd was checked and fresh gpt-6-luna High
processes started in the same panes. Both acknowledged role/context onboarding. Current workspace `w4`, tab `w4:t1`:
Coordinator `w4:p7`; Worker `backend_worker` / `w4:pD`, session `gt-backend-worker-task7b`; QA `backend_qa` / `w4:pE`,
session `gt-backend-qa-task7b`. Preserve layout/cwd/focus. Replacement permission is exercised, not ongoing.

Starting baseline: `/tmp/gt-backend-task7b-baseline-w7uYPj`; captured inputs manifest SHA-256
`ccb07dd557579fe0c4f238f9cbebf28745ffe5308b4b50d5e6a806d2eee61bb7`. It includes accepted source/tests/resources,
local docs/README/build inputs and supplied shared contract/catalogue copies. The just-completed 7a verification is
fresh production/test baseline evidence; no redundant baseline full-suite run is required before initial design.
Backend files are untracked: use baseline-relative comparisons/hashes, not an empty git diff. Shared-docs remains an
independent, pre-existing dirty checkout at HEAD `3aefeb3e2eaa147793d595833217d6070eeabff2`; never reset/edit it.

## Authority and fixed criteria

**Read `docs/task-7-contract.md` completely; its common representation/auth rules, 7b query/token specification and
AC7b.1–5 are the fixed acceptance contract.** This execution assignment supplements organization and technical choices,
not product behavior. Read root README (including mcpls preference), the 7a acceptance/worker/QA reports as relevant,
and the current authoritative `shared-docs/docs/backend-contract/{README.md,openapi.yaml,service-protocol.md,
domain-rules.md,client-compatibility.md,android-client-protocol.md,authentication.md,acceptance-scenarios.md}` plus
`shared-docs/catalogue/README.md` completely. Resolve contradictions explicitly; do not need Android source.

Only these three new routes are assigned:

| Route | Items/filter scope |
|---|---|
| GET `/v1/catalogue/exercises` | Visible bundled/live owned Exercise; q, repeated muscleGroupId/equipmentId; catalogueVersion |
| GET `/v1/workouts` | Live owned WorkoutSummary; optional exact active/finished status |
| GET `/v1/routines` | Live owned saved non-draft complete Routine; no domain filter |

One shared signed stateless keyset mechanism: limits 1–200/default 100; account/endpoint/normalized filters/effective
limit/last sort key/fixed first-page +15-minute expiry binding. Continuations preserve expiry. Authenticated invalid,
tampered, foreign or mismatched tokens return 400 invalid_page_token; authentic matching expired tokens return 410
page_expired at now >= expiresAt. Verify structure/signature/context before expiry. Token alone never authorizes;
live account/session authorization applies on every page and another live same-account session may continue.

Workout order startedAt DESC then canonical UUID DESC; exercise/routine UUID ASC, with matching strict after-key
predicates and native UUID ordering (not Java signed UUID.compareTo). items/hasMore/nullable continuation are accurate
using an extra MATCHING record; no token on terminal pages. Complete aggregates and internally consistent page reads,
but live membership/content across requests, not frozen sessions/copies, sync bootstrap, deletion signal or export.
No domain/revision/reservation/replay/cursor/credential-lifetime effects from reads. Preserve original stored labels;
fresh canonical lists resolve current labels. No new product caps, partial roots or invented reference endpoints.

Search is literal case-insensitive substring across exercise, muscle and equipment names. Normalize binding and matching
with locale-independent Unicode lowercase, preserving whitespace; absent and empty q are equivalent. Do not assume
PostgreSQL/default JVM casing matches this for all Unicode (for example dotted-I/contextual sigma). Demonstrate the
chosen approach. SQL filter categories OR within/AND across; canonical UUID sets sorted/deduplicated. Unknown valid IDs
match nothing. Follow the already-fixed scalar/repeated/query/error choices in the shared task-7 contract.

## Reviewed technical choices and first checkpoint

- Reuse accepted canonical mapping and live read authorization/consistent transaction boundaries where cohesive.
  Keep endpoint query/normalization, token validation/signing and response assembly separately understandable.
  Add only bounded read/pagination primitives; do not grow the mutation god method or introduce a handler framework.
- Use established HMAC-SHA256 with constant-time signature comparison and purpose separation. **Approved stable key
  configuration:** derive a list-page signing key from the existing configured AuthCrypto HMAC facility using a fixed,
  versioned list-page-specific label, then use that key for page signatures. The existing auth master configuration is
  already required/validated and stable across restart. No generated per-process default, no printing/copying keys,
  no auth key rotation, and no secret-file access or new deployment dependency. Equivalent clearly domain-separated
  use may be proposed at the first checkpoint, but do not reuse an untagged credential fingerprint as a page signature.
- Tokens may use a small versioned payload containing signed anchor/expiry/context binding; opaque means clients do not
  interpret it, not a requirement for payload encryption. Bind normalized context unambiguously (including filters
  directly or a cryptographic binding). Do not add a filter/name/token semantic size cap to simplify encoding.
- Provide a narrow testable pagination service Clock boundary for exact expiry tests; default is service UTC. Do not
  rewrite auth clocks or base expiry on business startedAt/device time. Preserve one first-page expiry through every
  token; no sliding extension. Fixed signing configuration must allow a representative real JVM restart continuation.
- No new migration/build/dependency/application configuration is expected. Ask Coordinator before any such change.
  Applied V1–V6, accepted auth/write/replay algorithms and source/test semantics remain protected.

**Initial production freeze:** Worker may inspect/design and write `docs/task-7b-worker.md`, but must not edit
production yet. Propose a concise cohesive approach covering shared signing/context/Clock, query normalization,
Unicode matching, keyset predicates, consistent page construction and planned files. Coordinator reviews that approach
before releasing production implementation. QA may simultaneously derive requirements and author tests in its owned
files; no Maven yet. Do not wait for every trivial implementation edit once the approach is approved.

## Ownership, verification and acceptance

- Worker owns `src/main/**` and `docs/task-7b-worker.md`, not tests/fixtures/status/build/shared-docs. No production
  edits until the first approach is approved. Later stable handoff includes exact files/decisions/commands, hashes,
  blockers and unexecuted checks; neither Worker nor QA claims final acceptance.
- QA owns `src/test/**` and `docs/task-7b-qa.md`, not production/migrations/status/build/shared-docs. Start with a concise
  requirement-first coverage plan before inspecting new implementation; readable prose/table is optional, no line quota.
  Prefer dedicated cohesive browsing/token HTTP tests and helpers, not enlarging legacy suites or duplicating giant
  harnesses. Fixtures must be domain-valid, business timestamps millisecond-compatible and expected values independently
  derived. Preserve old assertions; production findings go through Coordinator, never silently adapt the oracle.
- Coordinator owns contract/integration/findings/structural review/acceptance/status docs, not production/test authoring.
  Mandatory clean/readable-code gate: small cohesive methods, explicit results, meaningful names, practical separation,
  local duplication removed, no mode/nullable coordination or speculative frameworks. QA also reports structural concerns.

All Maven uses `flock /tmp/gt-backend-task7-build.lock ./mvnw ...` in a Coordinator-granted slot, with relevant source,
tests, resources, build and normative shared-docs/seed frozen. A production-only package may exclude test compilation
while QA authors; behavioral/test-compile slots freeze both roles. No Maven is authorized by this initial assignment.
Capture before/after manifests with stable path membership; exclude backend coordination docs deliberately, not changing
runtime inputs after a failure. Keep normative shared-docs in the verification set. Remove only stale test-generated
Surefire reports before an exclusive run; copy fresh reports before the next run. Report actual counts, errors/skips,
direct endpoint evidence versus supplements/gaps, and exact fault/interleaving/expiry/restart evidence.

Finish with Worker self-check, independent frozen QA targeted checks and full regression integrating **both 7a/7b** and
accepted direct/sync/auth surfaces, Coordinator baseline-relative structural/scope review and real HTTP/PostgreSQL spots.
AC7b.1–5 include filters/Unicode/OR-AND, order/ties/signed-bit UUIDs, page boundaries, normalized token equivalence,
tampering/account/endpoint/limit isolation, fixed expiry including exact boundary, cross-session/restart, concurrent live
membership and disabled/logout behavior, safe errors/no effects, one shared primitive, and final integrated verification.
Record representative gaps without weakening required behavior. Only this integrated checkpoint can complete task 7.

## Safety and exclusions

Use real HTTP and isolated Testcontainers PostgreSQL 16; mock external Google only, not domain/service behavior.
Never read/source/print/copy/rotate `~/.config/gymtracka-backend/dev.env` or secrets. No local gymtracka connection,
migration/reset/destructive test; no staging/commit/deployment, shared-docs edits/submodule conversion, existing service
shutdown, pane/agent reset or layout change. Stop only test-created processes and clean only test-created faults.
Existing post-container auth cleanup/Hikari noise is recorded, not permission for unrelated cleanup. Tasks 8–10,
new mutations/child/import/reference endpoints, account lifecycle and broad legacy refactoring remain out of scope.
