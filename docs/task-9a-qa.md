# Task 9a QA — frozen baseline and requirement-first plan

## 0. Amendment (Coordinator corrections, baseline slot closed)

- Baseline APPROVED as independently verified by the Coordinator: exit 0,
  18 suites / 208 tests with zero failures/errors/skips, timestamps and all
  98 input checks before/after/current matching
  `5419d57da52ae5b76b8431c4f94b4efc91272c407352e190976b56953b8f2bbf`.
  The baseline slot is CLOSED; no further Maven is authorized until a
  separate authoring-release message.
- This plan is amended per the binding Coordinator corrections below and is
  sound subject to them. Only this file (`docs/task-9a-qa.md`) is edited in
  this pass. No test source, production source, build, migration, or other
  docs edits; no Maven run; no new-implementation inspection.
- Oracle correction: expected domain export records come from hand-authored
  intended request values and pinned catalogue data, NEVER copied from
  accepted-API setup responses, GET responses, the production mapper, or any
  observed result. Setup/GET responses may supply only genuinely
  service-generated IDs/times needed for ownership/envelope constraints, not
  expected domain fields. The boundary cases below are requirement-derived
  tests, not a request to use Worker implementation as an oracle.
- Ownership correction: `docs/task-9a-review.md` is Coordinator-owned, not a
  Worker-side file.

Scope: slice 9a ONLY under `docs/task-9a-contract.md` and shared rules in
`docs/task-9-contract.md`. QA owns tests and this report; no production edits.
No test source has been authored or edited. No new Worker production has been
inspected. No acceptance is claimed. See section 0 for the Coordinator-approved
baseline and binding plan corrections applied here.

## 1. Frozen baseline run (exclusive QA slot, closed and approved)

- Exact command: `flock /tmp/gt-backend-task7-build.lock ./mvnw -q clean test`
- Start (UTC): `2026-10-01T10:29:42Z`; end (UTC): `2026-10-01T10:36:55Z`.
- Exit code: `0` (`/tmp/gt-backend-task9a-qa-baseline-9a01/exit-code.txt`).
- Full log: `/tmp/gt-backend-task9a-qa-baseline-9a01/mvn-clean-test.log`
  (254059 bytes; `clean` removed only test-owned build output in `target/`).
- Fresh Surefire XML copied before any further run to
  `/tmp/gt-backend-task9a-qa-baseline-9a01/surefire-xml/` (18 `TEST-*.xml`).
- Independent XML sum: **18 suites, 208 tests, 0 failures, 0 errors,
  0 skipped**. Per-suite counts are in `surefire-summary.txt`; this matches
  the historical expectation of 208/18 but was counted fresh, not assumed.
- Known log noise (not a failure): post-Testcontainers Hikari
  `Failed to validate connection ... has been closed` warnings and one
  `Snapshot cleanup invocation failed; uncommitted snapshot work was rolled
  back` warning appear while the suite still exits 0 with the counts above.

## 2. Starting-input verification (before and after the run)

- Coordinator capture: `/tmp/gt-backend-task9a-baseline-3kcl8w19`,
  `inputs.sha256` (98 behavioral paths), `captured-files.sha256`
  (145 curated project-state paths), plus Git/shared status and diffs.
- `sha256sum /tmp/gt-backend-task9a-baseline-3kcl8w19/inputs.sha256` =
  `5419d57da52ae5b76b8431c4f94b4efc91272c407352e190976b56953b8f2bbf`,
  matching the supplied manifest SHA-256.
- `sha256sum -c .../inputs.sha256`: all 98 `OK` before the run
  (`inputs-before.txt`) and all 98 `OK` after the run (`inputs-after.txt`);
  the two check outputs are identical.
- `sha256sum -c docs/shared-docs-inputs.sha256`: all `OK`, zero failures.
- Backend `HEAD 36fe88d8951d17b66a45a537497fcd1b5dacf8d3` and shared
  `HEAD 3aefeb3e2eaa147793d595833217d6070eeabff2` unchanged; the 27
  shared modified paths are unchanged.
- Backend `git status` before the run matched `backend-status.txt`
  (`M README.md`, `M docs/coordinator-handoff.md`, five untracked Task 9
  planning files). After the run two additional untracked files appeared:
  `docs/task-9a-review.md` (Coordinator-owned, not Worker-side) and
  `docs/task-9a-worker.md`. Frozen behavioral inputs (`src/`, `pom.xml`,
  `mvnw`, `.mvn/`, listed shared normative/catalogue inputs) are byte-identical
  per the 98-path check, so the baseline freeze held; the delta is outside the
  frozen set and QA did not read either file while writing this
  requirement-first plan.

## 3. Requirement-first 9a QA plan (no oracle from production code)

Normative basis read completely: `README.md`, `docs/task-9-contract.md`,
`docs/task-9a-contract.md`, and shared `account-lifecycle.md`,
`authentication.md`, `retention.md`, `service-protocol.md`,
`openapi.yaml` (export route plus `AccountGoogleProof`/`AccountExport` and
export shapes), `fixtures/account-export.json` (illustrative only), plus the
challenge `purpose` and pinned `gymtracka-catalogue-v1` checks. Fixtures are
illustrative, never expected-value oracles. Independent expected domain export
records are hand-authored from intended request values and pinned catalogue
data, NEVER copied from accepted-API setup responses, GET responses, the
production mapper, or any observed result; setup/GET responses may supply only
genuinely service-generated IDs/times needed for ownership/envelope
constraints, not expected domain fields. The boundary cases below are
requirement-derived tests, not a request to use Worker implementation as an
oracle.

Environment and safety: isolated Testcontainers PostgreSQL 16 and real HTTP;
mock only external Google verification (keeping real claim/signature
regression coverage where it exists); never touch the local `gymtracka`
database or read/source/print/copy/rotate
`~/.config/gymtracka-backend/dev.env` or real secrets; `V1`–`V7` immutable;
no build/dependency/config/migration edits, commits, deployment, or
agent/context management. Prefer `mcpls` read-only navigation when the plan
moves to execution; report actual tool errors with read-only fallback and no
tooling changes. Every future Maven operation uses
`flock /tmp/gt-backend-task7-build.lock ./mvnw ...` inside a
Coordinator-frozen window, with exact command/log/exit/fresh XML/identical
before/after manifests preserved.

### AC9a.1 — purpose-bound fresh authorization

Two accounts A and B with real challenge issuance. Cases, each asserting
exact HTTP status, shared `errorCode`, `Cache-Control: no-store`, and that
rejected requests leave challenge rows and protected data unchanged:

- `{}` default behaves as `signIn`; purposes `signIn`, `export`,
  `deleteAccount`, `accountStatus` are distinct and non-interchangeable.
- Correct `export` proof for A succeeds; wrong purpose, wrong subject,
  wrong accountId, unknown accountId, and foreign binding fail with
  `401 invalid_google_credential` (proof faults) or `404 not_found`
  (binding faults) per the contract, without consuming a valid challenge.
- Absent bearer plus valid proof succeeds; valid bearer alone without proof
  is denied.
- Exact 300-second boundary: `now >= expiresAt` rejects; just-before-expiry
  accepts (service clock seam, deterministic test clock).
- Deterministic expiry during waits: a challenge that expires while blocked
  in provider/subject/account lock waits is finally rejected and not
  consumed.
- Unknown verified subject never implicitly signs up or provisions an account
  through any lifecycle proof path; export proof for an unknown
  identity/account creates nothing and issues no grant.
- Consumed challenge retry fails `401`; nonce mismatch fails `401`.
- Concurrent same-challenge attempts serialize to exactly one success.
- No lifecycle proof validation creates an account or issues any session
  grant; assert account/grant tables unchanged on every proof-only path.

### AC9a.2 — complete independent export oracle

Hand-author the intended domain content first (custom exercises with named
muscle/equipment relations, empty and populated drafts, saved routines,
blank/empty saved autosaves, active plus finished workouts with notes, rest,
completion flags, nullable and zero values, and UTC timestamps), publish that
intended content through accepted APIs, then build the expected
`AccountExport` from the hand-authored values plus pinned catalogue data
(`catalogueVersion: gymtracka-catalogue-v1`): version 1, all arrays present
even when empty, each array ordered by canonical UUID ascending including
across the signed-UUID boundary. Only genuinely service-generated IDs/times
needed for ownership/envelope constraints may be taken from setup responses;
no expected domain field is copied from a setup response, GET response,
mapper, or observed result. Assert full IDs, references, consecutive
positions, configured/recorded rest, notes, completion, and convenience
exercise labels from the same read view; old unchanged live roots survive
production history pruning; tombstones, catalogue roots, and foreign rows are
excluded. Validate the wire body against the OpenAPI export schemas and
runtime rules.

### AC9a.3 — stream, headers, and data minimization

Real HTTP asserts `200 application/json`, `Cache-Control: no-store`,
`Content-Disposition` attachment `gymtracka-export.json`, and no-store plus
sanitized envelopes on representative errors. Include one large complete
aggregate and multiple roots with no byte or product truncation and no
account/aggregate caps; specifically include a large single root spanning the
cancellation byte boundary. After export, assert no export session/file,
response cache, raw proof/token storage, new revision, change batch, replay
result, or credential extension was created. Recursively assert absence of
revision/mutation/cursor/owner/session/credential internals and workout
fields forbidden by the narrower export schema, not just top-level owner IDs.

### AC9a.4 — admission and failure recovery

Deterministic test-owned gates/faults separate pre-admission rejection from
post-admission read/stream failure. Pre-admission failure includes
streaming-connection and snapshot/read-view establishment failure: the
challenge is not consumed and retry with the same proof remains possible.
Post-admission includes the confirmed consumption commit: the challenge is
durably consumed. An in-doubt consumption commit (outcome uncertain at the
test gate) is not treated as guaranteed rollback and must never restore the
proof to reusable. Failed or cancelled admitted downloads never yield
successful-looking partial JSON or credential-bearing diagnostics: faults with
pending generator bytes and a partial first write must not flush or auto-close
into valid partial JSON, and no error may be appended to salvage a partial
body into a syntactically complete success. A constrained-pool or
current-view fault fails closed and releases connections. Source review plus
fault evidence must verify that actual injected repository reads share the
writer's repeatable-read transaction, and that independent live-cancellation
checks cannot accidentally join that transaction. Durability case: stop the
ADMITTING service process mid-stream and restart against the same isolated
database; the old challenge must remain consumed (no resume, no stored-export
replay) while a fresh challenge can start a new export. A fresh challenge
retry after any admitted failure succeeds independently.

### AC9a.5 — coherent reads and deletion-ready cancellation

Coordinate real writers (renames, child replacements, deletes) during export
and assert every authoritative field and label belongs to one consistent
view. Controlled isolated-database account disablement before first byte
releases zero export data; disablement during serialization/output stops
further release, as does disablement immediately before final closure,
without claiming already-delivered bytes erased. Review transaction
participation and an independent live-cancellation observation path (a frozen
repeatable-read account row alone is insufficient); independent live checks
must not accidentally join the writer transaction (see AC9a.4). Gates must
not impose circular waits through locks they hold. These fixture races are
explicitly marked as needing re-verification through the real deletion
endpoint in 9b/9c, not as deletion acceptance evidence.

### AC9a.6 — maintainability and preservation

Coordinator structural review of cohesive proof, HTTP, mapping, and
streaming boundaries with no controller SQL, no signup side effect, no
nullable lifecycle mode, no auth god-method growth, no speculative
framework, and no mutation refactor. Existing challenge/exchange/refresh/
logout and all accepted mutation/read/pull/pruning/snapshot behavior remain
unchanged except an explicitly reviewed local extraction. New sensitive
paths log neither proof nor exported data.

### AC9a.7 — frozen independent acceptance (later windows)

After Coordinator approves this baseline, the QA plan, and the no-edit
Worker design: dedicated export/proof coverage, integrated accepted
auth/API/sync regressions, full suite, and Coordinator structural review
plus real HTTP/PostgreSQL spots on identical corrected frozen inputs, with
exact commands, fresh XML counts, exits, and manifests preserved.

## 4. Gate status

Baseline is closed, green, and Coordinator-approved on frozen inputs; this
amended requirement-first plan records the binding oracle, ownership, and
boundary-test corrections above. No test authoring, no further Maven run, and
no new-implementation inspection will occur until the Coordinator gives final
Worker-design approval plus a separate authoring-release message. Existing
assertions and supplied working-tree changes are preserved. No acceptance
claimed. QA remains idle awaiting that release.

## 5. Stable test handoff (amended per binding corrections, no Maven run yet)

- Owned test files, `@Test` counts verified by annotation count (earlier handoff
  mistakenly counted class annotations; actual then was 31):
  - `AccountExportProofHttpIntegrationTest.java`, 14 tests (AC9a.1: purposes,
    binding, bearer rules, synthetic-Clock exact 300s boundary at equality and
    just-before, latch-induced provider-wait expiry, lock-wait expiry with latest
    recheck after release, consumed/nonce/concurrent reuse, no-signup, strict shapes).
  - `AccountExportOracleHttpIntegrationTest.java`, 4 tests (AC9a.2: hand-authored
    oracle with pinned catalogue names/IDs, null/zero preservation, ordering,
    tombstone/catalogue/foreign exclusion, real pruning survival).
  - `AccountExportStreamHttpIntegrationTest.java`, 5 tests (AC9a.3: headers,
    40x8 large aggregate, no idempotency namespace, no side effects, connection
    release accounting).
  - `AccountExportAdmissionHttpIntegrationTest.java`, 6 tests (AC9a.4:
    pre-admission no-consume with same-proof retry, disable-before-start with
    re-enable retry, corrected pre-commit trigger failure asserting UNCONSUMED
    plus same-proof success after recovery, deterministic post-consumption gate
    fault with durable consumption, parallel challenges, strengthened
    admitting-process stop/restart with large data, first-byte kill,
    partial-vs-full evidence, exact original-token retry, and outer-finally
    cleanup).
  - `AccountExportCancellationHttpIntegrationTest.java`, 6 tests (AC9a.5:
    committed rename/delete exactness, gate-held snapshot with separate rename,
    child-replacement and delete commits asserting the exact before-view plus
    fresh-after proof, deterministic disable after admission before first byte,
    mid-large-single-root via `beforeByteSegment`, disable before final closure,
    post-consumption fault before first write; every abort asserts no partial
    success with no swallowed paths).
  - `AccountExportPoolHttpIntegrationTest.java`, 1 test (finite pool with bounded
    acquisition terminates fail-closed with drained, reusable pool).
  Total: 36 `@Test` methods. Fresh XML counts will be reported from actual runs,
  never assumed.
- Binding corrections applied: the pre-commit trigger failure now asserts the
  corrected expectation (UNCONSUMED, state unchanged, 500, same proof succeeds
  after recovery) instead of the earlier incorrect never-succeed assertion; expiry
  uses the injected synthetic `Clock` in new services with exact equality and
  latch-deterministic provider/lock waits (no `AuthService` clock changes, no
  wall-clock cutoffs, no sleeps for outcomes); coherence uses the
  `afterSnapshotEstablished` gate with writers as separate commits and asserts the
  exact established before-view; disablement uses deterministic
  `afterConsumptionCommitted`/`beforeFirstByte`/`beforeByteSegment`/
  `beforeFinalClosure` gates; `try/finally` releases every gate, fault, and
  fixture. Gates hold no locks and never wait on an account lock they hold.
- Oracle discipline unchanged: expected domain records stay hand-authored intended
  request values plus pinned catalogue data; setup responses supply only bearers
  and service-generated IDs/times for ownership/envelope constraints. The approved
  hook interface is consumed strictly as deterministic wiring vocabulary, never as
  expected-value source. Tests reference new Worker types only for the hook and
  `Clock` seams, per the approved coordination.
- Exact remaining gap: the truly in-doubt commit instant (uncertain outcome at
  commit) is not fault-reproduced — the trigger path proves certain rollback and
  the gate path proves certain-commit durability, with `ProofInDoubt` 503 and
  never-restore covered by design review plus restart durability. Deletion races
  stay 9b/9c work by contract.
- Handoff request: no Maven slot is open and none was used during authoring, so
  nothing here is compile-verified. Please open an exclusive frozen
  compile/correction/verification window with explicit input freeze; all future
  Maven runs will serialize through `/tmp/gt-backend-task7-build.lock` with exact
  commands, logs, exits, fresh XML, and identical before/after manifests. Existing
  assertions are untouched. No acceptance is claimed.

## 6. Dedicated verification results (exclusive QA window, no Maven outside it)

- Window `/tmp/gt-backend-task9a-qa-window-i49ayj5m`, manifest SHA-256
  `67406ec5a74313baae803bdcbf0a8950447d31eca0337961fe6d2ae9e27af734` (113 paths)
  verified all-OK before the first run; per-run before/after inventories preserved
  under `/tmp/gt-backend-task9a-qa-runs/` (compile-01..09, dedicated-01..06).
  Only QA-owned test files differ from the starting inventory; all production,
  build, shared, and contract paths verify unchanged on every run.
- Compile-only runs (`flock ... ./mvnw -q -DskipTests test`) corrected between
  closed runs, owned mistakes only: wrong `TestConfiguration` package (3 files),
  `{}` structural classification 401 to exact 422, checked-`IOException` catches,
  missing `throws`, `processed_mutations.owner_id`, completed-set nulls.
  Compilation is green; every failed run and manifest is preserved.
- Dedicated runs (`flock ... ./mvnw -q '-Dtest=AccountExport*HttpIntegrationTest'
  test`, stale test-owned Surefire cleared first, fresh XML copied per run):
  final dedicated-06 reports **36 tests, 34 pass, 2 failures, 0 errors,
  0 skipped** (Admission 6/6, Cancellation 4/6, Oracle 4/4, Pool 1/1,
  Proof 14/14, Stream 5/5). Historical @Test count 36 was met exactly by count,
  not used as an expectation.
- The 2 remaining failures are a PRODUCTION/contract finding, not test mistakes:
  post-byte cancellation aborts deliver status 200 with the truncated export
  followed by an appended `{"errorCode":"internal_error"}` envelope
  (dedicated-06 bodies: mid-root 49617 bytes, closure 8832 bytes, both ending in
  the envelope). By code inspection the export's own abort path cannot append
  (guarded `IOException` only); the envelope shape matches only the global
  `AuthErrors.safe` `@ExceptionHandler(Exception.class)` advice, which the async
  streaming error dispatch reaches after commit and appends to the 200 stream.
  This violates the fixed no-append requirement (D9a.3/R3). QA made no production
  edits and did not weaken the assertions; a Worker-side fix (keep the global
  advice off the committed export stream) plus a frozen re-run is required.
- Truly in-doubt commit remains the exact recorded coverage gap (certain-rollback
  trigger path and certain-commit gate path covered; uncertain instant
  design-reviewed). Integrated/full suites and Worker self-check remain unopened.
  No acceptance is claimed; QA awaits Coordinator disposition.

## 7. Window closure and evidence limitation (Q9a.6 correction, no new runs)

- The exclusive QA compile/correction/dedicated window is CLOSED. The final
  dedicated-06 result is FAILING 34/36 (2 failures, 0 errors/skips, exit 1) —
  never blocked-green and never acceptance. All reports, tests, and evidence are
  preserved as-is; no test/source edits or Maven runs will occur while Worker
  owns correction F9a.8 (async committed export aborts reaching generic
  body-writing advice).
- Evidence limitation, recorded truthfully: per-run `inputs-before/after.txt`
  only re-checked the working tree AGAINST THE ORIGINAL 67406ec5 starting
  manifest. From compile-02 onward they list my corrected QA test files as
  FAILED (5 files by dedicated-06) — those OK/FAILED lists are NOT fresh hashes
  of the changed test versions and cannot prove those versions were byte-identical
  during any run. Runs compile-05 through compile-09 additionally lack before/after
  inventory files entirely. No manifests are retroactively invented and no old
  run is claimed fully frozen; compile-01..09 and dedicated-01..06 stand only as
  failure/debug evidence with exact commands, logs, exits, timestamps, and fresh
  Surefire XML where applicable.
- Future corrected runs must generate full actual-current `before.sha256` and
  `after.sha256` from the complete live inventory, `cmp` them, and record their
  SHA-256 values plus input snapshots — not merely checks against a stale start
  manifest. QA will use the Coordinator-provided manifest helper and new freeze
  after the Worker stable fix. Truly in-doubt commit gap unchanged.

## 8. Post-freeze restart-fixture strengthening (authoring only, no run yet)

- Q9a.5 correction accepted: the one-byte read plus sleep proved only client
  restraint, not a mid-stream originator, and buffer-size assumptions are not
  evidence. The synthetic size claim and sleep-based overlap assurance are
  removed, not weakened around.
- New fixture: `src/test/.../account/ExportGateMarkerHooks.java` (test-only,
  `@ConditionalOnProperty("task9a.export-gate.dir")`, `@Primary`, inert in all
  parent contexts and invisible to production packaging) implements the approved
  `AccountExportHooks.afterConsumptionCommitted` gate: it writes a coordination
  marker holding no account/token/challenge/payload data, then blocks pre-first-
  byte while the streaming transaction holds no row locks. The child runs the
  real `GtBackendApplication` with the real service/database/HTTP stack and the
  existing stub-certs Google path; no production test-mode route, runtime config
  change, token/export spool, or implementation-derived oracle.
- The rewritten restart test waits for the exact gate marker, verifies durable
  consumption in the database while the originator is still blocked, destroys the
  admitting child (asserted stopped via the existing exit-code gate), recovers in
  a different plain-production process, retries the EXACT original
  accountId/challengeId/idToken (401, no export data), then succeeds with a fresh
  proof; sessions/credentials counts and the no-stored-export check are retained,
  and processes/stub/marker/gate files close in an outer `finally`.
  Post-consumption pre-first-byte interruption is the asserted boundary; post-byte
  cancellation stays covered by the dedicated gate tests. `@Test` count is
  unchanged at 36; all other tests/assertions and the independent request-value
  oracle are preserved. The gated-child window has now run: see section 9.
  Truly in-doubt commit gap unchanged.

## 9. Final gated-child evidence (post-freeze, Coordinator-verified)

- Gated-child dedicated rerun independently verified by the Coordinator: 36/36,
  zero failures/errors/skips, exit 0, with actual 114-path before == after ==
  live hash `46b4631f1a845b39a2758f72fe5fbfb67cf1e7e8a20e671eee877890861f82d7`.
  The sparse snapshot-mirror (Admission override) plus the base 114 snapshot
  reconstruct all final paths; a new full 114 snapshot was materialized.
- Q9a.5 resolved within bounded evidence: actual child gate, durable consumption
  verified while the originator was blocked, death (forced SIGKILL, exit 137 —
  graceful stop cannot finish while parked in the test gate) before plain-B start,
  exact original-proof retry to 401 with no export data, fresh proof to 200,
  credentials unchanged, no stored export. The 137 is terminologically honest:
  forced termination of a blocked test-owned process, with death-before-recovery
  asserted, never a dropped stop assertion.
- Q9a.6 hashes resolved within bounded evidence via helper-generated actual-
  current manifests with recorded SHA-256 and per-run mirrors. Old compile and
  dedicated runs remain historical failure/debug evidence only.
- Remaining exact gap: the truly in-doubt instantaneous commit outcome is not
  fault-reproduced (certain-rollback trigger and certain-commit gate paths are
  covered; `ProofInDoubt` handling is design-reviewed). Integrated/full QA awaits
  its separate slot after Worker finishes. No acceptance is claimed.

## 10. F9a.9 Google-boundary regressions (authoring only, no run yet)

- Findings accepted as source/dependency-verified: malformed JWT text reaches an
  un-normalized parser throw (must stay 401), and provider certificate-fetch
  `IOException` must surface as lifecycle 503 rather than 401. Worker owns the
  narrow parse-versus-I/O normalization in the existing `GoogleTokenVerifier`
  plus typed 503 handling; `AuthService` stays unchanged.
- Unit strengthening (additive, every existing assertion preserved):
  `auth/GoogleTokenVerifierTest.java` gains one test asserting eight malformed
  shapes (undotted text, two/four parts, malformed header/payload/signature
  encodings, non-JSON header/payload) throw `InvalidGoogleToken` against an
  unroutable certificates URL, i.e. failing in parsing before any provider fetch.
  No network-touching shape is asserted at unit level, avoiding coupling to the
  pending typed provider exception.
- New `account/AccountExportGoogleBoundaryHttpIntegrationTest.java` (3 tests) uses
  the ACTUAL verifier bean over real HTTP against controlled stub certificates
  (keytool PKI, no Mockito on the Google boundary): malformed shapes via export
  assert 401, sanitized no-store envelopes with no credential echo, and no
  consumption; a valid synthetic RS256 proof during a stub 503 asserts 503
  `temporarily_unavailable` with no consumption, then the EXACT same proof
  succeeds to a complete export once certificates recover; a healthy-stub sanity
  test proves the PKI path itself. Expected statuses come from the finalized
  contract, including the pending 503, not from observed output. Unrelated
  `RuntimeException` 500 paths and genuine 401s are untouched; no blanket
  exception mocking is used anywhere.
- `@Test` count moves 36 to 40 (1 unit + 3 integration); all other tests,
  assertions, and the independent oracle are preserved. Truly in-doubt commit gap
  unchanged. No production, build, dependency, config, shared, or Coordinator-doc
  edits.
- Post-amendment verification (combined window `qa-google-boundary-ihxdf5tg`,
  freeze `963fd084`, 115 paths): compile green first try with no corrections;
  combined dedicated exit 0 with fresh XML independently tallied at 8 suites,
  41 tests, 0 failures, 0 errors, 0 skips (the 40 above plus the pre-existing
  second verifier unit test), before == after == starting == live == snapshot.
  F9a.9 fix behaviorally confirmed by the requirement-first regressions. A later
  duplicate opening of the same window required no rerun: the live tree still
  hashed `963fd084` byte-identical to the frozen start.

## 11. Final integrated/full regression (frozen window, no corrections)

- Window `/tmp/gt-backend-task9a-qa-final-zlszs3g5/`, freeze `963fd084c24e2010a95de059738a0a2454fe6501cbeb4ac3072b45a5a59dee34`
  (115 paths). Helper-generated before/after manifests match each other and the
  starting manifest on both runs; no source/test edits, fixture corrections, or
  retries occurred in this window.
- Integrated run (exact approved target list): exit 0, fresh XML independently
  tallied at 22 suites, 227 tests, 0 failures, 0 errors, 0 skips — including all
  seven account suites, the gated-originator restart, malformed-token 401s, and
  provider 503 recovery alongside auth/resource/read/browse/upsert/preflight/pull/
  snapshot/pruning coverage.
- Full run (`./mvnw -q test`): exit 0, fresh XML independently tallied at
  25 suites, 248 tests, 0 failures, 0 errors, 0 skips, covering every prior suite
  plus catalogue and persistence foundations.
- Explicit limits carried forward: truly in-doubt instantaneous commit outcome
  not fault-reproduced; no live Google or deployment/HTTPS guarantee (provider
  edge mocked at stub PKI); controlled-disablement fixtures stand in for future
  actual-deletion races (9b/9c). Coordinator structural/coverage review and
  HTTP/PostgreSQL spots remain after this window. No acceptance is claimed.
