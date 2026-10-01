# Canonical aggregate foundation (subtask 4)

`domain.CanonicalAggregates` defines immutable internal aggregate values; `AggregateValidator` checks complete shape,
family-wide UUID uniqueness, UTC millisecond-range timestamps, finite/nonnegative numeric inputs, nullability, and
workflow eligibility. `CanonicalAggregateRepository` is a **low-level row adapter, not an accepted mutation API**. Its
ordinary writes enter `AccountWriteRepository.inAccountWrite`; mutation-engine guarded primitives require an existing
transaction and owner lock. These adapter methods do not publish a change batch or replay evidence by themselves. Write
inputs have revision zero; database inserts/increments assign server revisions, never client-known revisions. The
historical limits below describe the task-4 foundation, not the current sync-push implementation.

Available row operations include active workout replacement of an existing root, create-only workout import (active or
validated finished), draft/saved routine autosave without status transition, full custom-exercise relationship
replacement, owner-scoped complete canonical reads, and current exercise-name projection for workout/routine child rows.
Child and root IDs are preserved, nullable numeric fields and nullable-versus-empty instructions are represented, and
reference visibility is checked inside the transaction. Spring-managed multi-query reads use repeatable-read
transactions; direct construction in tests bypasses Spring transaction proxies, so tests wrap writes in
`TransactionTemplate`. Do not expose row methods as domain HTTP endpoints.

## Limits of this subtask-4 foundation (historical)

The row adapter itself does not publish batches or replay evidence and is not a direct HTTP domain endpoint. At the end
of subtask 4 no mutation engine existed; 5b first composed its routine row write, and 5c–5f subsequently added the
remaining sync kinds. The current sync executor enforces account/session authorization, create-only import and
active-replacement policy, per-mutation transactions, canonical publication and durable replay for all 17 kinds; V5
supplies exact-byte replay/cursor storage. Direct resource routes and sync pull/snapshots remain future work. Foundation
tests cover PostgreSQL round trips, owner isolation, nested-reference rollback, current-name projection and revisions;
sync HTTP/PostgreSQL tests exercise the executor. See `README.md` and `docs/sync-push-implementation.md` for current
scope and the shared backend contract for normative behavior.
