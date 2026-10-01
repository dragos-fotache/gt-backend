# Task 7 — resource reads and browsing contract

## Authorization and starting state

The user authorized **subtask 7a** after approving the two-slice plan and explicitly making clean, readable code an
acceptance criterion. Both contracts were defined upfront. The user subsequently authorized 7b after 7a acceptance
with fresh role-briefed Luna Worker/QA; that sequence was completed. **Both 7a and 7b/integrated task 7 are now
accepted** (`docs/task-7a-acceptance.md`,
`docs/task-7b-acceptance.md`). This contract and execution assignments are historical, not continuing permission.
Fresh Luna actors completed 7b and now await a new assignment. Tasks 8–10 and the dedicated legacy refactoring task
remain out of scope.

Normative authority: `shared-docs/docs/backend-contract/README.md`, `openapi.yaml`, `service-protocol.md`,
`domain-rules.md`, `client-compatibility.md`, `android-client-protocol.md`, `authentication.md` and
`acceptance-scenarios.md`. Read these completely before work; local operational choices below do not override them.
Report contradictions to Coordinator before implementing an interpretation. No Android source is required.
Read root `README.md` as well. Its code-navigation preference applies to both roles: prefer **mcpls MCP tools** for
Java symbols, definitions, references and hover; jdtls is their underlying Java language-server process, NOT a standalone
tool namespace. Discover `mcpls_get_document_symbols`, `mcpls_get_definition`, `mcpls_get_references`,
`mcpls_get_hover` or search `symbol` on server `mcpls`. A failed lexical search for `jdtls` is not a server outage.
Use `rg` for broad text searches and `read` for file contents. If actual mcpls calls are unavailable, report their errors
and use a read-only fallback rather than changing tooling/configuration or blocking unrelated work.

Task 6 is accepted historically; its 137-test result is not fresh evidence for the current inputs. Several current
source/test hashes differ from the task-6 checkpoint, and the independent shared-docs checkout now has pre-existing
working-tree changes at HEAD `3aefeb3e2eaa147793d595833217d6070eeabff2`. OpenAPI changes against HEAD are whitespace-only;
prose includes wrapping/table formatting. Preserve ALL current shared-docs changes, do not reset or edit them, and use
the current supplied normative files. Do not assume the old clean-checkout or exact-hash statements still hold.

A new baseline was captured before task-7 edits: `/tmp/gt-backend-task7a-baseline-903cic`, containing src/docs/build
inputs and supplied contract/catalogue copies. Its initial inputs manifest SHA-256 is
`fd0296bb0acbca939658d9dbe71b41296aa37f6bb5d689c32754dd944b633d72`. Backend files remain untracked; an empty git
diff is not evidence. Compare to this baseline and use hashes. QA first runs the untouched baseline full suite while
Worker inspects/designs without editing production. Coordinator releases the production freeze after that check.

Current team in workspace `w4`, tab `w4:t1`: Coordinator `w4:p7`; Worker `backend_worker` / `w4:pD`; QA
`backend_qa` / `w4:pE`. Cwd `/home/dragos/IdeaProjects/gt-backend`, Java 27, Spring Boot 4.1.1, Maven wrapper,
PostgreSQL/Flyway. Preserve panes, cwd and user focus.

## Common canonical representation, authorization and consistency

- Every route requires a current service bearer credential, including reads of bundled exercises. Derive owner only
  from verified authentication. Missing, malformed, invalid, expired or revoked access, or disabled account, returns
  `401 authentication_required`. New reads after accepted logout/revocation/disablement reject; this is not retroactive
  erasure of bytes already delivered. Another live session of the same account continues to work after session logout.
- Canonical responses are plain Exercise, Workout or Routine objects, or the specified list envelopes, never sync
  results/batches. Preserve root/child IDs, positive owned-root revisions, UTC millisecond-compatible timestamps,
  ordered zero-based children, numeric/null/zero distinctions, instruction order and null versus empty instructions.
  No owner IDs, credentials, SQL, reservations, replay internals or sync cursors are exposed.
- Exercise relationships are ordered by muscle role primary/secondary/tertiary then canonical UUID ascending;
  equipment by UUID ascending. Include catalogue relationship names and nullable quantities. Bundled exercises retain
  their actual type/metadata/tertiary relationships, `isUserCreated=false`, and **omit revision**. Live owned customs
  have `type=reps`, `isUserCreated=true`, and a positive revision.
- Workouts include active and finished state; routines include drafts, saved routines, and legitimate temporarily
  blank/empty saved autosaves. Read eligibility must not reuse command save/start/finish eligibility restrictions.
  Empty drafts, empty active workouts and entries with no sets remain representable where domain-valid.
- `exerciseName` is derived from current visible exercise data in the **same consistent read view** as the root,
  children and relationships. Renames change labels on fresh active/finished/draft/saved reads without changing parent
  domain fields or revisions. Stored mutation batches and replay responses retain their original labels and bytes.
- A single aggregate must not mix root/revision, children, sets or exercise labels from different committed views.
  Use an actual Spring-managed consistent multi-query transaction or equivalent single-query snapshot, not a collection
  of independently committed repository calls. Read authorization must check live state; do not accidentally call
  the existing `FOR UPDATE` write authorizer inside a PostgreSQL read-only transaction or weaken write fencing.
  In-flight reads may represent a committed pre-write view; no cross-request frozen view is promised.
- GETs do not alter domain rows, revisions/timestamps, reservations, active selection, account cursor, sync publication,
  replay evidence or credential expiry. No idempotency header is required; a supplied key does not create/replay an
  operation. No new schema or stored read/list sessions are expected.
- Errors use safe `{errorCode,message?}`. Shared transient SQL/service failures map to `503 temporarily_unavailable`,
  unexpected failures to `500 internal_error`, not a false 404 or durable domain outcome. Preserve accepted behavior.
- `totalVolumeKg` is optional derived convenience data, not authoritative persisted state. 7a need not add it to the
  existing representation. If emitted, compute the contractual sum from the same read view, avoid nonfinite output,
  and do not change accepted replay bytes or introduce product caps.

## 7a — authorized route scope

| Route | Success and visibility |
|---|---|
| GET `/v1/exercises/{exerciseId}` | 200 complete bundled or caller-owned live custom Exercise, including relationships |
| GET `/v1/workouts/{workoutId}` | 200 complete caller-owned live active or finished Workout |
| GET `/v1/routines/{routineId}` | 200 complete caller-owned live draft or saved Routine |

Missing, tombstoned or foreign private roots return the same `404 not_found`, with no disclosure of ownership or
existence. A bundled exercise GET succeeds, not `read_only`. That code applies to mutations only.

Coordinator operational validation choices, consistent with existing UUID/error conventions: noncanonical or malformed
path UUID returns `422 validation_error`; individual routes have no supported query parameters, so reject nonempty
query syntax with `400 invalid_request`. Supported GET body is absent/zero bytes; reject a nonempty body with
`422 validation_error` rather than parsing it as a command. Do not invent precedence for requests with several
independent invalidities. A valid optional Idempotency-Key header has no read semantics.

Do not expose list/search/reference/child/import/sync/account routes during 7a. A focused canonical mapping extraction
from the mutation publisher is allowed when it removes duplication and preserves behavior; do not enlarge or broadly
refactor `RoutineUpsertExecutor.applyCommand`. Keep read orchestration separate from mutation execution and replay.

### Fixed 7a acceptance criteria

**AC7a.1 Routes and complete canonical data.** Independent real HTTP/isolated PostgreSQL positives for all three
routes: supplied pinned bundled data, rich custom metadata/relationships including deterministic ordering, active and
finished workouts with full ordered entries/sets, draft and saved routines (including empty/blank valid states).
Assert canonical field values independently, not by comparing two production mappers. Cover null/zero, instruction
null/empty/order, timestamps, repeated exercise references and distinct entry identities, completion/recorded versus
configured rest, and absent bundle revision. No ownership/internal leakage, truncation or command eligibility applied
to reads. Separate pinned-seed evidence from synthetic fixture evidence. The actual pinned seed has no tertiary muscle
relationships (899 exercises; 928 primary and 1710 secondary relationships), so no pinned tertiary example can be required
or fabricated. Preserve all actual seed values/roles, retain contractual tertiary representation/order support, and
explicitly distinguish its structural/supplementary evidence from the unavailable pinned-seed HTTP permutation.

**AC7a.2 Visibility and live authorization.** All three routes have missing, deleted and foreign private coverage with
404 `not_found` and safe bodies. Bundled GET succeeds for both accounts only when authenticated. Verify missing/invalid/
expired access and accepted logout rejection; account disablement rejects ordinary reads without implementing task 9.
Verify another live same-account session remains usable. Proportionate route-specific assertions plus explicitly
identified common-auth supplements are acceptable; report the distinction and gaps.

**AC7a.3 Fresh labels and no side effects.** Rename a custom referenced in an active workout, finished history, draft
and saved routine. Fresh reads show the new name; parent revisions and domain values do not change. Preserve original
stored mutation/replay labels. Assert successful, repeated, missing/foreign, malformed and unauthorized GETs cause no
root/revision/reservation/cursor/batch/replay changes and no credential lifetime extension. Optional Idempotency-Key
must not claim or reuse evidence.

**AC7a.4 Consistency under writes.** Controlled real PostgreSQL/HTTP concurrency demonstrates a multi-query read
cannot mix a root/revision with children/sets from a later whole-root replacement or finish/delete, and cannot mix
exercise metadata with relationships from another revision. Demonstrate name projection is in the same read view
with relevant concurrent rename/replacement. Accept a complete committed before or after view, not hybrids.
Use deterministic gates/interception or bounded DB coordination rather than timing sleeps or a service simulator.
Review transaction proxy participation and isolation explicitly. Cover families proportionately and disclose actual
interleavings; shared repository tests are supplements, not direct-route concurrency evidence.

**AC7a.5 Validation and failure safety.** Path/query/body policies above return typed errors without side effects.
Representative test-owned transient/integrity failure in the new read path returns safe 503/500, not 404, and succeeds
unchanged after removing the fault. No durable rejection/replay record, SQL/stack trace/private data in error output.
No permanent new size/shape/product caps and no broad global error-handler cleanup.

**AC7a.6 Readability and maintainability — mandatory acceptance gate.** Coordinator structurally reviews every changed
production path. Require small cohesive methods, meaningful names, explicit results where useful, practical separation
of HTTP validation, read orchestration/auth, persistence and canonical mapping, and removal of obvious local duplication.
Share warranted canonical mapping without mode flags, nullable coordination, deep nesting, speculative handler frameworks
or controller SQL. Keep new paths independently understandable. Do not grow the legacy command god method or mix read
queries into replay/publication. Return reasonably local deterioration for cleanup, then repeat frozen-state checks.
A green suite does not waive this gate. Larger unrelated refactoring remains separately recorded debt.

**AC7a.7 Independent frozen-state verification and scope.** Worker provides a stable handoff and build/relevant
regression self-check. QA independently runs the new HTTP/read target plus direct mutation/sync/preflight/auth
regression, then full `./mvnw -q test`; records exact commands, fresh XML totals, failures/errors/skips, input manifests
before/after, copied reports, route/criterion evidence and gaps. Coordinator reviews baseline-relative scope,
canonical/auth/consistency structure, fresh results and risk-based existing-test spots. Freeze production AND tests
for behavioral verification and serialize every Maven operation. Preserve migrations V1–V6, supplied shared-docs,
build inputs, accepted APIs/tests, local DB/data and secrets. No task-7 completion or deployment claim from 7a.

## 7b — defined upfront, conditional start approved after 7a acceptance

| Route | Response/filter scope |
|---|---|
| GET `/v1/catalogue/exercises` | Exercise items from bundled plus live owned custom, q/muscleGroupId/equipmentId, catalogueVersion |
| GET `/v1/workouts` | WorkoutSummary items, optional exact enum status active/finished |
| GET `/v1/routines` | Full saved non-draft Routine items, no domain filter |

Common representation/auth/no-effect rules apply. Search is case-insensitive across exercise name, muscle names and
equipment names. IDs within one filter category are ORed; muscle and equipment categories are ANDed; q combines with
these filters. Use locale-independent Unicode lowercase q for binding and matching, sorted/deduplicated canonical UUID
filter sets. Do not use locale-dependent JVM/default database case behavior without verifying equivalence.

Coordinator operational query choices for 7b: q is a literal substring, not a SQL pattern/regex; escape SQL `%`, `_`
and escape characters as needed. Preserve whitespace (no silent trim); absent q and empty q normalize identically as
no search restriction. Repeated `muscleGroupId`/`equipmentId` use OpenAPI form/explode (one UUID per value, no comma
syntax); no values means no filter. Unknown well-formed filter IDs match nothing rather than disclose/create catalogue
references. Scalars q/status/limit/pageToken appear at most once. Reject unknown parameters, malformed array UUIDs,
invalid status or invalid/out-of-range/noninteger limit with `400 invalid_request` (unsupported query syntax);
an empty, malformed or mismatched supplied token uses the more specific `400 invalid_page_token`. Do not impose
cross-invalidity precedence beyond token validation below.

- One shared signed stateless keyset mechanism for all three routes, not independent cursor implementations, stored
  result copies or list-session tables. Choose a bounded comprehensible versioned payload and established keyed signing
  primitives; don't invent cryptography or expose signing secrets. Keep normalization, token validation, endpoint SQL
  and response construction cohesive and separately understandable. Signing/configuration choices need review before 7b.
- Token binds authenticated account, concrete endpoint, normalized filters, effective limit, last sort key, fixed expiry.
  It is **not session-bound**; another live session of the same account can continue with the same context. Token alone
  cannot authorize, and disabled/revoked access cannot continue. A properly configured service restart should not
  silently invalidate otherwise valid tokens; no generated per-process signing secret as a production default.
- Limits 1–200, default 100; omitted default and explicit 100 have the same effective context. Starting without a token
  establishes expiry exactly 15 minutes after the first page using service UTC time; every continuation preserves it.
  Expired at `now >= expiresAt`. Verify signature/structure and account/endpoint/filter/limit context before returning
  expiry: invalid/tampered/foreign/mismatched token is 400 `invalid_page_token`; authentic, matching expired token is
  410 `page_expired`. Neither silently starts a new list.
- Workout order startedAt DESC then canonical UUID DESC; routine/exercise order canonical UUID ASC. Page predicates are
  strict after-last-key comparisons matching the same order, not offset paging or Java signed-long UUID ordering.
  Read the current live view for each page; contents/membership can change between requests. Keep aggregates complete
  and internally consistent within a page, without promising one frozen view for the full traversal.
- Response has items, hasMore, and nullable nextPageToken. Nonempty token iff hasMore; terminal token is null or omitted
  as permitted by OpenAPI. Determine continuation from an extra matching record, not a full-page heuristic. Empty and
  exact-boundary pages terminate correctly. CatalogueVersion is the supplied fixed `gymtracka-catalogue-v1`.
- Lists are browsing only, not a sync bootstrap, absence/deletion signal, snapshot or repeatable export.

### Fixed 7b acceptance criteria (not a present assignment)

**AC7b.1 Query semantics and canonical data.** Independent HTTP/PostgreSQL coverage of bundled/custom visibility,
search across every name source with Unicode/case/literal SQL metacharacters, OR-within/AND-across filters,
sorted/deduplicated filter equivalence, status filters, exclusion of routine drafts, catalogue version, and correct
complete Exercise/WorkoutSummary/Routine wire shapes. Missing/foreign/deleted members are never included.

**AC7b.2 Keyset order and boundaries.** Ascending UUID and descending workout timestamp/UUID ties, UUIDs across
signed-bit boundaries, empty lists, limit 1/default/200, malformed/out-of-range limits, exact and partial terminal
pages, more-than-one-page traversal without writes and no skipped/duplicated IDs. Correct hasMore/terminal token;
never split or truncate a routine aggregate. No offsets/frozen list sessions.

**AC7b.3 Token integrity, isolation and normalization.** Tampered/malformed/wrong-account/wrong-endpoint/changed
filters/limit reject 400 invalid_page_token without disclosure or silent restart. Equivalent normalized q/UUID sets
and omitted/default limit continue. Cross-session same-account continuation succeeds with live authorization.
Correct context/signature checking before expiry and safe signing configuration/restart behavior.

**AC7b.4 Fixed expiry and live membership.** Controlled service clock proves first-page +15 minutes, no sliding
extension, exact-boundary expiry, 410 only for authentic matching expired token, and new tokenless start after expiry.
Concurrent create/update/delete/rename/draft-save/status changes show live later pages with strict keyset progress, not
frozen sessions. Disabled accounts and logged-out access cannot continue with a still-valid token. No GET effects.

**AC7b.5 Structure and integrated verification.** Mandatory clean/readable-code gate as AC7a.6, including one shared
pagination primitive and separate comprehensible endpoint queries. Worker self-check, independent frozen QA target and
full regression including BOTH read slices and accepted mutation/auth surfaces, Coordinator review/spots, exact fresh
reports/manifests/coverage limits and baseline-relative scope. Task 7 completes only after this integrated acceptance.

## Assigned work and safety

- Worker owns `src/main/**` and `docs/task-7a-worker.md`. Read existing auth, canonical repositories/types and mutation
  mapping; propose a concise focused approach and transaction/mapping boundaries before editing. New read classes,
  visible bundled/custom row mapping and bounded extraction of shared canonical mapping are allowed. No new migration
  is expected; ask Coordinator before any schema/build/dependency/configuration change. Do not edit tests, QA/status
  docs, shared-docs or build inputs. Do not change existing mutation algorithms/replay/auth write fencing.
- QA owns `src/test/**` and `docs/task-7a-qa.md`. Define requirement-first route/AC coverage before examining the new
  implementation; use whatever concise readable format makes evidence and gaps clear. No fixed line count or mandatory
  matrix format applies. Prefer dedicated read HTTP tests and cohesive fixture/concurrency helpers, not another huge block
  in the legacy sync test. Preserve existing assertions; findings and needed seams go through Coordinator. Do not edit
  production, migrations, shared-docs, build or status docs. QA does not claim final acceptance.
- Coordinator owns contract/plan/checkpoint/README/acceptance docs, assignments/integration/findings and structural
  sign-off, not production or test authoring. Use concise grammatical handoffs with exact paths, commands/results,
  stable/frozen state, open findings and unexecuted coverage. The user explicitly relaxed inherited formatting constraints:
  no report line quota or required matrix layout. Both roles may inspect/read and use /tmp for reports.

Every Maven operation uses `flock /tmp/gt-backend-task7-build.lock ./mvnw ...`; do not overlap another task's build or
reuse mutable target reports as historical evidence. Locking alone does not freeze inputs. Initial baseline full-suite
slot is QA only; Worker production remains frozen, and QA must not edit tests until that slot ends. Thereafter production
and test authoring may proceed concurrently under disjoint ownership. Any build-only slot can freeze production while
QA authors tests using `-Dmaven.test.skip=true`; behavioral/test-compile slots require both roles frozen and Coordinator
agreement. Copy fresh reports before the next run overwrites target. Notify Coordinator when ready/blocked; don't send
new work assignments to a working agent or independently negotiate acceptance/scope. Coordinator may queue a brief
user-requested clarification without interrupting active work; do not confuse submission with acknowledgement.

Use real HTTP and isolated Testcontainers PostgreSQL 16; only external Google behavior may be mocked as in existing
tests. Existing post-container auth-cleanup/Hikari noise is a recorded lifecycle issue, not authorization for unrelated
cleanup. No local gymtracka connection/migration/reset, no secret-file access (never read/source/print/copy/rotate
`~/.config/gymtracka-backend/dev.env`), no commits/staging/deployment, no shared-docs edits/submodule conversion,
service shutdown, agent replacement/termination, or layout changes. Terminate only your test-created processes and
remove only your test-created faults. Current 7a agents must not start 7b. Coordinator will finish 7a acceptance, open
fresh Luna Worker/QA contexts and assign 7b under the user's conditional authorization. Later tasks remain unauthorized.
