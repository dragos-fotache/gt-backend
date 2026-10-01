# Task 7b — Coordinator review history

**Final status: accepted.** Integrated task-7 acceptance, final structural gate, independent 144-targeted / 165-full
checks and six Coordinator HTTP/PostgreSQL spots are recorded in `docs/task-7b-acceptance.md`. Final manifests match at
`41b939b4d58fa5bb12a8f584e57bbbb49ecdbca69ac3005b2cdd9b62ced5e646`; no blocking finding remains. Sections below
record review history, not open work or verification permission.

## Approach approval and preliminary structure

Approved one shared purpose-separated HMAC/context-digest binary token service, injected UTC pagination Clock, normalized
query values, native UUID/timestamp keyset predicates and Java ROOT literal matching over internal candidate chunks.
The candidate scan is not a result cap; only returned matches are fully materialized, with last RETURNED key in the
continuation and a real extra matching record determining hasMore. Each page shares one live authorized read-only
repeatable-read view, without stored/frozen list sessions. Existing AuthCrypto public digest supplies a stable
purpose-specific signing key without changing auth crypto/configuration. Narrow Clock wiring is approved.

Production remains separate: HTTP validation/response, browse transaction/orchestration, dedicated query repository,
normalization, sole token primitive and pure canonical mapping/summary projection. No mutation god-method growth,
new schema/dependency, ephemeral key, secret-file access or product cap. Final AC7b.5 structural sign-off requires the
verified final state, not this preliminary review or compilation alone.

## Early draft corrections

- Token validation initially returned only Anchor, discarding original expiry. Worker changed it to explicit
  Continuation(anchor,expiresAt), preserving expiry for every reissued token.
- Empty/absent q initially used null while context writing assumed a string. Worker unified it to empty-string ROOT
  normalization and literal matching, eliminating scattered nullable coordination.
- Token decoder now validates the fixed v1 token/payload shape; context comparison and signature verification precede
  expiry classification. This grammar check is not a semantic filter/name length cap.
- QA initially searched for jdtls tool names. Corrected: mcpls is the MCP namespace; jdtls is its backend process.
  Actual mcpls calls reported initialization/non-project diagnostics; read-only fallback is allowed. No tooling changes.

## QA draft fixture/oracle review

Coordinator requested correction of the transcribed Middle Back UUID (97d4, not 97d2), repeated q on keyset
continuations, releasing the test-owned port reservation before a child JVM binds, and ensuring membership assertions
respect page-size boundaries. These are QA authoring corrections, not relaxed requirements or production findings.
Pagination Clock mocking is the approved narrow service-time seam; auth clocks must remain unchanged.

Final verification will retain fresh reports/manifests and explicit direct evidence, supplements and representative gaps.
