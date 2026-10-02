# Implementation history

This is historical context, **not current operating instructions**. Current
entry points are the [README](../../README.md), [architecture](../architecture.md),
[runbook](../personal-server.md) and [known limitations](../technical-debt.md).
The shared contract remains authoritative.

## What was built

| Stage | Delivered |
|---|---|
| Foundations | Pinned catalogue import, account-owned aggregates/children, identity reservations, canonical projection/validation, Google challenges and service credentials. |
| Sync commands | Seventeen commands, strict structural preflight, exact-byte accepted/rejected replay, ordered complete batches and account-bound cursors. |
| Direct resources | Fourteen mutable operations with a separate method/route/body replay namespace, sharing domain application/publication. |
| Reads and browsing | Coherent individual reads and signed stateless live list continuations. |
| Pull and retention | Complete incremental batches, 90-day contiguous-prefix pruning, durable frozen snapshots and expired-storage cleanup. |
| Account lifecycle | Purpose-bound export, deletion acceptance/status, minimal versioned ledger, provider detachment, credential/write/durable-copy fences and scheduled physical purge. |

The development sequence was recorded as tasks 5–9 and bounded acceptance slices.
Useful decisions have been consolidated into architecture and limitations rather
than leaving task numbers as navigation. The purge implementation exists, but the
old all-criteria acceptance declaration exceeded the actual tests/source evidence;
see the correction below.

## Verification checkpoint

The implementation-era final reports, dated 2026-10-02, recorded:

| Run | Tests / suites | Recorded result |
|---|---|---|
| Worker account/auth self-check | 136 / 26 | Exit 0, no failures/errors/skips |
| Independent purge target | 21 / 7 | Exit 0, no failures/errors/skips |
| Independent integrated target | 278 / 36 | Exit 0, no failures/errors/skips |
| Independent full suite | 325 / 41 | Exit 0, no failures/errors/skips |

Their common source/build/fixed-input manifest was
`cd88c3beca88b6b3f06bb24189467327758b4b6db3ffc8ccdcf2c7221e6f2a1e`.
These are historical results, not a test run performed during documentation cleanup.
Later documentation and shared-input pin changes mean that manifest is not the
current checkout manifest. Test source and production code were not changed by this cleanup.

Earlier failed purge runs were corrected for the missing direct-entry
`pending -> in_progress` transition, a missing callable scheduler entry, and
fixture/body/count issues. Original failures and reports remain recoverable from
Git and, while retained, the local evidence checkpoints.

**Correction to the old acceptance narrative:** passing those tests did not prove
phase-fault rollback, actual purge-process restart, exact deadline boundaries,
beyond-budget child collections, all purge/cleanup races, or production capacity.
Some of these were only planned, not implemented as tests. The new documentation
supersedes the previous blanket claim that every purge acceptance criterion passed;
it does not change the wire contract or pretend those gaps are production-only concerns.

## Git checkpoints and removed journals

- `36fe88d`: committed baseline through reads, pull/pruning and snapshots.
- `54a7cb7`: deletion/status implementation and implementation-era design records
  (alongside other supplied work).
- `d6b0508`: purge implementation, tests and the original acceptance declaration.
- `690d428`: initial personal-server runbook.
- `16caf08`: updated shared-contract input pin after the deletion-unavailable clarification.

Repetitive task contracts, execution journals, Worker/QA reports and implementation
notes were removed from the working documentation tree. They are still available
in Git at `16caf08`, without maintaining a second archive full of stale instructions:

```bash
git ls-tree -r --name-only 16caf08 docs/
git show 16caf08:docs/task-9c-qa.md
git show 16caf08:docs/task-9c-design.md
```

The old design describes intent, not proof of current implementation guarantees.
Consult current source and known limitations when changing behavior.

## Optional local evidence

Detailed Maven logs, copied XML, manifests and snapshots were stored outside Git at
`~/.local/state/gymtracka-backend/coordinator-checkpoints/`, with some original
artifacts under `/tmp/gt-backend-*`. They are optional local debug provenance, not a
build input, backup of personal data, or requirement for running this server. Cloning
this repository does not copy them.

The documentation cleanup leaves those directories in place pending review. They
can be deleted separately once no longer useful; tests and Git history are the
maintained evidence. Do not delete running-service files, databases, keys or unrelated
`/tmp` contents along with old test artifacts.
