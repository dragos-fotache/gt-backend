# Task 7a — Coordinator review and findings

**Status: 7a accepted.** Final acceptance, structural sign-off, post-fixture-fix 127-targeted / 148-full tests,
Coordinator five HTTP/PostgreSQL spots and limitations are in `docs/task-7a-acceptance.md`. All final verification
manifests match at `9dd8ca3ba78174a2ea2c97e3b7ea385b2e9545e30ba838a957469c7abacff17b`. The remaining sections
record review history, not open permission or unverified final-state claims. No blocking finding remains.

## Production handoff checkpoint

Worker's frozen `flock /tmp/gt-backend-task7-build.lock ./mvnw -q -Dmaven.test.skip=true package` passed; no tests
compiled/executed. Production/resources/build/shared-docs before/after manifests match at
`20331f2a09741291d342aedeb459a5eeafcbffcc7ec0e2d0fc93190313a0c6d2`; log and exact inputs are recorded in
`docs/task-7a-worker.md`. Coordinator independently compared manifests and baseline-relative production scope.

The seven changed/added production files are limited to read controller/service, pure canonical mapper, additive
non-locking authorization, resolved exercise values/loading, and bounded mutation-publisher mapper delegation.
Applied migrations/resources, wrapper/build inputs and supplied shared contract/catalogue compare unchanged to the 7a
baseline. Existing write authorization and command algorithms remain unchanged. Locale-independent status conversion
was corrected locally in read parsing/shared mapping; default-English wire semantics are unchanged.

The current production structure passes the preliminary AC7a.6 review: small controller callbacks, explicit
Found/NotFound, an explicit read-only repeatable-read transaction enclosing auth/data/materialization, pure named mapper
helpers, readable ordered exercise queries, shared UUID validation and no new command modes/framework. Final structural
sign-off still depends on the verified final frozen state and disposition of QA findings. Behavior has not yet been
verified; no acceptance from compilation alone.

## Early draft review (not final-state evidence)

- **F7a.1 — initial mapper concern, later corrected.** Draft `ResourceReadController` injected Jackson 2 while Boot 4
  also supplies Jackson 3. Worker chose the Boot-4 Jackson-3 mapper without dependency/configuration changes, and final
  real HTTP verification passed. Coordinator's initial claim that no Jackson-2 bean existed was mistaken:
  `GtBackendApplication.authObjectMapper()` already supplied one in the accepted baseline. This was therefore not a
  confirmed startup defect; the mapper choice is behaviorally verified, and no unresolved finding remains.
- **AC7a.6 bounded readability feedback.** Prefer explicit Found/NotFound over boolean plus nullable response state;
  replace magic string route selection with a simple typed boundary; reuse the existing UUID helper; use cohesive named
  entry/set mapping helpers rather than new nested mapper loops. No handler framework or broad mutation refactoring.
  Worker owns cleanup; final structural review and repeated frozen verification remain required.
- **Read transient classifier review.** Draft classifier recognized SQLSTATE 08/40 and Spring transient exceptions but
  not nested `SQLTransientException` without SQLSTATE, unlike accepted mutation handling. Worker was asked to preserve
  the shared 503 contract for that case. Current draft recognizes SQLTransientException directly; independent
  fault/recovery coverage remains required.

## Clarifications resolved before verification

- The pinned seed has no tertiary muscle relationship (899 exercises, 928 primary and 1710 secondary relationships).
  Contractual tertiary shape/order remains supported, but no nonexistent pinned HTTP example is required or fabricated.
  Assert all actual seed values; report structural/supplementary tertiary evidence separately from its seed coverage gap.
- QA may use test-owned JdbcTemplate/DataSource/JDBC interception to pause after the real root SELECT and before further
  child/relationship queries. Keep real HTTP, Spring transaction and PostgreSQL queries; one-shot target matching,
  deterministic gates, bounded waits and finally-release. No production hook is requested. Test-owned one-shot persistence
  exceptions are allowed for error mapping/recovery; distinguish injection from PostgreSQL-originated faults.
- No fixed report line count or mandatory matrix format; concise readable evidence/coverage/gaps suffice. This does not
  relax functionality or structural acceptance.
- Root README mcpls navigation preference is explicit in the assignment. Both agents have since invoked MCP navigation;
  Worker reported intermittent jdtls diagnostic internal errors and Coordinator saw initialization responses. Read-only
  fallback is permitted for unavailable calls; no tooling/configuration change is authorized.
