# Auth expiry precision correction

Status: **ANDROID_INTEROP_GREEN** — backend correction/review/gates/package and
unchanged Android interoperability rerun passed. At the result handoff, the Android
coordinator was finalizing owned cleanup, R03c acceptance and its shared issue update.
This report does not modify the backend's independently pinned shared checkout.

## Scope and authority

The existing shared `client-compatibility.md` requires lossless epoch-millisecond
UTC timestamps. `authentication.md` fixes five-minute challenges, fifteen-minute
access credentials, thirty-day initial/rotated refresh credentials, inclusive
expiry (`now >= expiresAt`) and original-grant replay without extension.
No wire/API or lifetime change is intended. Android must not round reads.

The Android coordinator's shared issue is at
`/home/dragos/AndroidStudioProjects/GymTracka/shared-docs/docs/backend-contract/issues/auth-expiry-precision.md`.
It was read without changing this backend's independent `shared-docs/` checkout
or pinned inputs. Its reported commit is `d5d68d4cfa919839e6f52a590cebda46c31647ae`.

## Diagnosis and design

`AuthService` issues from unquantized `Instant.now()`, returns nanosecond strings,
and persists timestamps in PostgreSQL's microsecond representation. The response
and stored deadline can therefore disagree, and neither need be millisecond-lossless.
`SessionAuthorization` also uses an independent direct wall-clock call.

- Inject the existing service `Clock` into auth issuance and access validation.
- Canonicalize each fresh challenge/grant's issuance instant to milliseconds once;
  derive all its deadlines from that instant and persist/return those same values.
- Preserve full-precision service time for in-memory validation. For SQL cleanup
  only, floor its cutoff to PostgreSQL's microsecond precision before binding it:
  a nanosecond timestamp otherwise rounds upward and can erase a still-valid row.
  Do not round validation time, rewrite database reads, or truncate the global clock.
- Recheck refresh credential expiry after acquiring its session locks, so a wait
  cannot permit rotation at or beyond the persisted/returned deadline. Check logout
  at its serialized point as well.
- Preserve exact body/key identity, original encrypted replay bytes, account identity,
  revocation and transaction/authorization fences. No schema migration is needed.

## Existing-data boundary

This is a prospective issuance correction, not authorization to read a personal
database, purge/revoke credentials, rewrite replay ciphertext, or change existing
stored expiry values. Valid old credentials remain governed by their existing
persisted deadlines; a successful fresh rotation issues a corrected new grant.

An incompatible pre-fix replay cannot simultaneously preserve the original exact
result and become a compatible timestamp. Such retained results remain unchanged,
with their original liveness/current-refresh/expiry gates. Their incompatibility is
an explicit legacy exception, not a claim that every historical replay is repaired.
Any historical-data transformation requires a separately agreed recovery policy;
no silent rewrite, forced reauthentication, or blanket revocation is included here.

## Team and verification boundary

Worker owns `AuthService.java`, `SessionAuthorization.java`, and the existing
manual auth-service construction adjustment. QA independently authors focused
new auth tests, using deterministic nonzero sub-millisecond clocks, isolated
PostgreSQL 16, provider test seams and actual child-process restart. Coordinator
reviews implementation, tests and legacy policy independently.

Relevant source/build/shared inputs must be frozen during each verification slot.
Every Maven operation uses `/tmp/gt-backend-task7-build.lock`. Record actual
commands/results, preserve failures and do not use live personal configuration,
databases, Google OAuth or Android code as a test oracle.

Required backend checks include all challenge purposes/default, exchange/refresh
precision and stored deadline equality, exact original replay across restart and
expired-access/current-refresh recovery, immediately-before/equal expiry boundaries,
invalid refresh/logout, identity conflict/revocation regression and legacy preservation.
## Execution evidence

First frozen focused gate:

```bash
flock /tmp/gt-backend-task7-build.lock ./mvnw -q \
  -Dtest=AuthChallengePrecisionHttpIntegrationTest,AuthGrantPrecisionHttpIntegrationTest,AuthPrecisionRestartHttpIntegrationTest test
```

19 tests / 3 suites: 16 passed, 3 failed, no errors/skips. The challenge cleanup
case proved real early deletion at expiry minus 1 ns due to SQL cutoff rounding;
its correction floors the cutoff to database precision, not milliseconds. The
grant cleanup case also exposed an incorrect fixture count (expired access must be
removed while current refresh/replay remain). The restart fixture incorrectly
expected replay of a superseded exchange grant; the contract requires `401` there.
QA must correct these fixture expectations without relaxing the contract.

Original reports are preserved at
`/tmp/gt-backend-auth-coordinator/first-gate-reports/`; the frozen source/build
manifest at `/tmp/gt-backend-auth-coordinator/first-gate-inputs.sha256` passed its
post-run check.

Corrected focused gate: **19/19 passed** (challenge 3, grant 14, actual-process
restart 2), no failures/errors/skips. Evidence:
`/tmp/auth-precision-second-20261004-113353-39239/`.

First full gate (`flock /tmp/gt-backend-task7-build.lock ./mvnw -q test`):
344 tests / 44 suites, 331 passed, 13 failed, no errors/skips. All new auth cases
passed. Failures were confined to existing browsing (1), account status (1),
history pruning (8) and snapshot lifecycle (3) fixtures. Their synthetic clocks
previously bypassed auth's independent wall clock; fixture credentials were issued
against real database time or a purported post-deletion attempt never advanced
its fixed clock. Fixture corrections must keep access valid for the scenario,
explicitly advance post-deletion signup time, retain all assertions, and leave
production lifetimes/authorization/deletion fences intact. Full reports and the
137/137 passing frozen-input check are preserved in that evidence directory.

Final independent fixture review passed: timing/isolation only, no assertions
removed or relaxed. Corrected affected-plus-auth gate: **68/68 passed / 7 suites**
(browsing 17, status 9, history pruning 10, snapshot lifecycle 13, challenge 3,
grant 14, restart 2), no failures/errors/skips. Final full gate:
**344/344 passed / 44 suites**, no failures/errors/skips. Coordinator independently
recounted both saved report sets. Evidence:
`/tmp/auth-precision-final-20261004-115146-90131/` (logs, both report sets and
137/137 passing pre/post frozen-input checks). Earlier failures remain preserved.

Coordinator package gate:

```bash
flock /tmp/gt-backend-task7-build.lock ./mvnw -q -DskipTests package
```

Exit 0; log `/tmp/gt-backend-auth-coordinator/package.log`. Source/build manifest
and pinned shared inputs also passed independent post-test/post-package checks;
`git diff --check` passed. Artifact:

- `target/gtbackend-0.1.0-SNAPSHOT.jar`
- SHA-256: `41687b8ca223991aed4f6a88ee7cf8cb360ea2d2865cfcd867e157083489a369`
- Both corrected main auth classes present; no new test-only provider/clock seams
  or precision test/support classes packaged.

## Changed-file and handoff scope

- Production: `auth/AuthService.java`, `auth/SessionAuthorization.java`.
- Existing tests: auth HTTP constructor adaptation plus account status, browsing,
  history pruning and snapshot lifecycle fixture timing corrections (five files).
- New tests/support: three precision suites, shared sanitized assertions, test-only
  child provider and clock configuration (six files under `src/test/java/.../auth`).
- Local report: this file. Applied V1–V9, shared checkout and pins unchanged.

All changes remain unstaged/uncommitted. No deployment, personal configuration or
database access, live Google authentication, service restart, commit or push was
performed. Java 27 and isolated PostgreSQL 16 Testcontainers were used for backend
checks. Existing deferred purge/privacy/restore gaps are outside this correction.

Worker and QA hold with relevant source/build/shared inputs frozen. Backend test
and package slots are closed; the Maven lock was independently probed available.
The Android coordinator owns the next unchanged guarded disposable interop run;
any Maven operation must still acquire the same lock. No concurrent backend edits
or builds are authorized during that run. The verified artifact is supplied for
isolated interoperability, not live deployment.

Fresh/default/all-purpose challenges, fresh exchange/rotation and replay of
corrected grants are covered. **Incompatible historical accepted replay remains
unchanged and incompatible** as documented above; clients must not round it,
invent a new key for an uncertain consumed-token attempt, or infer cache/data
deletion. Neither backend-local green nor the readiness signal automatically closes the
shared issue or accepts Android R03c. The independent rerun result is recorded below;
formal Android acceptance/shared issue updates remain owned by its coordinator.

## Independent Android rerun result

The Android coordinator reported **ANDROID_INTEROP_GREEN** on 2026-10-04:

- Independently verified the same packaged JAR SHA-256 recorded above.
- Started its existing guarded disposable PostgreSQL 16 launcher with `--no-build`:
  no Maven operation, backend source/pin edits or private configuration access.
- **Unchanged focused auth: 10/10 passed.**
- **Unchanged broad interoperability: 56/56 passed / 10 suites**, including all
  46 original sync cases; no failures/errors/skips in either run.
- Sanitized precision diagnostic: `challenge=true, access=true, refresh=true`.
- Its 201/201 Android code/build inputs and the reviewed JAR hash stayed unchanged.
- No client precision relaxation or assertion change. Original incompatible legacy
  replay is still explicitly preserved and is not counted as corrected.

Evidence supplied by that coordinator: `/tmp/gymtracka-r03c-fixed-auth.log`,
`/tmp/gymtracka-r03c-fixed-broad.log`, `/tmp/gymtracka-r03c-fixed-launcher.log`, and
Android-owned XML results at `app/build/test-results/testRealBackend` in its repo.
These are independently executed client results, not a backend-agent Android run.
No backend source/build changes are needed for this rerun. Backend Worker/QA remain
holding; no deployment, live personal-service changes, commit or push is authorized.
Real Google OAuth, deployed TLS and device/Room convergence remain separate gates.

Backend green is not Android acceptance, real Google verification, deployment or
live TLS verification.
