# Known limitations and technical debt

This is a current follow-up list, not an implementation assignment. Functional
routes exist, but a passing suite is not proof of every shared-contract requirement.
The documentation review found that earlier purge acceptance language overstated
both implementation guarantees and coverage; this file records the actual gaps.

## Account-purge hardening and verification

References: `account/AccountPurgeService.java`, `account/AccountPurgeRepository.java`,
`account/ConfigBackedPurgeReadiness.java`, and the seven `AccountPurge*` test suites.

### Implementation gaps

- **Bounded child deletion versus restricted parents:** each pass deletes a limited
  set of children, then attempts parent deletion. Workout/routine entries and roots,
  and owned exercise rows, lack dependency guards comparable to snapshot headers,
  sync batches and auth sessions. Large child collections can therefore hit FK
  failures instead of returning clean partial progress. Earlier phase commits may
  make progress across retries, but reliable large-account completion is not proved.
- **Progress is recorded, not used as a resume cursor:** `purgeAccount` always
  restarts the full phase list. The `phase` column is bookkeeping. Claim locks end
  before processing; they are not a persistent single-purger lease. Overlapping
  purgers, repeated completion and state-regression outcomes need direct testing.
- **Effect checks are incomplete:** completion is conditional/count-checked and
  remaining-store counts are explicit, but several progress/state updates and
  bounded deletes do not compare selected versus deleted rows. Do not describe
  every effect as independently checked.
- **Readiness is only a flag:** enabling it does not check scheduler health, key
  coverage, backlog or capacity. A late *completion* logs a warning; there is no
  overdue unfinished-job health signal/alert. Lock waits are not explicitly bounded
  by the row budget. No deadline/availability guarantee follows from these defaults.
- **Permanent intent retention:** terminal progress rows remain alongside the minimal
  ledger. Their necessity and conformance with the shared permanent-evidence
  whitelist need review; the shared whitelist is not automatically widened by local code.

These are correctness/contract issues even for personal use, not merely optional
production polish. Keep deletion disabled on new installations until hardening and
verification are addressed. The documentation cleanup does not change the flag on
an already-running server or stop cleanup for previously accepted deletions.

### Coverage gaps

The dedicated purge suites cover 21 cases, including simple physical removal,
post-purge receipt/status, B isolation, unknown-intent rejection and repeated calls.
They do **not** establish all of the old acceptance summary's claimed evidence:

- `AccountPurgeCrashResumeHttpIntegrationTest` tests repeat calls, not an actual
  process stop/restart or injected phase/completion rollback fault.
- Deadline tests check the fixed receipt deadline and a 12-workout fixture, not
  beyond-budget child collections, controlled before/equal/after deadline behavior,
  busy-account priority or temporary-failure recovery by the deadline.
- Tests calling `purgeDue()` directly are not tests of timed `@Scheduled` invocation.
  Historical manual scheduling observation was a limited supplement.
- Purge-specific races versus sync, refresh/replay, snapshot capture/continuation,
  export, other cleanup services and a second purger are not all instantiated.
  Deletion-acceptance race tests and earlier shared-boundary tests are supplements.
- The purge recovery suite does not itself perform historical-key rollover,
  arbitrarily aged status, or process restart. Earlier ledger tests do not replace
  integration of those scenarios after actual purge.

A future fix should start with contract-derived tests for these cases, retain the
existing passing checks and re-review SQL/lock participation. Do not quietly relax
the 86,400-second shared deadline or infer success from disabled access alone.

## Backup restore and privacy

No automated, verified restore reconciles newer deletion evidence and ordered
sync/replay state with an old backup before serving. A blind restore can resurrect
deleted data and old sessions; see the [runbook](personal-server.md#6-backups-and-restore-caution).

Application/gateway-wide log redaction and maximum-retention enforcement are not
established by sanitized lifecycle errors or one scheduler warning. Avoid verbose
body/token logging and review any proxy/service-manager logs. Public-service
rate limiting, monitoring and capacity work can be deferred for personal use,
but do not claim that shared privacy/restore obligations have been satisfied.

## Legacy mutation command orchestration

`sync/RoutineUpsertExecutor.applyCommand` remains a large dispatcher combining
eighteen commands, eligibility SQL, flags/nullable intermediates, savepoints,
persistence and canonical batch construction. Separate replay ownership and explicit
domain results were extracted, but this is not the preferred structure for new features.

Refactor in a focused, behavior-preserving change: cohesive named command operations,
explicit results and practical separation of validation/transformation/persistence.
Preserve all commands, exact-byte identity, error classification, live fences,
reservations, revisions, complete ordered batches and cursor visibility. Avoid a
speculative handler framework or changing product/API behavior as part of cleanup.

## Test lifecycle and external integration

- Scheduled auth cleanup can log connection/Hikari errors after a Testcontainers
  database is stopped. Test-context scheduler shutdown needs separate investigation;
  do not mistake stale/late warnings for newly run tests or hide real failures.
- Most provider tests use deterministic doubles or stub certificates. They do not
  establish configured Android Google sign-in or deployed HTTPS behavior.
- Personal startup was observed against PostgreSQL 18.6; integration tests use 16.
  Add explicit version coverage if relying on another version long-term.
