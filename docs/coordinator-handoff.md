# GymTracka backend — Coordinator checkpoint

## Current status and next action

### Initial Git baseline authorized

After 8c acceptance, the user explicitly authorized an initial backend commit containing the accepted source, tests, build files and documentation. The independent `shared-docs/` checkout is excluded and remains untouched, including its supplied working-tree edits and the two approved retention clarifications. Its revision and exact verified input hashes are recorded in `docs/shared-docs-inputs.sha256`; the durable checkpoint retains the supplied contents.

Use the resulting initial commit as the backend baseline for future work (`git log --oneline` identifies it). Statements below about an entirely untracked backend describe the historical pre-commit state. The user has not authorized later implementation, automatic future commits or a commit in shared-docs. No secrets, local database, IDE state, mutable build output or test-created runtime artifacts belong in this commit.

### Task 8c accepted; await authorization for Task 9

**8c frozen snapshots is accepted**, alongside accepted 8a and 8b. Acceptance is recorded in `docs/task-8c-acceptance.md`; fixed criteria are in `docs/task-8c-contract.md`, and final review is in `docs/task-8c-review.md`. This completes the agreed bounded Task 8 sequence, not complete-v1 or deployment acceptance. Next is Task 9 account lifecycle, export, status, deletion evidence and purge, but await explicit user authorization and define its detailed contract before starting. Task 10 and broader legacy refactoring remain unauthorized. The historical instructions below describe earlier checkpoints, not current permissions or actor identities.

The fresh openai-codex gpt-6-luna High pair completed 8c and is idle awaiting assignment: Worker `backend_worker` at `w4:pD` (session `gt-backend-worker-task8c`) and QA `backend_qa` at `w4:pE` (session `gt-backend-qa-task8c`). The Coordinator remains at `w4:p7`. Live discovery verified available interactive shells before startup; layout and working directories were preserved. Rediscover live state before future control. Do not manage agent context or compaction, reset these actors, or start later work without authority.

Final independent QA passed **187 targeted tests across 15 suites** and **208 full-suite tests across 18 suites**. The final Worker self-check passed **147 tests across 11 suites**, and the Coordinator passed **eleven HTTP/PostgreSQL spots across three suites** and the mandatory structural gate. All exited with code 0 and had no failures, errors or skips on identical frozen **107-path** inputs, manifest SHA-256 `440efc494f7fffda03e74c6ef304fc27052a78d81a910e50bb0001974817a148`. Final evidence: `/tmp/gt-backend-task8c-qa-followup-integrated-tRe6jR/`, `/tmp/gt-backend-task8c-qa-followup-full-nKAbZq/`, `/tmp/gt-backend-task8c-worker-final-selfcheck-PVusxP/`, and `/tmp/gt-backend-task8c-coordinator-spot-qgsy9pph/`. Dedicated QA evidence is `/tmp/gt-backend-task8c-qa-followup-snapshot-W6aRB6/` (20 tests across three suites). The Coordinator independently checked fresh XML, report timestamps, hashes and baseline-relative scope. Final status-only README and documentation updates follow these manifests; production, tests, build and normative inputs are unchanged afterward. Current mutable reports contain later subsets; copied evidence preserves the full counts.

The durable 8c checkpoint location is `/home/dragos/.local/state/gymtracka-backend/coordinator-checkpoints/task-8c-20261001T074849Z/`. Original `/tmp/gt-backend-task8c-*` directory names are retained directly under it, so replace `/tmp/` with that prefix when following evidence links. `project-state/` contains the curated accepted source, build, documentation and normative inputs; the starting baseline also preserves pre-clarification shared inputs. Verify the archive by running `sha256sum -c checkpoint-files.sha256` from that directory. This is a local handoff checkpoint, not backup/restore or deployment acceptance. It excludes Git checkouts, the local database, secrets, mutable target files and stale packaged JARs.

All 8c verification windows are CLOSED. The resolved findings include capture-expiry rollback, V7 required JSON and revision checks, exhausted-retry HTTP 503 mapping, a test-imposed circular wait through an account foreign-key lock, and actual restart of the process that created the snapshot. The final test has child process A create the snapshot, stops A, and resumes the original token through process B. Earlier failed and superseded runs remain historical evidence, listed in `docs/task-8c-qa.md`; do not substitute their hashes or counts for the final freeze. No blocking finding remains.

The user reconfirmed exact 24-hour access expiry with bounded physical cleanup afterward, authorizing only narrow clarifications to shared `retention.md` and `service-protocol.md`. All other supplied shared changes and HEAD remain preserved. Scheduling, backlog, downtime and large cascades may delay deletion; no production cleanup-latency SLA is claimed, and the separate account-deletion purge deadline is unchanged. The user requires proper grammatical English and normal spacing in messages and reports. Within an explicitly approved exclusive QA window, QA may preserve a failed run, close it, correct its own tests without weakening requirements, and retry with fresh manifests. Batch reports when green or genuinely blocked; production findings and behavior changes still go through the Coordinator.

The starting copy is `/tmp/gt-backend-task8c-baseline-yxs7moqp`: 130 paths, with manifest SHA-256 `a04290f49a9a8cd5a782397d95ba5c3ba632fac1f89f1749db70b0aa21af8e55`. Shared-docs HEAD, status and diff were captured, and the durable 8b checkpoint was verified. Continue using `/tmp/gt-backend-task7-build.lock` and explicit input freezes. The Coordinator has not authored production code or tests. Its initial mcpls request reported Java initialization in progress, so it used read-only file inspection without changing tooling.

### Historical overnight resume checklist

- Read this checkpoint, `README.md`, `docs/task-8-contract.md`, `docs/task-8b-acceptance.md` and `docs/task-8b-contract.md`. No prior conversation is required to recover roles, scope, safety or verification state.
- 8a and 8b are accepted. **Next is 8c, but await explicit user authorization**, then define its detailed fixed contract and start one fresh gpt-6-luna High Worker and QA. Prefer mcpls; do not manage context/compaction.
- Rediscover live Herdr state before control; do not assume today's actors/panes survived overnight. Last observed Worker/QA were idle, with no Maven/Surefire/test-created backend process running. Preserve layout/cwd and unrelated services.
- Backend work is uncommitted/entirely untracked by prior instruction. Do not stage/commit without approval; use manifests/baseline-relative comparisons, not empty git diff. Preserve supplied independent shared-docs and local DB/secrets.
- **Durable evidence and code snapshot outside /tmp:** `/home/dragos/.local/state/gymtracka-backend/coordinator-checkpoints/task-8b-20260930T182603Z/`. Original temporary directory names are preserved under it; replace `/tmp/` with that prefix when following 8b evidence links. It includes baseline, failure/corrected/Worker/final QA/Coordinator artifacts and `project-state/` (accepted code/build/docs). Normative/catalogue copies are in its starting-baseline/shared-docs. Verify files using `sha256sum -c checkpoint-files.sha256` from that directory. This is a local handoff snapshot, not deployment/disaster-recovery acceptance; no Git checkout, secret file, local database, mutable target or stale packaged JAR was copied.

**8b is accepted** for production 90-day contiguous-prefix pruning, bounded scheduling, preserved pull/replay/domain/identity/credential state and mandatory structural review. Acceptance: `docs/task-8b-acceptance.md`; fixed criteria: `docs/task-8b-contract.md`; review/findings: `docs/task-8b-review.md`. Final QA passed **167 targeted /188 full tests** (12 /15 suites); Coordinator passed **eight HTTP/PostgreSQL spots** (3 suites). All exit0, zero failures/errors/skips on identical frozen96-path inputs, manifest `6eea3454693c7bd1f1623e04a9aadc8dbaab5300c1decc3528fbce63feb3d9cd`. Artifacts: `/tmp/gt-backend-task8b-qa-final-target-20260930T175941Z/`, `/tmp/gt-backend-task8b-qa-final-full-20260930T180155Z/`, `/tmp/gt-backend-task8b-coordinator-spot-VRGuSP/`. Current target contains only eight later spots; copied reports preserve full counts. Final status-only README/docs updates follow those manifests; production/tests/build/normative inputs remain unchanged.

**8c frozen snapshots is next but unauthorized**, and requires its own fresh Worker/QA pair. Current actors are idle awaiting assignment: backend_worker /w4:pD (gt-backend-worker-task8b), backend_qa /w4:pE (gt-backend-qa-task8b), both fresh-for-8b openai-codex gpt-6-luna High. No context/compaction management; all roles prefer mcpls and report actual errors/read-only fallback without tooling changes. Continue project-wide `/tmp/gt-backend-task7-build.lock` plus explicit input freezes. All 8b slots are closed. No local DB/secrets, staging/commit, applied migrations/shared-docs or layout change. Baseline `/tmp/gt-backend-task8b-baseline-ljes6evs` remains intact; QA baseline evidence is separately copied at `/tmp/gt-backend-task8b-qa-baseline-evidence-20260930T171026Z/`. Historical accepted 8a and Task5–7 checkpoints follow.

**Task 8a is accepted** for incremental pull/shared durable account-bound cursors and the mandatory structural gate. Acceptance: `docs/task-8a-acceptance.md`; fixed scope/three-slice plan: `docs/task-8-contract.md`; review/findings: `docs/task-8a-review.md`. Final independent QA passed **156 targeted / 177 full tests** (10 / 13 suites); Coordinator passed **seven HTTP/PostgreSQL spots** (4 suites). All had zero failures/errors/skips on identical frozen 91-path inputs, manifest SHA-256 `b0b9df36e20f4e231c86f98335c7e860c183bf9eaaa8d0c140a716e8105de2aa`. Artifacts: `/tmp/gt-backend-task8a-qa-final-target-20260930T162426Z/`, `/tmp/gt-backend-task8a-qa-final-full-20260930T162611Z/`, `/tmp/gt-backend-task8a-coordinator-spot-9jWYnT/`. Current target contains only the later seven spots; copied reports preserve full counts. Manifests describe verification before final status-only README/docs updates; production/tests/build/shared-docs stayed unchanged afterward.

The user approved three slices with one fresh gpt-6-luna High Worker/QA per slice and authorized **8a only**. At the 8a acceptance checkpoint, 8b awaited authorization; **the user has now authorized 8b under `docs/task-8b-contract.md`**. 8c snapshots and later work remain unauthorized. Do not manage agent context or compaction. All roles prefer mcpls Java navigation, report actual errors and use read-only fallback without tooling changes. Baseline copy: `/tmp/gt-backend-task8a-baseline-qntn15_q`, 121-path inputs manifest `3d9c88e024d5df34939afbeeba1154a8d668f1fa4ef03ac0553da2378de4478f`. Fresh Worker/QA use existing verified shell panes, same cwd/layout; Coordinator interruption was resumed without replacing them. Continue using `/tmp/gt-backend-task7-build.lock` and agreed input freezes. All 8a build/verification slots are closed. Historical Task 5–7 evidence follows; do not confuse its mutable target descriptions with current artifacts.

**Task 6 is accepted** for all 14 direct mutable resource/command operations and separate resource replay. Task 5
remains accepted for its 17-kind sync-push surface. This is not complete-v1 or deployment acceptance.

**7a is accepted.** Its three individual exercise/workout/routine GETs, common canonical mapping, live authorization
and consistent read boundary passed independent verification and the mandatory clean/readable-code gate.
`docs/task-7a-acceptance.md` records scope, findings, structural review, exact commands and limits. Final post-fixture-fix
QA passed **127 targeted / 148 full tests** (6 / 9 suites); Coordinator passed **five real HTTP/PostgreSQL spots** on
the same frozen inputs. All had zero failures/errors/skips; final relevant manifests match at
`9dd8ca3ba78174a2ea2c97e3b7ea385b2e9545e30ba838a957469c7abacff17b`. Artifacts:
`/tmp/gt-backend-task7a-postfix-{targeted,full}/` and `/tmp/gt-backend-task7a-coordinator-spot/`. Copied reports carry the full historical 7a counts; current target is overwritten by later 7b checks. Final read-test hash:
`7872fc4b3e3f2ac4f750abfb3116838621df800bf8c950965f5dda15a5538ba2`. 7a baseline:
`/tmp/gt-backend-task7a-baseline-903cic`. Earlier green pre-fixture-fix runs are historical, not final-state evidence.

The user authorized **starting 7b after finishing 7a with fresh Luna Worker/QA**, briefed on team roles. That boundary
was met and **7b / integrated task 7 are now accepted** (`docs/task-7b-acceptance.md`). Fresh Luna High actors were
role/safety/readability/mcpls-briefed and implemented/independently tested the assigned slice. Final QA passed **144
targeted / 165 full tests** (7 / 10 suites); Coordinator passed **six real HTTP/PostgreSQL spots** and the mandatory
final structural gate. All had zero failures/errors/skips on identical frozen inputs. Final relevant manifests match at
`41b939b4d58fa5bb12a8f584e57bbbb49ecdbca69ac3005b2cdd9b62ced5e646`. Artifacts:
`/tmp/gt-backend-task7b-qa-final-{target,full}/` and `/tmp/gt-backend-task7b-coordinator-spot/`. Current target contains
only the later six-test subset; copied reports preserve full totals. Final browsing-test hash:
`2d0371227412371ddc6cac4c6eecf229f512901e8a16d7623e251dbe5bee1463`. Exact browser suite count is 17; earlier
18 counted @Testcontainers as @Test, not a missing case. Route-specific large/empty paging permutations remain explicit
nonblocking coverage gaps, not changed behavior.

7b baseline: `/tmp/gt-backend-task7b-baseline-w7uYPj`; both slice contracts were defined upfront in
`docs/task-7-contract.md`, execution recorded in `docs/task-7b-contract.md`, review in `docs/task-7b-review.md`.
Task 7 acceptance is bounded to individual reads and browsing/pagination, not complete-v1/deployment. At that checkpoint Worker and QA awaited assignment; **the user has now authorized 8a only** under `docs/task-8-contract.md`. Remaining roadmap: 8 pull/pruning/snapshots;
9 account lifecycle; 10 broader API/operations/deployment. Dedicated legacy refactoring remains unauthorized.

Worker owns production; QA owns requirement-first tests and independent verification; Coordinator owns contracts,
integration/findings, structural review, acceptance and status docs, not production/test authoring. Serialize every
Maven operation through `/tmp/gt-backend-task7-build.lock` AND freeze relevant inputs. No report line quota or mandatory
matrix format; concise readable evidence/coverage/gaps suffice. Root README mcpls preference applies; both old agents
used it and reported intermittent initialization/non-project diagnostics, using read-only fallback without changing
tooling. The fresh-context permission is narrow and exercised at this boundary, not ongoing authority to reset agents.

## Authority, repository and durable reports

- Cwd: `/home/dragos/IdeaProjects/gt-backend`. Java 27, Spring Boot 4.1.1, Maven wrapper, PostgreSQL/Flyway; package
  `com.dragosfotache.gtbackend`.
- Normative behavior: `shared-docs/docs/backend-contract/README.md` and linked documents/OpenAPI. Local notes do not
  override it; resolve mismatches explicitly. No Android source is needed to interpret these contracts.
- Read `README.md`, `docs/task-6-contract.md`, `docs/task-6-acceptance.md`, `docs/task-6-worker.md`,
  `docs/task-6-qa.md`, `docs/resource-mutations-implementation.md`, `docs/sync-push-implementation.md`, and
  `docs/technical-debt.md` as relevant.
- `shared-docs/` is an independent checkout, never a backend submodule, at HEAD
  `3aefeb3e2eaa147793d595833217d6070eeabff2`. At 7a start it has pre-existing working-tree changes (including contract
  wrapping/table formatting; OpenAPI diff is whitespace-only), unlike the historical clean task-6 checkout. Preserve
  these supplied files/Git state; do not reset/edit them. The build uses the pinned catalogue seed/notices.
- Backend still has no tracked files/commits/remotes or staging. Files are untracked; an empty git diff is not evidence.
  Use baseline-relative comparisons and hashes. Task-6 baseline: `/tmp/gt-backend-task6-baseline-SjcEZw`; pre-5g
  baseline: `/tmp/gt-backend-5g-baseline-BUCeOL`.

## Accepted task-6 implementation

- `ResourceController` exposes the 14 exercise/routine/workout mutations. Success is a canonical resource (201/200), or
  a bodyless 204 DELETE response. Creation/editing have explicit distinct existence policies; copy sources are
  path-only. No new GET/list, child or import endpoint was added.
- `ResourcePreflight.prepare` uses named route-resolution, body-validation, command-adaptation and shared
  structural-validation stages. UUID validation and custom-exercise field whitelists are shared locally. Strict original
  UTF-8 bytes are retained independently of the internal command envelope.
- `ResourceMutationExecutor` owns account/key replay, direct route policy, HTTP response mapping and evidence
  persistence in one live-session/account-fenced transaction. Identity is method + normalized concrete route + exact
  original body bytes. DELETE accepts only zero-byte/absent body; a nonempty body is unsupported, returns 422, and
  claims no key.
- Original status/body bytes, including domain rejections, are retained for the account lifetime independently of
  sync-log retention. Identical retries recheck live authorization/preflight, then replay before current eligibility.
  Changed identity returns 409 `idempotency_key_reused` without replacing evidence. Resource, sync and auth namespaces
  remain separate; switching surfaces never recovers an uncertain operation.
- Forward-only V6 adds `resource_mutations`. No synthetic sync evidence is created by resource calls. Domain rows,
  revisions, reservations, cursor, complete ordered batch and original response commit together.
- The sync wrapper owns sync replay/evidence. Shared domain application/publication returns explicit
  `DomainCommandResult.Accepted` or `.Rejected`; no nullable resource mode remains in the legacy command body. Its
  17-command algorithms, SQL, savepoints, domain classification and canonical publication were preserved.
- F6.1 (nested transient publication failure returned 500 rather than 503) was fixed and independently verified with
  rollback/unchanged retry. F6.2 (mixed long resource preflight/replay responsibilities and nullable mode coupling) was
  resolved by a bounded local cleanup before acceptance.

## Final verification and frozen inputs

All relevant inputs were frozen and Maven operations serialized through `/tmp/gt-backend-task6-build.lock`.

- Worker build-only package passed; post-cleanup resource/sync/preflight self-check passed **104 tests**, zero
  failures/errors/skips.
- Independent QA post-cleanup five-suite target passed **116 tests**; full `./mvnw -q test` passed **137 tests across 8
  suites**, zero failures/errors/skips. Direct resource suite: 15; sync HTTP: 65; preflight: 24; auth HTTP: 11; Google
  verifier: 1; catalogue: 7; account repository: 7; canonical aggregate repository: 7.
- Coordinator reviewed final baseline-relative scope and AC6.1–6.7, independently validated fresh XML totals, and passed
  **five real HTTP/PostgreSQL spot tests** on the identical final inputs.
- Final QA manifests `/tmp/gt-backend-task6-qa-postcleanup-{before,after}-{targeted,full}.sha256` all hash to
  `5efdeb16cd7cd45049201da37a1016560cb0ddba8b3a34170fa04c1e89c9219f`. Coordinator
  `/tmp/gt-backend-task6-coordinator-{before,after}.sha256` match each other and final QA inputs.
- Fresh reports: `/tmp/gt-backend-task6-qa-postcleanup-targeted-reports/`,
  `/tmp/gt-backend-task6-qa-postcleanup-full-reports/`, `/tmp/gt-backend-task6-coordinator-spot-reports/`. Exact
  commands/logs are in acceptance and QA reports. Current `target/surefire-reports/` includes the later five-test
  resource subset; copied reports carry the full totals.
- Earlier green pre-cleanup results are historical, not final-state evidence. The original 113-test run had the
  confirmed F6.1 failure; later QA compiler/fixture/helper corrections did not relax requirements or change production
  policy.

Historical accepted task-6 SHA-256 (several current files differ; do not present these as fresh 7a inputs):

| File                                   | Hash                                                               |
|----------------------------------------|--------------------------------------------------------------------|
| ResourceController.java                | `56ba0bb1793fd1aa292ef2078c27de8fe6ca6670192fd2b24ee6c126ff33cc04` |
| ResourcePreflight.java                 | `d811dc559e6aef8d423b154b7d2d2a40a796ab0439c74888ca553e82ed72c8ed` |
| ResourceMutationExecutor.java          | `d8e68e185e9aa4c5e992a434ffea0af15743337a7451951cc80745038300af0e` |
| RoutineUpsertExecutor.java             | `23c39e2bca1bf6f2017a7827fce9d80156a2e2a64a4c85db0a80cdb981c02425` |
| PushPreflight.java                     | `8595acb0c7f5dea32219d8c888a7df65aab1b27974193293e1d4c70d7a896a8d` |
| V6__resource_idempotency.sql           | `a7fd5660687cf4b3c5fd5f6d72ba2f037e923f434b94cbaee86db38063f38792` |
| DirectResourceHttpIntegrationTest.java | `b77a4b36790d9b491f10cc4a0cec6bd77310098b0f19433b56e74a9c937880e5` |

## Maintainability, limits and history

Maintainability/readability are mandatory acceptance criteria alongside function and contract compliance. Delegate small
cohesive methods, explicit results, meaningful names, practical responsibility separation and removal of obvious
duplication. Avoid growing complex methods with additional command checks, mode flags, nullable coordination or deep
nesting. Avoid speculative handler frameworks. Preserve behavior/tests. Review structure explicitly; return reasonably
local deterioration for cleanup before acceptance. Use concise, normal grammatical English in every handoff.

`RoutineUpsertExecutor.applyCommand` remains legacy debt with mixed eligibility, transformation, SQL and event
construction. Task 6 did not broadly redesign it or extend its mode flags. See `docs/technical-debt.md` for a separately
approved refactoring task; no such work is authorized merely by recording it.

Coverage is representative, not every state/fault/route combination. Direct evidence and shared-engine supplements are
distinguished in QA's matrix. No blocking finding or unmet bounded criterion remains. Auth-cleanup/Hikari connection
errors after stopped Testcontainers remain a recorded nonblocking lifecycle anomaly, not fixed in this task.
Deployment/HTTPS/release OAuth/Android integration and later API surfaces remain unverified.

Task 5g history: `docs/task-5g-acceptance.md` records bounded acceptance, independent 89 targeted/122 full tests and
five Coordinator spots. Its unknown integrity/replay poisoning and root-versus-child classifier fixes remain preserved.
The separate routine-root-versus-child collision permutation is a recorded nonblocking coverage gap, not a behavioral
waiver. Historical executor hash `02b9018484ff6b4b6811817195a2f26ace7c4cc1c599f413db01bf5d90399a95` is not current; sync
HTTP test hash `b90e370f1f8066c63543da4fbaf98659a6bd7136346bac00c1125f2cfddc01ea` remains unchanged.

## Protected resources and team

- Applied V1–V5 are immutable; future schema changes are forward-only. V6 was exercised only in isolated PostgreSQL 16
  test databases, not applied to preserved local `gymtracka`. Local schema/data and existing services were not reset or
  destructively tested.
- Never read, source, print, copy, rotate or expose `~/.config/gymtracka-backend/dev.env` or secrets. No local secrets
  were accessed for these checks. No staging/commit, deployment, submodule conversion or layout change occurred.
- Coordinator owns scope/contracts/integration/findings/acceptance/status docs, not production or test authoring. Worker
  owns production; QA owns tests and independent checks. Allow disjoint concurrent edits when safe; freeze relevant
  inputs for verification in addition to serializing Maven.
- Herdr: `HERDR_ENV=1`, workspace `w4`, tab `w4:t1`; Coordinator `w4:p7`, Worker `backend_worker` / `w4:pD` (session `gt-backend-worker-task8b`), QA `backend_qa` / `w4:pE` (session `gt-backend-qa-task8b`). Both are openai-codex gpt-6-luna High pi processes, fresh for authorized 8b. Completed idle 8a processes exited via native /quit; foreground zsh/cwd availability was verified before starting 8b without creating/moving/closing panes. Historical task7/8a/pC/pA actor identifiers are not current.
- Preserve panes/cwd/layout. Read the Herdr skill, verify environment and rediscover live IDs before control. Idle agent panes are not free shells. 8b authorization is now exercised; future authorized 8c uses another fresh pair per the user's three-slice plan, not continuing permission to start 8c or terminate unrelated agents/services. Do not manage context or compaction.
