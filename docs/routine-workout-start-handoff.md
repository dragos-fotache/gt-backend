# Android handoff for routine-based workout starts

The backend implements `workout.startFromSnapshot` on `POST /v1/sync/push`. Use it for new account-owned routine
starts after the target origin supports it. Keep sending older queued `routine.startWorkout` entries with their
original mutation IDs and bytes. The backend retains the legacy source-binding validation.

## Source and build

| Checkout | Revision |
|---|---|
| `/home/dragos/IdeaProjects/gt-backend` | Backend commit containing this handoff |
| `/home/dragos/IdeaProjects/gt-backend/shared-docs` | `f09a4d7c945f0e07a382fb15bc4ecd62316c011a` |

Use the finalized contract and [Android implementation plan](../shared-docs/docs/backend-contract/issues/routine-workout-start/android.md)
from the shared-docs revision above. Bring that revision into the independent checkout at
`/home/dragos/AndroidStudioProjects/GymTracka/shared-docs` for Android implementation.

Build from the backend root with Java 27.

```bash
sha256sum -c docs/shared-docs-inputs.sha256
flock /tmp/gt-backend-task7-build.lock ./mvnw -q -DskipTests package
```

The package build passed. The runnable artifact is `target/gtbackend-0.1.0-SNAPSHOT.jar`.
Its SHA-256 is `0648b4d487608d8f3dff7b28695878a2d52927ca7f55dcad0af2474d0f3e860e`.

## Contract decisions

- The envelope targets the new workout. The starting `WorkoutInput` goes directly in `payload` and preserves
  the phone's workout ID, child IDs and values. The service derives exercise labels from visible exercise references.
- Structural preflight requires a nonblank name, at least one exercise and consecutive zero-based child positions.
  It accepts zero-set exercises and omitted or explicit null initial set values. Initial sets are incomplete and
  have null or omitted recorded rest. The name uses the same whitespace rules as a routine name and is stored as supplied.
- Snapshot starts require fresh IDs under the existing workout reservations. An unavailable exercise yields
  `invalid_reference` and a reserved identity yields `id_collision`. Failed replacements preserve the active
  workout and cursor. A matching retry returns the stored original batch or error with `status: duplicate`.

## Verification

The HTTP and restart tests use disposable Testcontainers PostgreSQL 16. The new regression exercises a server-side
routine edit after capture, legacy source-binding failure, a snapshot start and pull, later upsert and finish, whole
push preflight, missing and foreign references, reservations, concurrent starts, and exact-byte replay after an
HTTP process restart.

| Command | Result |
|---|---|
| `flock /tmp/gt-backend-task7-build.lock ./mvnw -q -Dtest=PushPreflightTest test` | 43 passed |
| `flock /tmp/gt-backend-task7-build.lock ./mvnw -q '-Dtest=RoutineUpsertHttpIntegrationTest#snapshotStart*' test` | 5 passed, including exact replay across an HTTP process restart |
| `flock /tmp/gt-backend-task7-build.lock ./mvnw -q test` | 369 passed, including all 70 sync-push HTTP cases |
| `python3 -m unittest discover -s tests` | 18 passed |
| `sha256sum -c docs/shared-docs-inputs.sha256` | 28 inputs matched |
| `git diff --check` and `git -C shared-docs diff --check` | Passed |

The OpenAPI push schema accepted the request and canonical response below and rejected 11 invalid envelope, payload,
exercise and set variants. The packaged JAR accepted the exact example request against a fresh disposable PostgreSQL
database. Its response matched the example apart from the generated batch ID and cursor. Pull returned that same
batch as the only change after the setup cursor.

## Synthetic replacement

Use a disposable account and wait for the service to finish installing the pinned catalogue.
Create an empty active workout with ID
`b5c6e800-1c4c-4ace-8000-000000000001` at revision 1. Send the following JSON to `POST /v1/sync/push`
with the account's bearer token and `Content-Type: application/json`.

```json
{
  "mutations": [{
    "mutationId": "b5c6e800-1c4c-4ace-8000-000000000010",
    "type": "workout.startFromSnapshot",
    "aggregateType": "workout",
    "aggregateId": "b5c6e800-1c4c-4ace-8000-000000000002",
    "localGeneration": 1,
    "payload": {
      "id": "b5c6e800-1c4c-4ace-8000-000000000002",
      "name": "Offline strength",
      "status": "active",
      "startedAt": "2025-01-01T06:07:08.123Z",
      "exercises": [{
        "id": "b5c6e800-1c4c-4ace-8000-000000000003",
        "exerciseId": "4aba0e31-4c5b-45d3-b32b-1bc3480b06c7",
        "position": 0,
        "notes": "Start note",
        "restDurationSeconds": 75,
        "sets": [{
          "id": "b5c6e800-1c4c-4ace-8000-000000000004",
          "position": 0,
          "repetitions": null,
          "weightKg": 0.0,
          "isCompleted": false
        }]
      }, {
        "id": "b5c6e800-1c4c-4ace-8000-000000000005",
        "exerciseId": "4aba0e31-4c5b-45d3-b32b-1bc3480b06c7",
        "position": 1,
        "notes": "",
        "restDurationSeconds": 0,
        "sets": []
      }]
    }
  }]
}
```

Expected HTTP 200 response. The service generates the batch ID and opaque cursor, shown here as example values.

```json
{
  "results": [{
    "mutationId": "b5c6e800-1c4c-4ace-8000-000000000010",
    "status": "accepted",
    "errorCode": null,
    "batch": {
      "batchId": "b5c6e800-1c4c-4ace-8000-000000000011",
      "cursor": "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
      "changes": [{
        "aggregateType": "workout",
        "aggregateId": "b5c6e800-1c4c-4ace-8000-000000000001",
        "operation": "delete",
        "revision": 2
      }, {
        "aggregateType": "workout",
        "aggregateId": "b5c6e800-1c4c-4ace-8000-000000000002",
        "operation": "upsert",
        "revision": 1,
        "record": {
          "id": "b5c6e800-1c4c-4ace-8000-000000000002",
          "name": "Offline strength",
          "status": "active",
          "startedAt": "2025-01-01T06:07:08.123Z",
          "finishedAt": null,
          "revision": 1,
          "exercises": [{
            "id": "b5c6e800-1c4c-4ace-8000-000000000003",
            "exerciseId": "4aba0e31-4c5b-45d3-b32b-1bc3480b06c7",
            "position": 0,
            "notes": "Start note",
            "restDurationSeconds": 75,
            "sets": [{
              "id": "b5c6e800-1c4c-4ace-8000-000000000004",
              "position": 0,
              "repetitions": null,
              "weightKg": 0.0,
              "isCompleted": false,
              "restSeconds": null
            }],
            "exerciseName": "Machine Pec Fly"
          }, {
            "id": "b5c6e800-1c4c-4ace-8000-000000000005",
            "exerciseId": "4aba0e31-4c5b-45d3-b32b-1bc3480b06c7",
            "position": 1,
            "notes": "",
            "restDurationSeconds": 0,
            "sets": [],
            "exerciseName": "Machine Pec Fly"
          }]
        }
      }]
    }
  }]
}
```

The replacement assertion is one batch with the prior workout's revision-2 delete first and the new workout's
revision-1 upsert second. Pulling from the previous cursor returns the same replacement batch.

## Deployment

The change has only run in local test fixtures. Deployment and Android capture are pending. Enable the Android
producer after the configured origin supports the command, and retain server support while devices have queued
snapshot starts.
