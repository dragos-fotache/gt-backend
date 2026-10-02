# Personal server runbook (non-production, single user)

This backend is accepted for functional scope through Task 9c. It is
**not** production-hardened: no capacity evidence, no alerting, no
restore drills, no SLA. For one personal server and one personal Android
app that is fine. This file is the whole operations guide.

## 1. Prerequisites

- Server with Java 27 and PostgreSQL 16 (same machine is fine).
- A Google Cloud project with an OAuth client the Android app uses to
  request ID tokens. The backend checks the token audience, so you need
  that client ID as `GOOGLE_OAUTH_AUDIENCE` below.
- Reachability from the phone: Tailscale (simplest, private) or a public
  domain with TLS (see section 5).

## 2. Configuration

All secrets come from the environment, never from Git. `.env` files are
ignored. Keep them in `~/.config/gymtracka-backend/dev.env` (or your
service manager's `EnvironmentFile`) with `chmod 600`.

| Variable | Required | Meaning, default |
|---|---|---|
| `DB_URL` | yes | JDBC URL, e.g. `jdbc:postgresql://localhost:5432/gymtracka` |
| `DB_USER` / `DB_PASSWORD` | yes | Postgres role for the app (least privilege, owns no superuser) |
| `GOOGLE_OAUTH_AUDIENCE` | yes | OAuth client ID the Android ID tokens are minted for |
| `AUTH_ENCRYPTION_KEY` | yes | Base64 of 32 random bytes (credential encryption) |
| `ACCOUNT_DELETION_LEDGER_KEYS` | for delete/status | JSON ring `{"activeVersion":"1","keys":{"1":"base64-32B"}}`; generate with `python3 -c "import base64,os,json;print(json.dumps({'activeVersion':'1','keys':{'1':base64.b64encode(os.urandom(32)).decode()}}))"` |
| `ACCOUNT_DELETION_PURGE_ENABLED` | recommended `true` | Personal use: enable so accepted deletions actually purge within 24 h. Default `false` (fail-closed `503`). |
| `ACCOUNT_DELETION_PURGE_{FIXED_DELAY_MS,ACCOUNT_BUDGET,ROW_BUDGET}` | no | Defaults 60000 ms, 20 accounts/sweep, 500 rows. Leave alone. |
| `AUTH_CLEANUP_*`, `SYNC_{HISTORY,SNAPSHOT}_CLEANUP_*` | no | Defaults on, 60 s cadence. Leave alone. |

Generate once, back the values up with the database (section 6). Losing
`AUTH_ENCRYPTION_KEY` or a referenced ledger-key version orphans
credentials or deletion receipts with no recovery.

## 3. Database

Create the database and role once. Flyway migrates V1–V9 automatically on
every boot; never hand-edit applied migrations or tables.

```sql
CREATE USER gym_user WITH PASSWORD '...';
CREATE DATABASE gymtracka OWNER gym_user;
```

## 4. Build and run

```bash
./mvnw -q -DskipTests package
java -jar target/gtbackend-0.1.0-SNAPSHOT.jar --server.port=8080
```

systemd example (`/etc/systemd/system/gt-backend.service`): run as an
unprivileged user, `EnvironmentFile=/home/<you>/.config/gymtracka-backend/dev.env`,
`ExecStart=/usr/bin/java -jar /opt/gt-backend/gtbackend-0.1.0-SNAPSHOT.jar --server.port=8080`,
`Restart=on-failure`. Logs go to the journal (`journalctl -u gt-backend`).

Update procedure: `git pull`, rebuild, `systemctl restart gt-backend`.
Migrations are forward-only, so rollback means restoring the database
backup, not downgrading the jar.

## 5. Reachability and TLS

- **Tailscale:** install on server and phone, serve on the tailnet
  address. No certificates needed; restrict with ACLs if you like.
- **Public domain:** put Caddy in front (`reverse_proxy localhost:8080`)
  for automatic Let's Encrypt. The Android app must use `https://`
  unless you add a cleartext exception, which you should not for a
  public host.

## 6. Backups (the one operational must-do)

Nightly `pg_dump` via cron, keep 7 daily copies plus the env file:

```bash
pg_dump -U gym_user -h localhost gymtracka | gzip > /var/backups/gt/gymtracka-$(date +%F).sql.gz
```

Restore: `gunzip -c <backup> | psql <empty database>`, then boot the app
and sign in again (sessions/credentials restore with the database as long
as the keys in section 2 are intact). Test a restore to a scratch
database once. Honest note: backups hold deleted rows until they age
out of retention; the 24-hour purge deadline applies to live storage.

## 7. Android wiring

- Base URL: your server origin. Google Sign-In must request an ID token
  for the audience in section 2.
- First sync: `POST /v1/auth/google/challenge`, Google exchange,
  `POST /v1/sync/pull`, `POST /v1/sync/push`; snapshots via
  `/v1/sync/snapshot`. Mutations need `Idempotency-Key`; retries reuse
  the identical body for replay, changed bodies get `409`.
- Error codes: `401 invalid_google_credential`, `404 not_found`,
  `409 idempotency_key_reused`, `422 validation_error`,
  `503 temporarily_unavailable` (retry later). All lifecycle responses
  are `no-store`; never log tokens or bodies.
- Deletion (`POST /v1/account/delete`, `confirm:true`) returns a receipt
  and purges within 24 h when enabled. The app is responsible for
  clearing its own Room cache and not reassigning old pending work after
  account changes; export (`POST /v1/account/export`) gives the user
  their data first.

## 8. Deliberately skipped

Capacity/availability evidence, alerting, outage runbooks, restore
drills beyond section 6, compliance review, and any multi-user
guarantees. Revisit only if other people ever depend on this server.
