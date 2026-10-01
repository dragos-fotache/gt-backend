# Next Coordinator handoff — original Task 9 plan with current acceptance update

## Current update — 9a accepted

The user authorized **9a only** with the Muse Spark 1.3 Contributor Medium Pi pair, and bounded 9a is now accepted in `docs/task-9a-acceptance.md`. Read `docs/coordinator-handoff.md` for final evidence, durable checkpoint, live actor observations and next authority. Final independent QA passed 227 integrated / 248 full tests; Worker passed 204 and Coordinator passed eleven spots plus the structural gate, all without failures/errors/skips on identical corrected 115-path inputs. All verification windows are closed.

**9b/9c, Task 10, further replacement, commit and deployment remain unauthorized.** Obtain explicit 9b authority and its no-edit design/readiness review before assignment. The separate 24-hour physical purge deadline is unchanged. The original planning-only statements below are historical, not current permissions; they do not supersede 9a acceptance or authorize later work.

## Start here: original planning authority and next action

The user asked to create documents for the three Task 9 slices and prepare this handoff. **Planning is authorized; implementation is not.** No Task 9 production code, tests, migrations, keys or runtime configuration have been authored. No Task 9 worker assignment, baseline Maven run or further commit has been authorized or performed. The user subsequently authorized new Worker/QA Pi sessions in the existing Herdr panes, using Muse Spark 1.3 Contributor Medium; this does not authorize implementation.

Next action is to read the plans, then obtain explicit authorization for **9a**. The current actor/model policy is now approved as recorded below. Do not start 9b/9c from a general roadmap statement. Task 10, deployment and dedicated legacy refactoring remain unauthorized. The earlier user permission to make the initial commit was exercised; it is not continuing permission to commit these planning files.

Read in order:

1. `README.md` and `docs/coordinator-handoff.md` for accepted state and protected resources.
2. `docs/task-9-contract.md` for shared rules, sequencing and approval gates.
3. `docs/task-9a-contract.md`, `docs/task-9b-contract.md`, and `docs/task-9c-contract.md` completely.
4. Shared backend-contract README, account-lifecycle, authentication, retention, service-protocol, OpenAPI, schema, domain/compatibility and relevant acceptance documents. The shared contract is authoritative; no Android source is needed.

These are planned execution contracts, not acceptance documents. Resolve any specification mismatch before choosing an implementation. Concrete schema, crypto key-ring, export transaction/cancellation, purge budgets and readiness configuration still require a no-edit Worker design and Coordinator approval.

## Git baseline and working tree

Cwd: `/home/dragos/IdeaProjects/gt-backend`. Stack: Java 27, Spring Boot 4.1.1, Maven wrapper, PostgreSQL/Flyway; package `com.dragosfotache.gtbackend`.

Initial backend commit: **`36fe88d8951d17b66a45a537497fcd1b5dacf8d3`**, `Establish accepted backend baseline through task 8c`. It contains 113 accepted source/test/build/documentation files, including the pinned Maven wrapper JAR. No remote push was performed. The backend was clean immediately after that commit.

This planning pass adds five untracked Task 9 Markdown files and updates only the central handoff and README pointers. They are not part of the initial commit or the earlier durable 8c checkpoint. Use `git status --short` as well as `git diff`: a diff alone does not show untracked plans. Preserve them and any later user changes. If HEAD differs, inspect the new history before choosing a baseline; do not reset or clean the checkout to force it back to this hash.

`shared-docs/` is a separate ignored Git checkout, **never a submodule**. HEAD remains `3aefeb3e2eaa147793d595833217d6070eeabff2`, with 27 pre-existing/current modified tracked files. The accepted snapshot-retention clarification changed only shared retention and service protocol; all other supplied edits remain preserved. `docs/shared-docs-inputs.sha256` records 27 exact normative/catalogue inputs and currently verifies. HEAD alone does not reproduce this dirty checkout. No shared-docs commit or new shared edit is authorized.

Useful read-only checks from the backend root:

```bash
git status --short
git log -1 --oneline
git -C shared-docs status --short
git -C shared-docs rev-parse HEAD
sha256sum -c docs/shared-docs-inputs.sha256
```

## Accepted evidence, not a new test run

Tasks 5–8 are accepted within their documented bounds. For final 8c inputs, independent QA passed **187 targeted tests across 15 suites** and **208 full-suite tests across 18 suites**. The final Worker self-check passed **147 tests across 11 suites**. The Coordinator passed **eleven HTTP/PostgreSQL spots across three suites** and the structural gate. All exited with code 0 with no failures, errors or skips.

The final 107-path behavioral manifest is `440efc494f7fffda03e74c6ef304fc27052a78d81a910e50bb0001974817a148`. Later status and commit-hygiene documentation changes follow those manifests; production, tests and build implementation stayed unchanged. No new behavioral tests were run for this planning request. Read `docs/task-8c-acceptance.md` and `docs/task-8c-review.md` for scope, limits and resolved findings.

Durable evidence is outside temporary storage at:

`/home/dragos/.local/state/gymtracka-backend/coordinator-checkpoints/task-8c-20261001T074849Z/`

Original evidence directory names are retained directly beneath it. Replace `/tmp/` with that prefix when following old 8c evidence paths. Final directories include:

- `gt-backend-task8c-qa-followup-snapshot-W6aRB6` — 20 dedicated tests.
- `gt-backend-task8c-qa-followup-integrated-tRe6jR` — 187 integrated tests.
- `gt-backend-task8c-qa-followup-full-nKAbZq` — 208 full-suite tests.
- `gt-backend-task8c-worker-final-selfcheck-PVusxP` — 147 Worker tests.
- `gt-backend-task8c-coordinator-spot-qgsy9pph` — eleven Coordinator spots.

The archive has a verified 759-file manifest, an intact starting baseline and curated accepted project state. Verify with `sha256sum -c checkpoint-files.sha256` from its root. It excludes Git checkouts, secrets, local database, mutable target and stale packaged application JARs; the legitimate wrapper build input is retained. This is handoff evidence, not deployment or disaster-recovery acceptance. Current `target/surefire-reports` can contain later subsets/stale reports and must not be used as the full-suite evidence.

## Planned slices and the non-negotiable boundaries

### 9a: fresh proof and export

Reuse existing purpose-bound challenges and Google verification without provisioning accounts or minting grants. Export all current live owned cloud roots, including drafts, through one consistent direct JSON response. Preserve IDs and values, use narrower export shapes, and omit revisions/sync/credential internals. No stored export, public link, resume token or export replay.

The difficult boundary is durable proof consumption at authorized read admission versus a long consistent stream. An interrupted admitted export cannot make the old proof reusable. Cancellation must observe newer account disablement, not only the frozen read snapshot; an incomplete download cannot be finalized as a successful partial file. Controlled disablement fixtures in 9a must later be reverified with actual deletion.

### 9b: deletion acceptance, status and minimal evidence

One transaction records the original minimal receipt/digest and purge intent, consumes successful proof, disables the account/all sessions and detaches A's current provider binding. Identical accepted deletion replay precedes original proof expiry checks and survives purge; changed body under the key conflicts. Status uses fresh proof for the specified account, including old A after the subject owns B. No lifecycle proof route creates an account.

Minimal evidence is permanent and strictly whitelisted; raw subject/token/body, profile, credentials and domain copies do not belong in it. Historical key versions must remain verifiable across approved rollover. Do not rotate or read the existing development secret to design this.

**Do not enable ordinary deletion between 9b and 9c.** Approve a fail-closed purge-readiness gate before 9b authoring. Isolated tests may explicitly enable acceptance, but a queue alone does not satisfy the physical deadline. Since detachment permits fresh signup, basic B-isolation must already work in 9b; it cannot be deferred until after purge exists.

### 9c: physical purge and integrated recovery

Purge every A-bearing live copy by the original 24-hour deadline, preserving only the whitelist. Inventory actual tables/FKs/triggers, including replay, credentials, snapshots and later lifecycle state. Use finite transactions, durable progress and completion checks; large accounts and failure recovery must not extend `purgeBy`. Delayed A work is always filtered by A's UUID, never by the Google subject now bound to B.

Prove status/receipt recovery after actual purge and actual service restart, key-version verification, signup before/after purge, all API/export/snapshot races and no new durable copies after completion. The snapshot cleanup lag exception does not apply. Controlled deadline tests are not an unconditional capacity/availability SLA: unresolved operating conditions remain an explicit deletion-release blocker. Backup replication and actual restore stay Task 10.

## Source landmarks and known traps

- V4 and `AuthService.challenge` already support all four purposes. The current exchange path is signup-capable; do not reuse it as a non-signup lifecycle authorization shortcut.
- AuthService uses a provider-subject advisory transaction lock for binding/provisioning. Review it with account/session/credential, challenge, auth-replay and new ledger locks before adding deletion. Labels such as "account-first" are not proof of actual SQL lock ordering.
- Canonical projections are reusable, but existing API/snapshot records contain fields forbidden in export. Follow OpenAPI export schemas rather than copying GET results.
- Read-only repeatable-read authorization can return a coherent pre-change view. It does not observe future deletion during a long export, and it must not allow a new durable copy after purge completion.
- Snapshot header insertion and new cursor metadata take implicit account foreign-key key-share locks. In 8c, a test paused their holder and synchronously awaited a conflicting writer, causing an artificial timeout. Design deterministic gates around actual locks; do not remove fences to make a bad fixture pass.
- V3 tombstone-child delete triggers require a disabled account. V7 snapshot-to-cursor references and many domain/auth FKs are restrictive. Keep the required disabled account/parent anchors until children are removed. Do not bypass triggers or guess deletion order.
- Actual process restart means the creating/accepting process is stopped before recovery in a different process. A fresh reader while the originator stays running is useful but weaker evidence.

## Team and execution gates

Latest live discovery found Coordinator `w4:p7` and available foreground zsh shells in the existing Worker `w4:pD` and QA `w4:pE` panes, all in workspace `w4`, tab `w4:t1`, at this cwd. Under explicit user startup authority, fresh Pi sessions now run as Worker `backend_worker` (`gt-backend-worker-muse`) and QA `backend_qa` (`gt-backend-qa-muse`). Both verified `meta/muse-spark-1.3-contributor` with `medium` reasoning, read the README and this handoff, acknowledged role/safety boundaries and returned idle. No prior agent needed stopping; layout/cwd were unchanged and no focus command was issued. These are observed IDs, not an overnight guarantee.

Do not send them implementation assignments from this handoff. The user-approved model policy is now **Muse Spark 1.3 Contributor Medium** in Pi, replacing historical Luna. Future per-slice replacement still requires authorization; earlier Task 8 permission does not authorize it. Read the Herdr skill, verify `HERDR_ENV=1` and rediscover live state before control. Idle agent panes are not shell prompts. Preserve layout, cwd, focus and unrelated services. Do not manage context or compaction.

Worker owns production and its slice report; QA owns requirement-first tests and its report; Coordinator owns contracts, integration/findings, structure, acceptance and handoff. After slice authorization:

1. Capture current Git and independent shared state; establish actual relevant input manifests.
2. Give QA the exclusive frozen baseline full-suite slot. Worker inspects and proposes a no-edit design only. Historical expected count is 208 tests across 18 suites; record actual fresh results rather than assume them.
3. Record QA's requirement-first plan before new implementation inspection. Approve concrete schema, keys/configuration, locks, stream admission/cancellation, release readiness and test seams before releasing authoring.
4. Permit disjoint production/test authoring, then open appropriately frozen verification windows. Every Maven operation uses `flock /tmp/gt-backend-task7-build.lock ./mvnw ...`; lock alone is not a source freeze.
5. Within an exclusive QA window, preserve a run's fresh evidence and close it before correcting QA-owned compilation/fixture mistakes and retrying. No weakening requirements or production edits. Report when green or truly blocked, not after every small correction. Avoid `--wait` when reporting to an active Coordinator, and quote literal messages safely.
6. Verify Worker self-check, independent integrated/full tests, actual creating-process restart, final structural/coverage review and Coordinator HTTP/PostgreSQL spots on identical corrected inputs before bounded acceptance. Keep 9b and final deployment readiness limits explicit.

Prefer mcpls Java navigation; the planning Coordinator's AuthService symbol request reported Java initialization in progress. Read-only fallback was used without tooling changes. Write proper grammatical English with normal spacing, not compressed status shorthand. There is no report line quota or mandatory matrix format.

## Protected resources and final limits

Never access the local `gymtracka` database or read/source/print/copy/rotate `~/.config/gymtracka-backend/dev.env` or real secrets. Use isolated Testcontainers PostgreSQL 16 and real HTTP; external Google may be mocked for deterministic protocol tests, without claiming live provider/deployed verification. Applied V1–V7 are immutable; concrete future migrations require approval and must be forward-only.

No implementation, further agent replacement, staging/commit, shared reset/edit/submodule conversion, deployment, unrelated shutdown or layout change is authorized by these planning documents. The separate user authorization for current Muse Worker/QA startup has been exercised. Stop only test-created processes and remove only test-owned faults. Existing post-Testcontainers auth-cleanup/Hikari noise is separate debt, not permission for broader cleanup. No pending Task 9 Maven window exists.
