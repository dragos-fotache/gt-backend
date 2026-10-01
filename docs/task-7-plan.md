# Task 7 — approved two-slice plan

## Status and authority

The user approved **7a individual reads**, then **7b browsing/pagination**, with both contracts defined upfront.
7a was accepted, then the user-authorized fresh Luna Worker/QA contexts were role-briefed and completed 7b.
**Both slices and integrated task 7 are accepted.** Contracts: `docs/task-7-contract.md`; execution history:
`docs/task-7b-contract.md`; final acceptance: `docs/task-7a-acceptance.md`, `docs/task-7b-acceptance.md`.

Final integrated QA passed 144 targeted / 165 full tests; Coordinator passed six final 7b HTTP/PostgreSQL spots and
the mandatory structural gate. Worker/QA await a new assignment. Task 8 and later work, deployment and the separately
recorded legacy refactoring task are not authorized by this completed plan.

Authoritative behavior and wire shapes: `shared-docs/docs/backend-contract/README.md`, `openapi.yaml`,
`service-protocol.md`, and linked domain/authentication/client contracts. This plan does not override them.

## 7a — individual resource reads

| Endpoint                         | Scope                                                                         |
|----------------------------------|-------------------------------------------------------------------------------|
| GET `/v1/exercises/{exerciseId}` | Visible bundled or caller-owned live custom exercise, including relationships |
| GET `/v1/workouts/{workoutId}`   | Caller-owned live workout aggregate                                           |
| GET `/v1/routines/{routineId}`   | Caller-owned live routine aggregate, including an owned draft                 |

Establish reusable canonical read mapping, live authorization, visibility/ownership checks, and consistent aggregate
reads. Return the contractual absence for missing, deleted or foreign private resources without disclosing ownership.
Reads must not mutate domain revisions, reservations, replay evidence or sync publication.

Acceptance checkpoint: contract-derived positive/negative real HTTP/PostgreSQL coverage, canonical fields and current
convenience labels, consistency under relevant concurrent writes, explicit readability review, and independent
frozen-state verification. Accepting 7a alone does not complete task 7.

## 7b — browsing and shared pagination

| Endpoint                      | Scope                                                                                                                 |
|-------------------------------|-----------------------------------------------------------------------------------------------------------------------|
| GET `/v1/catalogue/exercises` | Search bundled and caller-owned custom exercises, with declared search/muscle/equipment filters and catalogue version |
| GET `/v1/workouts`            | Workout summaries, optionally filtered by active/finished status                                                      |
| GET `/v1/routines`            | Saved non-draft routine aggregates                                                                                    |

Implement one shared signed, stateless keyset-token mechanism rather than separate cursor implementations per endpoint.
Keep endpoint queries, filter normalization, token validation and response construction cohesive and separately
understandable.

Contract requirements include:

- Live lists, not frozen result copies or stored list sessions.
- Limits 1–200, default 100.
- Opaque signed tokens bound to account, endpoint, normalized filters, limit and last sort key.
- A fixed expiry 15 minutes after the first page, preserved by continuations.
- Context mismatch/tampering: 400 `invalid_page_token`; authentic expired tokens: 410 `page_expired`.
- Locale-independent Unicode lowercase search text and sorted/deduplicated UUID filter sets.
- Workout order: startedAt descending, then UUID descending. Routine/exercise order: UUID ascending.
- `items`, `hasMore`, and nullable `nextPageToken`; catalogue pages also return `catalogueVersion`.
- Concurrent writes may change later pages. Browsing is not a sync bootstrap, deletion signal, repeatable export or
  frozen snapshot. Disabled accounts cannot continue browsing with an unexpired token.

Acceptance checkpoint: independent HTTP/PostgreSQL tests for filters, ordering/ties, page boundaries, normalized token
context, tampering/account isolation, fixed expiry, concurrent membership changes and canonical data. Finish with
integrated regression verification of both slices and the accepted mutation/auth surfaces.

## Shared contract and work organization

Before implementation begins, Coordinator defines the shared task-7 contract and fixed acceptance criteria for both
slices, including common representations/auth/consistency rules and the pagination protocol. Sequence implementation as
7a, then 7b; do not fragment the work into one task per endpoint.

Within each slice, Worker production implementation and QA requirement-first test authoring may proceed concurrently
under disjoint file ownership. Coordinator owns scope, integration, findings, structural review and acceptance. Use
focused handoffs at slice boundaries and final integrated verification; serialize Maven and freeze relevant inputs for
every check.

Maintainability/readability are mandatory alongside functionality: small cohesive methods, explicit results, meaningful
names, practical responsibility separation and removal of obvious duplication. Prefer shared canonical mapping and
pagination primitives where warranted, not speculative framework abstractions. Do not enlarge the legacy mutation god
method or slip a broad refactor into this read-only feature; record larger debt separately. All handoffs use concise,
normal grammatical English.

## Exclusions and safety

No new mutation kind, command, child endpoint or product cap. Sync pull/pruning/snapshots remain task 8; account
lifecycle remains task 9; deployment/general operational acceptance remains task 10. Do not invent catalogue reference
endpoints absent from OpenAPI.

Preserve accepted task-6 behavior/tests, applied migrations, local `gymtracka` data/schema, independent shared-docs,
secrets and Herdr layout. No staging/commit or local database migration is authorized by this plan. Test with isolated
PostgreSQL; never access the local development secret file.

**Final checkpoint:** 7a and 7b/integrated task 7 are accepted (`docs/task-7a-acceptance.md`,
`docs/task-7b-acceptance.md`). Final QA passed 144 targeted / 165 full tests; Coordinator passed six final 7b spots
and the mandatory structure gate. Worker/QA await a new assignment; task 8 or later work is not authorized by this plan.
