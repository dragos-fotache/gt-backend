# Task 8c — Worker implementation handoff

## Scope and status

The approved production implementation for Task 8c is in place. The independent QA baseline closed before authoring and passed 188 tests across 15 suites, with no failures, errors, or skipped tests. The user approved a narrow clarification to the shared retention and service-protocol documents after that baseline.

The Worker changed only `src/main/**` and this handoff document. QA owns `src/test/**` and `docs/task-8c-qa.md`. No tests, Coordinator documents, shared normative documents, build files, or dependencies were changed by the Worker. Applied Flyway migrations V1–V6 remain unchanged.

## Production changes

- `src/main/resources/db/migration/V7__durable_sync_snapshots.sql` creates account-owned snapshot headers and ordered frozen canonical records. Composite account/snapshot keys and cascading foreign keys support later account purge. Each header's baseline cursor is constrained to a token registered for the same account. Constraints cover lifetime, nonnegative wide counts and ordinals, allowed aggregate types, required payload fields, and envelope/record identity and revision agreement. Snapshot root identities are unique by type and UUID, and an expiry index supports cleanup. An additional composite cursor-token index supports the baseline foreign key without changing existing cursor behavior.
- `src/main/java/com/dragosfotache/gtbackend/sync/SyncSnapshotController.java` implements query and request-body validation and maps failures to safe response codes. It accepts limits from 1 through 500, defaults to 200, treats an empty continuation token as invalid, and requires live bearer authorization on every request.
- `SnapshotPageTokenService.java` creates deterministic, versioned HMAC tokens using a domain-separated key derived through the existing `AuthCrypto`. Tokens bind the account, snapshot, long-valued offset, effective limit, and fixed expiry. A matching authentic token remains distinguishable as expired after its payload has been cleaned up. Existing authentication, list, and pull token formats are unchanged.
- `SyncSnapshotService.java` captures and reads snapshots in repeatable-read transactions. Capture combines live authorization, committed-boundary lookup and cursor registration, canonical aggregate and label mapping, durable record insertion, and first-page construction. Root IDs are fetched in chunks of 100 and ordered by canonical UUID text under the C collation. Each complete record is serialized and inserted as it is produced, rather than retaining all account payloads in one Java collection. The whole capture transaction retries up to four times only for cursor-registration or serialization conflicts. Continuations read immutable stored records without row locks, verify the expected contiguous rows, and reproduce the same page and next token on retry.
- `SyncSnapshotRepository.java`, `SyncSnapshotCleanupService.java`, and `SyncSnapshotCleanupScheduler.java` provide bounded persistence and scheduled cleanup. Each invocation selects and deletes at most 100 expired snapshot headers using `SKIP LOCKED`; the associated frozen records are removed by transactional cascade. The scheduler logs only a fixed, sanitized failure message and can retry on a later tick.
- `src/main/resources/application.yaml` adds only the approved `sync.snapshot.cleanup` settings. Cleanup is enabled by default, with initial and fixed delays of 60,000 milliseconds. The internal work budget remains 100 snapshot headers per invocation.

Capture samples the injected service clock once after the authorization and sync-state reads establish the consistent transaction view. It stores creation time at PostgreSQL microsecond precision and sets expiry exactly 86,400 seconds later. After materialization and first-page construction, capture checks expiry inside the transaction; if expiry has passed, the transaction rolls back both the snapshot and any newly registered baseline cursor. It checks again after commit before returning the response. Continuations check expiry inside their read transaction and again before returning. Capture adds no explicit account-row lock, and continuation reads add no row locks. However, capture takes implicit foreign-key key-share locks on the account row when it registers a cursor token and inserts its snapshot header. Those locks remain until transaction completion and can make the existing account `FOR UPDATE` writer boundary wait for a long capture. Snapshot contents are immutable and transactionally committed with the baseline; continuation reads remain compatible with cleanup through repeatable-read MVCC visibility.

## Review corrections

The Coordinator identified three bounded findings during the preliminary review. The capture transaction now has the required pre-commit expiry check, while retaining the post-commit response check. The token service rejects any encoded token whose length differs from its fixed format before Base64 decoding or allocation; it then checks canonical encoding, decoded length, MAC, version, and request context. The V7 payload constraint explicitly requires its JSON keys and types and wraps the predicate in `COALESCE(..., false)`, so missing fields cannot pass through SQL `NULL`. It also validates a positive signed 64-bit revision and matching envelope and record revisions. None of these changes modified V1–V6.

## Retention and resource considerations

Snapshot access ends exactly 24 hours after creation. Scheduled cleanup removes expired payloads in bounded batches, but the 100-header budget does not bound payload bytes, cascade duration, or physical-erasure lag. Backlog, downtime, and exceptionally large snapshots may delay physical deletion. A single large snapshot cascade may be expensive. This follows the expressly clarified policy; it is not evidence of a physical-erasure deadline. No aggregate-size limit, byte limit, or truncation was introduced.

## Build evidence and handoff

The approved build-only command was:

```text
flock /tmp/gt-backend-task7-build.lock ./mvnw -q -Dmaven.test.skip=true package
```

It exited successfully. Tests and test compilation were not run, and this result does not establish behavioral acceptance. The command, log, exit code, package artifact, and input manifests were copied to `/tmp/gt-backend-task8c-worker-package-MdLt76/`. The before-and-after manifest hashes matched at `7b62521a7ec3acba6c020c44a5079886a7947700e10bca8561610d22a4166b71`. That manifest includes the README version present during the build slot; Coordinator subsequently made a status-only wording correction to `README.md`, so the recorded hash is historical evidence and does not describe the current README contents. The package slot is closed. V1–V6 were separately checked against the starting baseline and match.

The dedicated target reported F8c.4: after four exhausted SQLSTATE 40001 retries, `SyncSnapshotService` raises `TemporarilyUnavailable`, which now has an explicit HTTP 503 `temporarily_unavailable` mapping in `SyncSnapshotController`. Retry count, transaction boundaries, and rollback behavior are unchanged. The subsequent dedicated snapshot target and both Worker behavioral self-checks passed with this mapping in place. Independent final QA later passed the dedicated, integrated, and full-suite runs. Structural review and Coordinator spots remain pending; these results do not constitute acceptance.

The reported F8c.5 gate is a fixture-imposed circular wait, not evidence of a hybrid capture defect. Cursor registration inserts into `sync_cursor_tokens`, whose account foreign key takes an implicit key-share lock; the snapshot header also references the account. The test pauses capture while those locks are held, then synchronously waits for a writer that calls the established account `SELECT ... FOR UPDATE` boundary. That writer cannot pass the conflicting key-share lock until capture commits, but capture cannot commit until the test releases its pause. The fixture should release capture after observing that the writer has reached the lock wait, then await the writer and verify the captured view and subsequent pull boundary. It must not wait for writer completion while capture remains paused. These foreign-key locks are real transaction participation and can delay a same-account write for the duration of a large capture. No production change was made for this test coordination issue; authorization fences and account-bound foreign keys remain intact.

## Behavioral self-check evidence

After F8c.4 was corrected, the Worker ran the Coordinator-approved selected regression command:

```text
flock /tmp/gt-backend-task7-build.lock ./mvnw -q '-Dtest=SyncSnapshot*Test,SyncPull*Test,SyncHistoryPruning*Test,RoutineUpsertHttpIntegrationTest,PushPreflightTest,DirectResourceHttpIntegrationTest' test
```

It exited with code 0. The 11 selected suites reported 147 tests, with zero failures, errors, or skips. The exact command, complete log, exit code, XML summary, and fresh reports for only those selected suites are in `/tmp/gt-backend-task8c-worker-selfcheck-TyfGnw/`. Its 107-path before-and-after input manifests match at SHA-256 `536478f0881b4bf62e140bd3fa9a57132c648b2af2c0a18244ac4850c4985609`. This is bounded behavioral self-check evidence, not final QA or acceptance.

The later Worker self-check used `/tmp/gt-backend-task8c-qa-followup-full-nKAbZq/inputs-after.sha256`, the final 107-path manifest with SHA-256 `440efc494f7fffda03e74c6ef304fc27052a78d81a910e50bb0001974817a148`. The same selected regression command exited 0 with 147 tests across 11 suites and zero failures, errors, or skips. Its exact command, log, exit code, XML summary, selected-suite reports, and identical before/after manifests are in `/tmp/gt-backend-task8c-worker-final-selfcheck-PVusxP/`. This is later evidence; the earlier self-check directory above remains preserved as historical evidence.

The Worker has not accessed the local database or secrets. No further Maven commands or production edits are planned. Production inputs remain frozen while Coordinator risk-based checks proceed. Independent QA passed its dedicated target and integrated/full suite, but Task 8c is not accepted; structural review and Coordinator spots remain outstanding.
