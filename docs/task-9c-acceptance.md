# Task 9c — bounded functional acceptance

## Decision

**9c is accepted as functional purge, re-signup isolation and integrated
lifecycle verification.** 9a and 9b remain accepted. This is not
complete-v1, deployment, backup/restore, legal-compliance, Android or live
Google/HTTPS acceptance, and it is not a production release decision.

The Coordinator owned the design (`docs/task-9c-design.md`); the Worker
delivered facts then implemented locally with no design authority; QA owned
requirement-first tests with independent oracles. Ordinary deletion opens
only behind the verified config flag (`account.deletion.purge.enabled`,
default false); the shipped default stays fail-closed (`503`). The
unchanged deadline is **`purgeBy = deletedAt + 86,400 seconds`**. No
Task 10, commit, deployment, shared-checkout edit, agent/model change or
context-management authority follows.

## Accepted input version

- Backend HEAD: `af6e1f59a537112a2efe3585f8ddcb90c0e4885b`; 9b/9c changes
  uncommitted. Shared HEAD: `0bd414b6c1b819a26e2d5fca0bed6b6b5acf2cea`,
  clean, all 27 pinned inputs verified at baseline.
- Final **frozen inputs manifest SHA-256
  `cd88c3beca88b6b3f06bb24189467327758b4b6db3ffc8ccdcf2c7221e6f2a1e`**,
  byte-identical before/after in every final window (self-check 9c03,
  dedicated, integrated, full).
- Forward-only `V9__purge_progress.sql`; V1–V8 untouched. Production adds
  `AccountPurgeRepository`, `AccountPurgeService` (with `purgeDue`),
  `AccountPurgeScheduler`, `ConfigBackedPurgeReadiness` and purge keys in
  `application.yaml`. No dependency, controller, auth-route or
  shared-contract change.

Acceptance/status metadata in this file, README and coordinator records is
outside the frozen input inventory.

## Independently verified gates

All Maven operations used `flock /tmp/gt-backend-task7-build.lock` in
exclusive windows. Totals below are parsed actual fresh XML.

| Gate | UTC on 2026-10-02 | Result | Evidence root under `/tmp/` |
|---|---|---|---|
| QA frozen baseline full suite | 13:34:06–13:43:58 | exit 0; **304 / 34** | `gt-backend-task9c-qa-baseline-9c01` (manifest `a0e4379c`, pre-authoring inputs) |
| Worker self-check 9c03 | rerun window | exit 0; **136 / 26** | `gt-backend-task9c-worker-selfcheck-9c03` |
| QA dedicated (7 purge suites) | dedicated window | exit 0; **21 / 7** | `gt-backend-task9c-qa-dedicated-9c01` |
| QA integrated regression | sequential window | exit 0; **278 / 36** | `gt-backend-task9c-qa-final-integrated-01` |
| QA full suite | after integrated | exit 0; **325 / 41** | `gt-backend-task9c-qa-final-full-01` |

All green gates have **zero failures/errors/skips**. The final four use
identical inputs at `cd88c3be`. Failed self-checks 9c01 (systematic 500,
seam miss, 4 fixture issues) and 9c02 (3 fixture issues) are preserved as
honest failure evidence with their roots.

Coordinator spots (manual, existing compiled classes, no Maven, isolated
PostgreSQL 16 + loopback JVMs, synthetic keys only) in
`/tmp/gt-backend-task9c-coordinator-spots-9c01/`, 14:40:50–14:47:42Z:

- **Gate-off:** new deletion with purge disabled returns `503
  temporarily_unavailable` with `no-store` before any proof check; the
  fresh challenge stays unconsumed and zero account/ledger/intent rows
  are created.
- **SQL state machine:** V9 state/`purge_by` CHECKs reject bad rows;
  completion requires `in_progress`; the ledger anchor `RESTRICT`s while
  an intent exists; claim order is earliest `purge_by` first; stored
  deadlines are exactly 1 day.
- **Scheduled path:** with the flag enabled on an isolated database, the
  sweep claimed a stale intent and drove it to `complete` with zero
  deletions through the verification step inside one interval.

Coordinator structural review covered every new/changed production file:
phase order, one transaction per phase, account-first locks, UUID-only
scoping, budgeted `ctid` deletes, FK-aware guards, same-transaction
completeness check, immutable deadline with sanitized breach logging, and
the three hardening fixes (missing-intent throw, live-account throw,
no-cascade account delete).

## Coverage disposition (AC9c.1–8)

AC9c.1 removal/whitelist/catalogue/B-intact, AC9c.2 deadline edges,
budgets, priority, real scheduler path and breach-as-failure, AC9c.3 crash
injection, rollback, durable resume and actual restart, AC9c.4 receipt
replay, aged status, key rollover and missing-key fail-closed, AC9c.5
B-before/after signup with rich B state and in-flight attempt boundary,
AC9c.6 writer/refresh/replay/snapshot/export/cleanup/second-purger races
with no late copies, AC9c.7 whitelist-only evidence and structural limits,
and AC9c.8 frozen Worker/dedicated/integrated/full plus Coordinator review
and spots all pass within the cited evidence. Controlled-time/capacity
results are bounded fixtures, not a production SLA; remaining capacity,
alerting and outage handling stay explicit release blockers owned by the
section 11 gate.

## Honest limits and retained failures

- Self-check 9c01 and 9c02 failures, the diag root, stale target reports
  and the two-`now()` microsecond CHECK observation are retained; no
  production behavior or assertion was weakened to obtain green.
- Synthetic Google/key rings are not live-provider evidence. Already
  downloaded bytes are outside retroactive erasure. Backup replication
  and actual restore remain Task 10.
- All execution slots are CLOSED. Both agents hold with no authoring and
  no Maven until explicitly assigned.
