# Technical debt requiring a separately approved task

## Legacy mutation command orchestration

`src/main/java/com/dragosfotache/gtbackend/sync/RoutineUpsertExecutor.java`, specifically `applyCommand(...)`, remains a
large legacy method coordinating 17 mutation kinds through command checks, booleans, nullable domain objects, a shared
rejection code, inline eligibility SQL, savepoints, persistence and canonical batch construction.

Task 6 extracted only the boundary needed by direct resources: an explicit accepted/rejected result, separate sync
replay ownership, and a focused resource replay executor. It did not redesign the existing command algorithms or extend
their mode flags. This debt is acknowledged, not silently accepted as the preferred structure for future features.

A dedicated refactoring task should separate meaningful command operations and their validation, transformation,
persistence and response/event construction where practical. Prefer simple named methods and explicit results over a
speculative handler framework. Preserve all 17 commands, exact-byte replay identities, rejection/error classification,
live-session/account fencing, savepoint rollback, root/child reservations, revisions, ordered complete batches and
cursor visibility.

Before that work, establish a stable baseline and contract-derived regression matrix; preserve current HTTP/PostgreSQL,
fault, concurrency and real-process replay tests. Verification must use frozen inputs and independent QA. No API/schema
change or product behavior change is implied.

**Status:** recorded only. This entry does not authorize or schedule refactoring, and it is not part of task 7.
