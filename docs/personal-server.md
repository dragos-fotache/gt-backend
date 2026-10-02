# Personal-server runbook

This is a single-user setup, not a public-service release checklist. It still
needs stable keys, backups, protected network access and honest handling of
[known limitations](technical-debt.md). Personal use does not remove the shared
contract's account-isolation and retention requirements.

## 1. Prerequisites

- Java 27; PostgreSQL (integration tests use version 16).
- The independent `shared-docs/` checkout for the catalogue build inputs.
- Google OAuth configuration: Android requests ID tokens for the **web/server
  client ID** configured as `GOOGLE_OAUTH_AUDIENCE`. Do not put a client secret
  in Android. Real phone sign-in still needs an end-to-end smoke test.

The local setup created during initial startup uses database `gymtracka_prod`,
role `gtapp`, and port 8080. The existing `gymtracka` development database was
left separate. The observed local PostgreSQL version was 18.6; a successful
startup there is not the same as integration coverage on that version.
These are setup notes, not a guarantee that a terminal/server is still running.

## 2. Database

For a new installation, run the following in an administrator `psql` session.
Choose a password interactively; do not put it into Git or shell history:

```text
CREATE ROLE gtapp LOGIN;
\password gtapp
CREATE DATABASE gymtracka_prod OWNER gtapp;
ALTER DATABASE gymtracka_prod SET timezone TO 'UTC';
```

The application role must be able to run Flyway DDL in its own database, but
must not be a PostgreSQL superuser. Startup applies V1–V9 automatically to a
new database. Never reset an existing personal database as a test fixture.

## 3. Environment and keys

The app does not load `dev.env` itself. Store configuration privately at
`~/.config/gymtracka-backend/dev.env` with file mode `600`; do not read or expose
that file through an agent transcript. The following uses **placeholders**,
not usable keys or credentials:

```bash
DB_URL='jdbc:postgresql://localhost:5432/gymtracka_prod'
DB_USER='gtapp'
DB_PASSWORD='<your database password>'
GOOGLE_OAUTH_AUDIENCE='<web/server client ID>'
AUTH_ENCRYPTION_KEY='<base64 of 32 random bytes>'
ACCOUNT_DELETION_LEDGER_KEYS='{"activeVersion":"1","keys":{"1":"<independent base64 32-byte key>"}}'
ACCOUNT_DELETION_PURGE_ENABLED=false
```

Quote the JSON as a whole; unquoted braces/spaces cause shell parse errors.
Generate the two keys independently using a password manager or cryptographic
random generator. Keep existing values stable when restarting/updating; do not
regenerate them on each boot.

| Setting | Behavior |
|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | Database connection. Defaults point at `gymtracka`/`gym_user`; set them explicitly to avoid using the wrong database. |
| `GOOGLE_OAUTH_AUDIENCE` | Required nonblank audience. A placeholder can satisfy startup but cannot authenticate real Google tokens. |
| `AUTH_ENCRYPTION_KEY` | Required base64 32-byte key. Protects credential fingerprints, encrypted replay and pagination signing. |
| `ACCOUNT_DELETION_LEDGER_KEYS` | Independent versioned ring. Blank leaves new deletion/status unavailable; malformed supplied JSON fails startup. Retain historical referenced versions. |
| `ACCOUNT_DELETION_PURGE_ENABLED` | Default `false`: new deletion returns unavailable. `true` wires the scheduler and acceptance readiness. It is not a runtime capacity/health guarantee. |
| `ACCOUNT_DELETION_PURGE_INITIAL_DELAY_MS`, `ACCOUNT_DELETION_PURGE_FIXED_DELAY_MS` | Default 60000 each. |
| `ACCOUNT_DELETION_PURGE_ACCOUNT_BUDGET`, `ACCOUNT_DELETION_PURGE_ROW_BUDGET` | Defaults 20 intents/sweep and 500 rows/table/phase. |
| `AUTH_CLEANUP_INITIAL_DELAY_MS`, `AUTH_CLEANUP_FIXED_DELAY_MS` | Default 60000 each. |
| `SYNC_HISTORY_CLEANUP_*`, `SYNC_SNAPSHOT_CLEANUP_*` | Enabled by default, initial/fixed delays 60000. |

The current personal setup previously enabled purge. This runbook recommends
keeping new installations disabled until the [purge hardening gaps](technical-debt.md#account-purge-hardening-and-verification)
are addressed. Changing documentation does not change any running configuration.
An enabled flag must not be interpreted as proof of the contract's 24-hour deadline.

Back up the database and keys securely. Losing the auth key invalidates existing
credential lookup/replay. Losing a referenced ledger version prevents old ownership
and receipt verification. Do not casually rotate either to fix unrelated errors.

## 4. Build and start

From the repository root:

```bash
sha256sum -c docs/shared-docs-inputs.sha256
flock /tmp/gt-backend-task7-build.lock ./mvnw -q -DskipTests package
```

Start in a normal shell without `exec` so stopping Java leaves the shell/pane
available. Abort on an environment-file error instead of booting with partial config:

```bash
(
  set -a
  . "$HOME/.config/gymtracka-backend/dev.env" || exit 1
  set +a
  java -jar target/gtbackend-0.1.0-SNAPSHOT.jar --server.port=8080 --server.address=127.0.0.1
)
```

This example binds to loopback for a reverse proxy. The app otherwise listens on
its configured/default interfaces; expose it only deliberately. Ctrl+C stops this
foreground process. Herdr is optional: run the same command in a terminal pane.
Do not rely on historical pane IDs.

Startup should report successful Flyway validation/migration and Tomcat listening
on the intended port. There is no configured Actuator health endpoint. A minimal
HTTP smoke check is:

```bash
curl --fail-with-body -sS http://127.0.0.1:8080/v1/auth/google/challenge \
  -H 'Content-Type: application/json' -d '{}'
```

This creates a short-lived challenge; it tests routing/database access, **not**
real Google sign-in, the ledger ring or purge scheduling.

For a server that must survive terminal exits, use a service manager such as
systemd with an unprivileged user, a private `EnvironmentFile`, an absolute JAR
path and `Restart=on-failure`. Do not combine shell `export` syntax with systemd
`EnvironmentFile` syntax. Keep logs private and avoid logging bodies/tokens.
No systemd unit, proxy, backup timer or firewall rule is installed by this guide.

Before updates, back up, run the tests, rebuild and restart deliberately. Applied
migrations are forward-only; downgrading the JAR against a newer schema is not a
supported rollback. Restoring a backup needs the safeguards below, not just a
service restart.

## 5. Phone access

- Public access: use HTTPS through a reverse proxy (for example Caddy) and keep
  the application/database ports private.
- Private access: a VPN/tailnet can restrict reachability, but VPN encryption
  does not make an `http://` URL acceptable to Android's network-security policy.
  Prefer HTTPS there too; any development-only cleartext exception must be
  deliberate, narrow and kept out of public/release configuration.
- Emulator `localhost` and phone `localhost` refer to the device, not this server.
  Configure the Android base URL to a reachable server origin.

## 6. Backups and restore caution

A simple starting policy is seven daily PostgreSQL backups, plus a separate secure
copy of the stable keys/configuration. Use `.pgpass` (mode `600`) or another secure
credential mechanism, not command-line passwords. For example:

```bash
pg_dump -h localhost -U gtapp -d gymtracka_prod -Fc -f /var/backups/gt/gymtracka-$(date +%F).dump
```

Create a private writable backup directory first. Check command success and available
space, and actually prune old copies; the command alone does neither. Seven daily
copies fit within the shared contract's maximum 30-day backup lifetime. Protect and
expire copied/off-machine backups too. Database dumps contain credentials and user
payloads; they are not safe to publish or place in this Git repository.

**Do not restore an old dump and immediately serve it.** It can resurrect accounts
or roots deleted since the backup, rewind cursors/replay identity, and restore stale
sessions. Current deletion evidence must survive and be reconciled before serving,
with client synchronization consequences reviewed. That process is not automated
or end-to-end verified in this backend.

You can check backup readability by restoring to an isolated scratch database and
inspecting it without exposing an app to clients. That is a backup integrity check,
not proof of safe live disaster recovery. Keep the running database untouched.

## 7. Android integration

Use the shared [Android protocol](../shared-docs/docs/backend-contract/android-client-protocol.md),
[authentication](../shared-docs/docs/backend-contract/authentication.md) and
[account lifecycle](../shared-docs/docs/backend-contract/account-lifecycle.md)
documents for DTOs, error handling and transitions.

- Google challenge/exchange are POSTs. Push is `POST /v1/sync/push`;
  pull and snapshots are **GET** `/v1/sync/pull` and `/v1/sync/snapshot`.
- Without a persisted cursor, bootstrap from a complete staged snapshot, not
  retained incremental history; preserve pending import/local intent.
- Sync uses mutation IDs and exact mutation-object bytes. Direct resource,
  auth and delete operations use their own idempotency keys. Do not add a
  generic idempotency header to all operations or switch namespaces on retry.
- Lifecycle proof is foreground/purpose-bound and does not sign in. A `202`
  deletion receipt means disabled, not physically purged. A `503` is not a receipt;
  keep the account/cache until an authenticated transition says otherwise.
- Android owns Room/outbox/session fences and clearing only the affected account.
  Backend tests do not implement cache wiping on a phone.

Real phone sign-in, refresh, sync, export and account transitions should be smoke-tested
with the configured URL/audience. They have not been established by a local challenge
response or mocked-provider tests. There is no obligation to pursue public-service
capacity/compliance work for personal use, but correctness gaps remain correctness gaps.
