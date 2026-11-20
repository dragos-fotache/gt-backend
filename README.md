# GymTracka backend

Personal-server backend for the GymTracka Android app: Java 27, Spring Boot
4.1.1, PostgreSQL, Flyway and the Maven wrapper. Base package:
`com.dragosfotache.gtbackend`.

## Documentation

- [Personal-server setup and operation](docs/personal-server.md)
- [Architecture and persistence boundaries](docs/architecture.md)
- [Known limitations and technical debt](docs/technical-debt.md)
- [Contributor/agent handoff](docs/coordinator-handoff.md)
- [Concise implementation history](docs/archive/README.md)

The [shared backend contract](shared-docs/docs/backend-contract/README.md)
and [OpenAPI](shared-docs/docs/backend-contract/openapi.yaml) define wire
behavior for both backend and Android developers. Local notes describe the
implementation; they do not override that contract. Resolve mismatches
explicitly rather than changing expected behavior to fit the code.

## Implemented API

Controllers exist for all paths in the v1 OpenAPI:

- Google challenge/exchange, refresh and logout.
- Catalogue/custom-exercise search, individual resources, workout/routine browsing.
- Fourteen direct exercise/routine/workout mutations and commands.
- Sync push (all eighteen mutation kinds), incremental pull and frozen snapshots.
- Account export, deletion receipt replay and authenticated status; scheduled purge.

Route coverage is not a claim that every failure, race or operating condition
is verified. In particular, account-purge hardening and coverage gaps remain
in [known limitations](docs/technical-debt.md#account-purge-hardening-and-verification).
The Android app's integration and local cache behavior require separate testing.

## Build and test

`shared-docs/` must be checked out independently at that path before building.
It is ignored by this repository and is **not a submodule**. The build embeds
its pinned catalogue seed and redistribution notices from `catalogue/v1`.
Do not replace it with a synthetic catalogue or add its Git checkout to this
repository. Exact input hashes are in `docs/shared-docs-inputs.sha256`:

```bash
sha256sum -c docs/shared-docs-inputs.sha256
flock /tmp/gt-backend-task7-build.lock ./mvnw -q test
flock /tmp/gt-backend-task7-build.lock ./mvnw -q -DskipTests package
```

Tests need Docker and use isolated Testcontainers PostgreSQL 16 databases.
They must not use the personal server's database or environment file. Most
HTTP tests double the external Google boundary; they are not live Android
or Google sign-in tests. Historical test totals are in the archive, not a
substitute for running the current suite.

For startup and configuration, follow the [runbook](docs/personal-server.md).
The server does not load `dev.env` automatically. Purge is disabled by default;
the enable flag is an operator choice, **not an automated capacity check**.

## Development rules

- Keep applied migrations V1–V9 immutable; add forward-only migrations.
- Never commit secrets, credentials, Google tokens or environment files.
- Do not reset a personal database or run destructive tests against it.
- Keep transaction, authorization, exact-byte replay and account-isolation
  boundaries intact when changing code.
- Prefer cohesive methods, explicit results and focused responsibility boundaries;
  do not grow the legacy command dispatcher with more modes.
- Review shared-contract edits separately and update their pinned hashes deliberately.
- Do not stage, commit, deploy or alter live services without explicit authorization.

For Java navigation, prefer the configured `mcpls` server when available;
use `rg`/read-only source inspection if it is unavailable, without changing
machine-wide tooling to work around a navigation failure.
