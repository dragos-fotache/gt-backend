# Contributor / agent handoff

## Read first

1. [README](../README.md): implemented surface, build/test commands and shared inputs.
2. [Architecture](architecture.md): components, transaction/replay boundaries and purge behavior.
3. [Known limitations](technical-debt.md): especially purge hardening and coverage gaps.
4. [Personal-server runbook](personal-server.md): environment, startup and backup cautions.
5. Relevant parts of the authoritative [shared contract](../shared-docs/docs/backend-contract/README.md).

Historical development task journals are no longer current instructions. A concise
[history](archive/README.md) explains how to recover original reports from Git.
No unfinished work is authorized merely by an old task plan or acceptance heading.

## Current scope

The v1 route implementations exist. The owner uses the backend and Android app
personally; a public-service deployment/compliance program is not the next implicit
task. Android integration remains separate. There is no fixed "Task 10" assignment.

Do not infer complete purge/deadline/race coverage from the historical green suite.
The documentation review corrected those overclaims and recorded concrete follow-up
items. This cleanup does not change code, keys, databases or any running server's flag.

## Safety and change control

- Never read, print, copy, overwrite or rotate real secrets through agent tools.
  `~/.config/gymtracka-backend/dev.env` is private. It is not a test input.
- Use isolated Testcontainers PostgreSQL for tests, not `gymtracka` or
  `gymtracka_prod`. Do not reset/migrate a personal database without explicit approval.
- Applied V1–V9 are immutable; future changes are forward-only.
- `shared-docs/` is a separate ignored checkout, not a submodule. Preserve its
  supplied state; review shared edits and backend hash-pin updates separately.
- Never stage/commit, deploy, change live configuration, stop unrelated services,
  replace agents or change layout/settings without authorization.
- Serialize Maven operations with `/tmp/gt-backend-task7-build.lock`. During team
  verification, freeze relevant source/build/shared inputs as well; a lock alone
  does not prevent edits. Record actual commands/results and preserve failures.
- Maintain readable, cohesive code and existing assertions; do not weaken tests
  to make a fixture pass or insert speculative frameworks into unrelated work.

## Team workflow when requested

The Coordinator owns architecture, interfaces, invariants, responsibilities,
acceptance criteria and design decisions. Worker owns implementation within that
design and reports ambiguities back rather than deciding them. QA owns independent,
contract-derived tests and verification. Disjoint authoring is allowed; verification
requires frozen inputs and exclusive build slots.

Use Herdr only when requested. Rediscover live agent identities/panes before control;
never assume historical IDs or that an idle agent pane is a free shell. Preserve cwd,
layout and unrelated services. Assign work asynchronously; Worker/QA notify the
Coordinator with a fire-and-forget Herdr message when green or genuinely blocked,
without `--wait`, then hold. Do not manage context/compaction implicitly.

Prefer `mcpls` Java navigation if available; report actual tool errors and use
read-only fallback without changing global tooling. Write concise grammatical reports.
No verification or authoring slot remains open from the historical implementation.
