# Direct resource mutations — task 6

Behavior and wire shapes remain authoritative in `shared-docs/docs/backend-contract/README.md` and its linked OpenAPI.
The bounded assignment and acceptance record are `docs/task-6-contract.md` and `docs/task-6-acceptance.md`.

## Implemented surface

Fourteen authenticated mutable operations are implemented:

- Exercises: POST collection (201), PUT existing custom exercise (200), DELETE (204).
- Routines: POST empty draft (201), PUT existing draft/saved root without promotion/demotion (200), DELETE (204), save
  (200), start-workout from a saved source (201).
- Workouts: POST start (201), PUT existing active root (200), DELETE (204), finish (200), perform-again from finished
  history (201), save-as-routine (201).

Bodies and concrete routes are listed in the task contract. Successful bodies are canonical resources, not sync
envelopes; DELETE success has no body. Copy source IDs come only from the route and fresh target/child IDs from the
body. POST creation and PUT editing have distinct existence rules; shared sync upsert semantics are not exposed
accidentally. Missing/foreign targets are not disclosed, and catalogue exercises remain read-only.

## Responsibility boundaries

`ResourceController` handles the HTTP surface and sanitized errors. `ResourcePreflight.prepare` orchestrates named
stages: resolve the route, parse the allowed body, validate timestamps, adapt the command, and invoke the existing
strict shape validator. Route, command and response descriptions are explicit. The command envelope produced for
validation is internal: it is neither the resource replay identity nor a persisted sync mutation.

`ResourceMutationExecutor` owns resource replay and response persistence:

1. Start one transaction, require a live session, and acquire the account write lock.
2. Look up the account/key evidence. Compare method, concrete normalized route and original body bytes; replay the
   original status/body or return `idempotency_key_reused` without replacing evidence.
3. For a new operation, apply direct route policy inside that same transaction.
4. Invoke `RoutineUpsertExecutor.executeDomainCommandInAccountWrite`, the internal common domain application/publication
   boundary. Its explicit result is accepted (canonical batch) or rejected (domain code).
5. Map the result to the original HTTP response and persist the exact status/body before committing.

The sync wrapper owns sync replay/evidence separately. Neither caller adds mode flags to the legacy command body. Common
execution is an internal primitive requiring its caller's authorized account transaction, not a standalone public
mutation API or permission to expose the row adapter.

Domain rows, revisions, reservations, account cursor, complete ordered batch and replay evidence commit together.
Recognized domain rejections retain their original HTTP outcome but no domain/log effects. Authentication/structural
failures and unexpected or retryable database failures do not claim a key. Retryable direct failures, including nested
publication failures, use sanitized 503; unexpected integrity faults remain sanitized 500, preserving the task-5g
classification fixes.

## Replay identity and retention

Forward-only V6 creates `resource_mutations`, keyed by authenticated account UUID and `Idempotency-Key`. Identity
includes method, normalized concrete route and **the entire original UTF-8 request body**, not a DTO serialization or
generated command envelope. Whitespace, key order and numeric spelling therefore matter. DELETE accepts only an
absent/zero-byte body; nonempty bodies are unsupported 422 inputs without evidence.

Identical structurally valid retries recheck live authorization and then return stored status/body bytes, even after
later domain changes or process restart. Replays never rematerialize convenience labels from current state. Changed
identity under the same account/key returns 409 and preserves the original evidence. Resource, sync and authentication
namespaces are independent: the same UUID on another surface is a different operation, not recovery of an uncertain
request. Retry an uncertain operation on its original surface with its original identity.

Resource evidence has no TTL and no foreign-key dependency on retained sync batches. It is retained for the account
lifetime, subject to the later whole-account purge implementation. V1–V5 are unchanged; V6 was applied only to isolated
test databases during this task, never the preserved local `gymtracka` database.

## Verification and limits

QA owns `DirectResourceHttpIntegrationTest`: all 14 positive routes plus representative route guards, exact replay,
namespace isolation, concurrency, real-JVM recovery, logout fencing and injected rollback/retry faults. Existing
sync/auth/repository tests supplement shared invariants; they do not pretend to cover every direct-route permutation.
Final commands, counts, input hashes and artifact paths are in the QA and acceptance reports; pre-cleanup results are
distinguished from final-state evidence.

The legacy `applyCommand` orchestrator remains technical debt documented in `docs/technical-debt.md`; no broad redesign
was authorized. Reads/lists, pull/pruning/snapshots, account lifecycle and deployment remain later tasks. No new
mutation kind, child route, import endpoint or product cap was added.
