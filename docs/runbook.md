# VitaDelta operations runbook

This runbook covers running, seeding and operating the VitaDelta stack: imports, the bootstrap
administrator, secret rotation, promo codes, exchange rates, the outbox, housekeeping, backup and
restore, security scanning, health, metrics and logs. Every command is written to be pasted as is
from the repository root.

## 1. Conventions

- All scripts under `scripts/` are POSIX `bash`. On Windows run them in Git Bash or WSL.
- `.env` in the repository root holds every `VITADELTA_*` setting of the local stack (see
  `.env.example` for the description of each variable). It is ignored by Git and is never
  committed.
- The compose commands below use this shell function; define it once per shell:

  ```bash
  dc() { docker compose --env-file .env -f infra/docker-compose.yml "$@"; }
  ```

- PostgreSQL is not published on the host and has no client there; SQL runs through the
  database container:

  ```bash
  dc exec -T postgres psql -U vitadelta -d vitadelta
  ```

  The user name is the value of `VITADELTA_DB_USERNAME` (`vitadelta` in the local stack).
- Secrets never appear in a command's arguments (visible to every local user in the process list)
  or in the shell history. Values are read from a hidden prompt (`read -rs`), from `.env`, or
  generated, and are handed over through standard input or a file of mode `0600`: SQL that
  carries a secret is piped into `psql`, request bodies are piped into `curl --data-binary @-`,
  the `Authorization` header is read from a header file (`curl -H @file`), and `.env` is changed
  with an editor or with `env_set` below. `printf` and `read` are shell builtins, so their
  arguments never reach the process list.
- `env_set NAME` removes the line `NAME=...` from `.env` and appends `NAME=<value>`, with the
  value read from standard input, or from a hidden prompt when standard input is a terminal; the
  file is rewritten with mode `0600`. Define it once per shell:

  ```bash
  env_set() {
    local name="$1" value line
    if [ -t 0 ]; then read -rs -p "$name: " value; echo >&2; else IFS= read -r value || true; fi
    (
      umask 077
      while IFS= read -r line || [ -n "$line" ]; do
        case "$line" in
          "$name="*) ;;
          *) printf '%s\n' "$line" ;;
        esac
      done < .env > .env.new
      printf '%s=%s\n' "$name" "$value" >> .env.new
    ) && mv .env.new .env
  }
  ```
- An environment variable set in the shell overrides the value in `.env` for one compose command,
  for example `VITADELTA_JOBS_ENABLED=false dc up -d api`.
- Changes to `.env` reach a running container only when it is recreated: use `dc up -d <service>`
  (recreates when its configuration changed), not `dc restart <service>` (keeps the old
  environment).
- Production rules that the local stack does not need are marked **Production**.

## 2. Run the stack

Prerequisites: Docker with Compose v2, curl, Git Bash or WSL on Windows. The test suites also need
JDK 25 in `JAVA_HOME`, Node.js 22.23 or newer with npm, and uv with Python 3.14 (see the README).

From an empty machine state to a seeded stack:

```bash
bash scripts/dev-down.sh --volumes
bash scripts/dev-up.sh --bootstrap-admin
bash scripts/seed.sh
```

- `scripts/dev-up.sh` creates `.env` from `.env.example` on its first run (development values and
  freshly generated secrets), builds the images, starts the services and waits until each one is
  healthy, then checks the API through nginx, the scoring service inside its container, the web
  root and Mailpit. An existing `.env` is never overwritten.
- `--bootstrap-admin` gives an empty `VITADELTA_BOOTSTRAP_ADMIN_EMAIL` the address
  `admin@vitadelta.localhost` and an empty `VITADELTA_BOOTSTRAP_ADMIN_PASSWORD` a generated
  password, stored in `.env`. A newly generated password is shown once, on the terminal only
  (written to `/dev/tty`, never to standard output or standard error, so a pipe, a log file or a
  CI log never receives it). Without a terminal, or when `CI` is set, it is not shown at all and
  the script names the `.env` entry to open in an editor instead. Values that are already set are
  kept and never printed. The end-to-end suites sign in with this account.
- `scripts/dev-down.sh` stops the stack and keeps the database volume; `--volumes` also deletes
  the volume and with it all local data.

| Endpoint | Address | Exposure |
|---|---|---|
| Web app and API (nginx) | http://localhost:8080 | all interfaces; the only public entry point |
| Mailpit inbox and HTTP API | http://127.0.0.1:8025 | loopback only |
| Mailpit SMTP (`mailpit:1025`), PostgreSQL (`postgres:5432`) | not published | compose network only; SQL through `dc exec -T postgres psql` (section 1) |
| API, scoring service, Actuator | not published | reachable inside the compose networks only |

nginx runs as the unprivileged `nginx` user (master and workers), listens on 8080 and keeps its
pid and temporary files under `/tmp` of its container.

**Production:** TLS is terminated at nginx or at an edge in front of it. Set `VITADELTA_TLS=true`
in that environment so that nginx adds `Strict-Transport-Security: max-age=63072000;
includeSubDomains` to every response; the plain-HTTP local stack keeps `VITADELTA_TLS=false`
(HSTS on localhost would force HTTPS on every local service). `VITADELTA_WEB_BASE_URL` must be the
public `https://` origin, `VITADELTA_WEB_ORIGINS` the same origin, and `VITADELTA_PRIVACY_CONTACT`
must be set; outside the dev profile the API refuses to start otherwise and sends `Secure`
cookies. Run exactly one API instance (in-memory rate limits and caches, ADR-0011).

## 3. Seed

```bash
bash scripts/seed.sh
```

The seed loads `data/reference` (nutrients, unit conversions, reference and upper-limit sets,
countries, plans, the form registry), the synthetic fixtures `data/fixtures` and the real US label
snapshot `data/real` (with synthetic demo prices). It runs the API image once with the Spring
profile `seed`.

- It validates every file first. Exit code 2 means a file is invalid; nothing was written and the
  output lists the rows at fault.
- It is idempotent: rows are upserted by their business keys. A run that changes nothing keeps
  `catalog_version`; changed offers write `OFFERS_CHANGED` outbox events exactly like an admin
  import, so price alerts fire for them.
- Run it again after changing files under `data/`.

## 4. Admin imports

Imports need an administrator access token. Sign in as the bootstrap administrator; the password
is typed at a hidden prompt, the request body goes to `curl` through standard input, and the token
is written to a header file of mode `0600` that the calls below read with `-H @"$ADMIN_AUTH"`:

```bash
API=http://localhost:8080/api/v1
ADMIN_AUTH=$(mktemp)    # mode 0600; remove it when done: rm -f "$ADMIN_AUTH"
admin_login() {
  local email password token
  email=$(sed -n 's/^VITADELTA_BOOTSTRAP_ADMIN_EMAIL=//p' .env)
  read -rs -p 'Administrator password: ' password; echo >&2
  token=$(printf '{"email": "%s", "password": "%s"}' "$email" "$password" |
    curl -s -X POST "$API/auth/login" -H 'Content-Type: application/json' --data-binary @- |
    sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p')
  [ -n "$token" ] || { echo 'sign-in failed' >&2; return 1; }
  printf 'Authorization: Bearer %s\n' "$token" > "$ADMIN_AUTH"
}
admin_login
```

On the local stack the password is the `VITADELTA_BOOTSTRAP_ADMIN_PASSWORD` value of `.env`
(shown once by `dev-up.sh --bootstrap-admin`, otherwise open `.env` in an editor); to read it from
there instead of the prompt, replace the `read` line with
`password=$(sed -n 's/^VITADELTA_BOOTSTRAP_ADMIN_PASSWORD=//p' .env)`. The access token is valid for
15 minutes; run `admin_login` again when a call answers `401`.

Products (JSON document, schema `contracts/schemas/import-products.schema.json`, products upserted
by `code`):

```bash
curl -s -X POST "$API/admin/imports/products" -H @"$ADMIN_AUTH" \
  -H 'Content-Type: application/json' --data-binary @data/fixtures/products.synthetic.json
```

Offers (CSV, multipart field `file`; for every product and country in the file, offers of
retailers absent from the file are deleted):

```bash
curl -s -X POST "$API/admin/imports/offers" -H @"$ADMIN_AUTH" \
  -F "file=@data/fixtures/offers.price-drop.synthetic.csv;type=text/csv"
```

Both answer `202` with the job and its URL in `Location`. Read the status, summary and row errors:

```bash
curl -s "$API/admin/imports/<jobId from the 202 answer>" -H @"$ADMIN_AUTH"
```

An import is validated as a whole: any error fails the job, nothing is written, the catalog version
is not bumped and no event is emitted. Limits: 20 MB per file, 50 000 products, 200 000 offer rows,
1000 reported errors. One import runs at a time; up to four wait in the queue.

## 5. Bootstrap administrator

At every start the API checks the bootstrap settings under an advisory lock:

| State | Result |
|---|---|
| an ADMIN account exists | nothing happens |
| no ADMIN, both variables empty | a warning is logged; admin routes answer `403` for everyone |
| no ADMIN, both variables set | a verified ADMIN with the FREE plan is created; the password must satisfy the password rules (at least 10 characters, not in `data/reference/weak_passwords.txt`) |
| the e-mail belongs to an existing USER | startup fails; an existing account is never promoted |

There are no default credentials. Locally, `bash scripts/dev-up.sh --bootstrap-admin` fills the
two variables; on another environment set them in its configuration before the first start. The
variables are read only to create the account: changing them later does not change the existing
administrator. To change the administrator's password, use the password-reset flow
(`POST /api/v1/auth/forgot-password`; locally the mail arrives in Mailpit).

If startup fails with the bootstrap collision, choose an e-mail that has no account, or remove both
variables, and start again.

## 6. Rotate secrets

Generate every new secret with `openssl rand -hex 48` (database passwords: `openssl rand -hex 24`).

### 6.1 JWT signing secret (key id rotation)

Access tokens are HS256 JWTs whose header `kid` is the first 16 hex characters of the SHA-256 of
the signing secret (ADR-0006). During a rotation the API accepts tokens of the current secret and
of `VITADELTA_JWT_SECRET_PREVIOUS`, selected by `kid`; tokens with any other `kid` are rejected.
Refresh tokens are random values stored as hashes and are not affected.

1. Put the current value of `VITADELTA_JWT_SECRET` into `VITADELTA_JWT_SECRET_PREVIOUS` and a new
   value into `VITADELTA_JWT_SECRET` (`env_set`, section 1; neither value appears in an argument):

   ```bash
   sed -n 's/^VITADELTA_JWT_SECRET=//p' .env | env_set VITADELTA_JWT_SECRET_PREVIOUS
   openssl rand -hex 48 | env_set VITADELTA_JWT_SECRET
   ```

   On another environment change the same two values in its configuration.
2. Recreate the API: `dc up -d --wait api`. New tokens carry the new `kid`; tokens issued before
   stay valid until they expire.
3. Wait at least 15 minutes (the access-token lifetime).
4. Empty `VITADELTA_JWT_SECRET_PREVIOUS` and recreate the API again:

   ```bash
   printf '\n' | env_set VITADELTA_JWT_SECRET_PREVIOUS
   dc up -d --wait api
   ```

Nobody is signed out: browsers renew their access token with the refresh cookie. After step 4 a
token signed with the old secret answers `401`.

### 6.2 Cookie secret

`VITADELTA_COOKIE_SECRET` signs the anonymous `vd_anon` cookie and encrypts the 20-second refresh
grace successors. It has no "previous" slot: after a change
(`openssl rand -hex 48 | env_set VITADELTA_COOKIE_SECRET`, then `dc up -d --wait api`)
every anonymous visitor gets a new identity and a fresh trial quota, and a refresh that races the
restart within its 20-second grace window is answered `401`, after which the user signs in again.
Rotate it at a quiet time.

### 6.3 Database password

`POSTGRES_PASSWORD` initialises the database only when its volume is created, so change the role
first, then the configuration. The new password travels through standard input only: the SQL is
piped into `psql` (statement logging is switched off for that session) and the value into
`env_set`:

```bash
NEW_DB_PASSWORD=$(openssl rand -hex 24)
printf "SET log_statement = 'none';\nALTER ROLE vitadelta PASSWORD '%s';\n" "$NEW_DB_PASSWORD" |
  dc exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U vitadelta -d vitadelta
printf '%s\n' "$NEW_DB_PASSWORD" | env_set VITADELTA_DB_PASSWORD
unset NEW_DB_PASSWORD
dc up -d --wait
```

### 6.4 SMTP credentials and other settings

Change `VITADELTA_SMTP_USERNAME` / `VITADELTA_SMTP_PASSWORD` at the provider, update the
configuration (`env_set VITADELTA_SMTP_PASSWORD` prompts for the value without echoing it) and
recreate the API (`dc up -d --wait api`). The same applies to every other `VITADELTA_*` value.

### 6.5 Backup key

Create a new key pair on the machine that holds backup keys (never on the backup host):
`age-keygen -o vitadelta-backup-<year>.key`. Put the printed public key (`age1...`) into
`VITADELTA_BACKUP_RECIPIENT`. Keep the old private key for as long as dumps encrypted to it exist
(14 days, section 11.2).

## 7. Promo codes

Promo codes grant PRO for a number of days. All calls below need the administrator header file
`$ADMIN_AUTH` of section 4. nginx never writes a promo code to its access log: the path after
`/promo-codes/` is logged as `<redacted>` (section 14).

```bash
# create: the server generates the code (optional prefix, then 16 Crockford base32 characters)
curl -s -X POST "$API/admin/promo-codes" -H @"$ADMIN_AUTH" \
  -H 'Content-Type: application/json' \
  -d '{"prefix": "LAUNCH", "planCode": "PRO", "durationDays": 30, "maxRedemptions": 100, "expiresAt": "2027-01-31T23:59:59Z"}'
# list (newest first, with redeemedCount)
curl -s "$API/admin/promo-codes" -H @"$ADMIN_AUTH"
# delete (case-insensitive; existing redemptions stay valid)
curl -s -X DELETE "$API/admin/promo-codes/LAUNCH-7K3M9QXT2B4N6PRW" -H @"$ADMIN_AUTH"
```

Users redeem a code with `POST /api/v1/me/subscription/promo` (`{"code": "..."}`) after verifying
their e-mail. Every failure (unknown, expired, exhausted, already redeemed, malformed) answers the
same `400 promo_invalid`; redemptions are limited to 10 per hour per IP and 5 per hour per user
(`429`). Redeeming activates PRO for the code's duration or extends an active PRO period.

To grant or revoke manually:

```bash
# find the user id
curl -s "$API/admin/users?q=someone@example.com" -H @"$ADMIN_AUTH"
# grant PRO for 30 days
curl -s -X POST "$API/admin/users/<user id>/subscription" -H @"$ADMIN_AUTH" \
  -H 'Content-Type: application/json' -d '{"planCode": "PRO", "durationDays": 30}'
# disable an account (revokes all of its sessions); "ACTIVE" enables it again
curl -s -X POST "$API/admin/users/<user id>/status" -H @"$ADMIN_AUTH" \
  -H 'Content-Type: application/json' -d '{"status": "DISABLED"}'
```

Expired PRO subscriptions are downgraded by `SubscriptionExpiryJob` daily at 00:05 UTC.

## 8. Exchange rates (FX) troubleshooting

Rates are ECB reference rates fetched from Frankfurter v2 (`VITADELTA_FX_BASE_URL`, default
`https://api.frankfurter.dev/v2`, always with `providers=ECB`) by `FxRefreshJob`: at start-up when
no rate is stored or the newest is older than one day, then every 6 hours. Queries never call the
provider; they read stored rates. ECB publishes on working days only, so the newest `as_of` lags by
one to three days around weekends and holidays; that is normal.

| Symptom | Check | Action |
|---|---|---|
| prices stay in the native currency, `meta.fx.status = UNAVAILABLE`, flag `FX_UNAVAILABLE` | no rate stored for the pair: `dc exec -T postgres psql -U vitadelta -d vitadelta -c "SELECT base, quote, max(as_of) FROM fx_rate GROUP BY 1, 2 ORDER BY 1, 2"` | run the refresh (below) and read its answer |
| `meta.fx.stale = true`, flag `FX_STALE` | newest `as_of` older than 5 days | the provider has been unreachable for days; check egress and the provider URL |
| refresh answers with failures | `dc logs api` lines of `com.vitadelta.api.fx`; metric `vitadelta.fx.refresh.failure` | provider down or unreachable: stored rates stay in use, the job retries every 6 hours |
| a display currency never gets a rate | WARN line naming the currency on every refresh; metric `vitadelta.fx.refresh.unsupported{currency}` | see unpublished currencies below |
| `422 fx_unsupported_currency` | the requested display currency is not in `GET /api/v1/fx/currencies` | add it to `VITADELTA_FX_EXTRA_CURRENCIES` if the ECB publishes it |
| `VITADELTA_JOBS_ENABLED=false` | no refresh runs at all | run the refresh manually or enable the jobs |

Run the refresh immediately (returns the number of upserted rates, or the failure per base):

```bash
curl -s -X POST "$API/admin/fx/refresh" -H @"$ADMIN_AUTH"
curl -s "$API/admin/health" -H @"$ADMIN_AUTH"   # components.fx: newestAsOf, rateAgeDays, stale
```

**Unpublished currencies.** `VITADELTA_FX_EXTRA_CURRENCIES` accepts any ISO 4217 code, but only the
currencies the ECB publishes get rates (`curl -s https://api.frankfurter.dev/v2/providers/ecb`
lists them). For a code the ECB does not publish, the provider leaves it out of its answer; the
refresh job then repeats the request without it, logs a warning naming it and counts
`vitadelta.fx.refresh.unsupported{currency}`, and every other pair is updated normally. The code
stays offered as a display currency but conversions to it are `UNAVAILABLE`. Remove it from
`VITADELTA_FX_EXTRA_CURRENCIES` and recreate the API (`dc up -d --wait api`) to stop offering it.
Check the provider directly from the API container when in doubt:

```bash
dc exec -T api curl -s "https://api.frankfurter.dev/v2/rates?base=TRY&quotes=EUR,USD&providers=ECB"
```

## 9. Outbox and dead letters

Offer changes and alert e-mails go through the transactional outbox (ADR-0008). `OutboxProcessor`
runs every 10 seconds, claims up to 100 events with a 2-minute lease, and retries a failed event
after 2, 4, 8 and 16 minutes; the fifth failure marks it `DEAD_LETTER` (metric
`vitadelta.outbox.dead.letters`). The stored `last_error` is the exception class only.

Inspect:

```bash
curl -s "$API/admin/outbox/stats" -H @"$ADMIN_AUTH"   # pending, failed, deadLetter, oldestPendingAt
dc exec -T postgres psql -U vitadelta -d vitadelta -c \
  "SELECT id, event_type, aggregate_id, attempts, last_error, processed_at
     FROM outbox_event WHERE outcome = 'DEAD_LETTER' ORDER BY processed_at DESC LIMIT 50"
```

`GET /api/v1/admin/health` reports the outbox as `DEGRADED` while `failed > 0` or the oldest
pending event is older than 5 minutes; dead letters are reported but do not change the status.

Handle dead letters:

1. Find the cause from `last_error` and the API log around `processed_at` (for
   `NOTIFICATION_CREATED` almost always SMTP: host, port, credentials, STARTTLS, provider limits).
   Fix it first.
2. Re-queue the dead letters of the affected period. Handlers are idempotent (one
   `alert_delivery` row per subscription and event; a notification is mailed once), so a re-queued
   event never produces a second alert or a second e-mail for work that already succeeded:

   ```bash
   dc exec -T postgres psql -U vitadelta -d vitadelta -c \
     "UPDATE outbox_event
         SET processed_at = NULL, outcome = NULL, attempts = 0, last_error = NULL,
             lease_until = NULL, next_attempt_at = now()
       WHERE outcome = 'DEAD_LETTER' AND processed_at >= now() - interval '1 day'"
   ```

3. Watch `GET /api/v1/admin/outbox/stats` until `pending` returns to 0.

Dead letters that are not re-queued are deleted by housekeeping after 90 days.

## 10. Housekeeping and retention

`HousekeepingJob` runs daily at 03:15 UTC under the advisory locks `HOUSEKEEPING_IDENTITY` and
`HOUSEKEEPING_ALERTS` and deletes in batches of 1000 rows:

| Data | Kept for | Setting |
|---|---|---|
| anonymous identities not seen | 180 days | `vitadelta.identity.anonymous-retention` |
| e-mail tokens after expiry or use | 7 days | `vitadelta.identity.email-token-retention` |
| refresh tokens after expiry or revocation | 30 days | `vitadelta.identity.refresh-token-retention` |
| refresh grace successors | 20 seconds | fixed (ADR-0006) |
| unverified accounts that never signed in | 30 days | `VITADELTA_UNVERIFIED_ACCOUNT_TTL_DAYS` |
| processed outbox events (with their `alert_delivery` rows) | 30 days | `vitadelta.alerts.processed-retention` |
| dead-letter outbox events | 90 days | `vitadelta.alerts.dead-letter-retention` |
| database backups | 14 days, at most 14 dumps | `scripts/backup.sh` (section 11) |

The `vitadelta.*` settings live in `services/api/src/main/resources/application.yml`. Each purge
logs its counts and increments `vitadelta.housekeeping.identity.purged` /
`vitadelta.housekeeping.alerts.purged`. `GET /api/v1/admin/health` lists the last run of every job.
`VITADELTA_JOBS_ENABLED=false` stops every scheduler (outbox, FX refresh, subscription expiry,
housekeeping); use it only for restores and maintenance.

## 11. Backup and restore

### 11.1 What a backup is

`scripts/backup.sh` runs `pg_dump -Fc` in the compose PostgreSQL container and pipes it through
`age` to the public key in `VITADELTA_BACKUP_RECIPIENT`, into
`backups/vitadelta-<UTC timestamp>.dump.age` with mode `0600`. The unencrypted dump never touches
the disk and the private key is never needed on the backup host. Without `age` on the `PATH` the
script refuses to run: an unencrypted backup is never written.

- Contains: the whole application database (catalog and reference data, accounts with password
  hashes, sessions, saved products, alerts, notifications, outbox, FX rates, promo codes, import
  jobs).
- Does not contain: `.env` and every other secret, the Mailpit store, the in-memory rate limits and
  caches, and the repository's `data/` files (they are in Git). Keep `.env` (or the environment's
  secret store) backed up separately and encrypted.
- `backups/` is ignored by Git; `scripts/security-scan.sh` fails if that ever changes.

Install `age` (1.3.2 or newer) from https://github.com/FiloSottile/age/releases, then:

```bash
# once, on the machine that keeps backup keys (not the backup host)
age-keygen -o vitadelta-backup.key        # prints the public key, age1...
# on the backup host: put that public key into VITADELTA_BACKUP_RECIPIENT in .env, then
bash scripts/backup.sh
```

`VITADELTA_BACKUP_RECIPIENT` is read from the environment first, then from `.env`. Options:
`--env-file <path>`, `--dir <directory>`.

### 11.2 Retention and erasure

After each successful backup the script deletes dumps beyond the newest 14 and dumps older than 14
days (the newest dump always stays). This 14-day window is the period during which a deleted
account can still exist in a backup; the privacy notice states the same window. A restore
re-introduces accounts that were deleted after the dump was taken, so after any production restore
the deletions since the dump time are re-run before e-mail and jobs are enabled again (section
11.5).

### 11.3 Scheduling

Scheduling belongs to the host, not to the application. Daily at 02:30 UTC, with the repository at
`/opt/vitadelta` and `age` in `/usr/local/bin`:

```cron
30 2 * * * cd /opt/vitadelta && PATH=/usr/local/bin:/usr/bin:/bin bash scripts/backup.sh >> /var/log/vitadelta-backup.log 2>&1
```

Copy each new dump off the host (it is encrypted, so ordinary object storage is fine) and alert on
a non-zero exit status. A deployment on managed PostgreSQL replaces this script with the provider's
snapshots (encrypted at rest by the provider) under the same 14-day retention rule.

### 11.4 Restore into an isolated stack (verification)

```bash
bash scripts/restore.sh --identity /secure/path/vitadelta-backup.key backups/vitadelta-<timestamp>.dump.age
```

The script removes any previous restore stack, starts a fresh PostgreSQL in the separate compose
project `vitadelta-restore` (`infra/docker-compose.restore.yml`: its own volume, one internal
network without a route out, nothing published), decrypts the dump straight into `pg_restore`, and
starts the scoring service and the API against it with fresh random secrets, every scheduler
disabled, e-mail going to a sink Mailpit and the FX provider unreachable. It then runs the
read-only smoke subset: public health, row counts per table (catalog tables must not be empty) and
`POST /recommendations/compare` of GOLD-A vs GOLD-B in TR. Nothing registers users, imports data or
triggers alerts. Every compose command of the script names the project `vitadelta-restore` and
its file explicitly and clears `COMPOSE_PROJECT_NAME`, `COMPOSE_FILE`, `COMPOSE_PROFILES` and
`COMPOSE_ENV_FILES` first, so settings exported in the shell for the live stack cannot redirect it;
before touching anything it checks that the resolved project is `vitadelta-restore`, is not the
live project, and has no container created from `infra/docker-compose.yml`, and refuses to run
otherwise. The stack keeps running for inspection; remove it with:

```bash
bash scripts/restore.sh --down
```

A backup that has never been restored is treated as nonexistent: restore the newest dump this way
at least once a month and after every change to the backup scripts or the database schema.

### 11.5 Restore into the live stack (recovery)

A restore brings back every account that was deleted after the dump was taken, so the deletions
since then must be re-run before the restored database serves anyone. **The only record of those
deletions is the API log**, and it does not survive a teardown:

- `DELETE /api/v1/me` writes one INFO line `Account deleted (userId=<uuid>)` (never the e-mail
  address) to the API container's standard output. There is no deletion table (it would be lost
  with the database being replaced) and no log file; Docker keeps that output only while the API
  container exists. `scripts/dev-down.sh`, `dc down` and every recreation of the container (for
  example `dc up` after a configuration change) discard it, and a size limit of the Docker
  log driver, if the daemon has one, rotates old lines out.
- Unverified accounts purged by `HousekeepingJob` are only counted in the log, not listed. They
  need no record: the restored rows are older than the TTL and the next housekeeping run purges
  them again.
- **Production:** without central log shipping (a Docker logging driver or collector that keeps
  the API's standard output outside the host) there is no durable deletion ledger. Until one
  exists, the procedure below captures the record from the running container first and stops
  when that record cannot be shown to cover the time since the dump.

Verify the dump with section 11.4 first. Then, on the host whose database is lost or damaged,
in this order; nothing is stopped or removed before step 3 has succeeded.

1. Stop public traffic only, so that nobody can delete an account after the record is taken. The
   API container, and with it its log, stays:

   ```bash
   dc stop web
   ```

2. Capture the deletion record into a directory of mode `0700` outside the repository (it holds
   user ids):

   ```bash
   umask 077
   RECOVERY_DIR=/secure/path/vitadelta-recovery-$(date -u +%Y%m%dT%H%M%SZ)
   mkdir -p "$RECOVERY_DIR"
   DUMP_TIME=2026-11-25T11:33:13Z    # the UTC timestamp of the dump file name, as ISO 8601
   dc logs --no-color --no-log-prefix --timestamps api > "$RECOVERY_DIR/api.log"
   docker inspect --format '{{.Created}}' "$(dc ps -a -q api)" > "$RECOVERY_DIR/api-container-created.txt"
   grep -o 'Account deleted (userId=[0-9a-f-]*)' "$RECOVERY_DIR/api.log" |
     sed 's/.*userId=\([0-9a-f-]*\))/\1/' | sort -u > "$RECOVERY_DIR/deleted-user-ids.txt"
   ```

3. Check that the record is complete: it must reach back to the dump time.

   ```bash
   echo "dump taken:        $DUMP_TIME"
   echo "container created: $(cat "$RECOVERY_DIR/api-container-created.txt")"
   echo "first log line:    $(head -n 1 "$RECOVERY_DIR/api.log" | cut -d' ' -f1)"
   echo "deleted accounts:  $(wc -l < "$RECOVERY_DIR/deleted-user-ids.txt")"
   ```

   Continue only when both the container creation time and the first log line are earlier than
   the dump time. Ids from before the dump do no harm (deleting an absent id changes nothing).
   If either is later, the record is incomplete (the container was recreated or its log rotated
   after the dump): take the missing period from the central log store, if the deployment has
   one, and add those ids to `deleted-user-ids.txt`. Without such a source **stop here**: the
   restored database cannot tell which of its accounts were deleted after the dump. Keep the
   current state (do not run step 4), bring in the owner, and continue only once the missing
   deletions are known from another source (support requests, the privacy contact's records) or
   the owner has decided how to handle the gap. Copy `$RECOVERY_DIR` to the machine that keeps the
   backup keys before continuing.

4. Stop the application and remove the damaged database volume. **This deletes the live database
   and the API container with its log**; the record of step 2 is the copy that remains.

   ```bash
   test -s "$RECOVERY_DIR/api.log" && bash scripts/dev-down.sh --volumes
   ```

5. Start PostgreSQL alone on the empty volume (the API must not start before the restore, or it
   would create an empty schema and the bootstrap administrator):

   ```bash
   dc up -d --wait postgres
   ```

6. Restore (plaintext streamed, never stored):

   ```bash
   age --decrypt --identity /secure/path/vitadelta-backup.key backups/vitadelta-<timestamp>.dump.age \
     | dc exec -T postgres pg_restore --no-owner --no-acl --exit-on-error --single-transaction -U vitadelta -d vitadelta
   ```

7. Re-run the account deletions of the record, before the API starts. The foreign keys cascade
   the rest of each account (sessions, tokens, subscriptions, saved products, alerts,
   notifications, usage counters, promo redemptions), exactly like `DELETE /me`; pending
   notification e-mails of the account are removed with it:

   ```bash
   while IFS= read -r id <&3; do
     dc exec -T postgres psql -X -q -v ON_ERROR_STOP=1 -U vitadelta -d vitadelta -v id="$id" <<'SQL'
   BEGIN;
   DELETE FROM outbox_event
    WHERE processed_at IS NULL AND event_type = 'NOTIFICATION_CREATED'
      AND aggregate_id IN (SELECT CAST(id AS varchar) FROM notification WHERE user_id = :'id');
   DELETE FROM app_user WHERE id = :'id';
   COMMIT;
   SQL
   done 3< "$RECOVERY_DIR/deleted-user-ids.txt"
   # must print 0
   { printf 'SELECT count(*) FROM app_user WHERE id IN (NULL'; sed "s/.*/, '&'/" "$RECOVERY_DIR/deleted-user-ids.txt"; printf ');\n'; } |
     dc exec -T postgres psql -X -A -t -U vitadelta -d vitadelta
   ```

8. Start the API with every scheduler off and without public traffic (nginx stays down), and
   check it:

   ```bash
   VITADELTA_JOBS_ENABLED=false dc up -d --wait api
   ```

   **Production:** also point `VITADELTA_SMTP_HOST` / `VITADELTA_SMTP_PORT` at a sink for this
   step (for example the compose `mailpit` service on the compose network: `VITADELTA_SMTP_HOST=mailpit
   VITADELTA_SMTP_PORT=1025 VITADELTA_JOBS_ENABLED=false dc up -d --wait api`).

9. Start everything with the normal configuration (e-mail and jobs enabled again):

   ```bash
   dc up -d --wait
   ```

10. Remove the recovery record once the restore is confirmed (it holds user ids):
    `rm -rf "$RECOVERY_DIR"`, and the copy made in step 3 likewise.

### 11.6 Restore drill transcript

The drill was performed on 2026-11-25 on the local stack: start, seed, create a user with a saved
product and a price alert through the API, back up, delete the volumes, restore into the isolated
stack, verify, then recover the live stack with section 11.5. The identity file and the file
holding the user's login body are shown as placeholders in angle brackets, `dc` is the function of
section 1, host paths are shown relative to the repository root, and compose progress lines are
trimmed. The drill had no account deletions after the dump, so the deletion record of section 11.5
steps 1 to 3 is not shown.

```console
$ bash scripts/dev-down.sh --volumes
[dev-down] stack stopped and data volume removed
$ bash scripts/dev-up.sh
[dev-up] stack is up: web http://localhost:8080, mailpit http://127.0.0.1:8025
$ bash scripts/seed.sh
[seed] done
$ # register, verify through Mailpit, sign in, save a product, create an alert (README "Trying the API")
register -> 202
verify-email -> 204
login -> access token received
save product -> 201
create alert -> 201
$ dc exec -T postgres psql -U vitadelta -d vitadelta -A -t -F ' ' -c "SELECT 'app_user', count(*) FROM app_user UNION ALL SELECT 'saved_product', count(*) FROM saved_product UNION ALL SELECT 'alert_subscription', count(*) FROM alert_subscription UNION ALL SELECT 'product', count(*) FROM product UNION ALL SELECT 'offer', count(*) FROM offer"
app_user 1
saved_product 1
alert_subscription 1
product 110
offer 211
$ VITADELTA_BACKUP_RECIPIENT=age1... bash scripts/backup.sh
[backup] dumping database vitadelta and encrypting to the configured recipient
[backup] wrote backups/vitadelta-20261125T113313Z.dump.age (139363 bytes, mode 0600)
[backup] done; 1 dump(s) kept in backups (newest 14, at most 14 days old)
$ bash scripts/dev-down.sh --volumes
[dev-down] stack stopped and data volume removed
$ docker volume ls --filter name=vitadelta
$ bash scripts/restore.sh --identity <identity file> backups/vitadelta-20261125T113313Z.dump.age
[restore] removing any previous restore stack
[restore] starting a fresh, isolated database
[restore] decrypting vitadelta-20261125T113313Z.dump.age and restoring it (plaintext is streamed, never stored)
[restore] starting scoring and the API against the restored database (jobs off, e-mail to the sink, no FX)
[restore] read-only smoke
  GET /api/v1/health                 OK    {"status":"DEGRADED"}

  row counts per table (restored database):
    alert_delivery               0
    alert_subscription           1
    anonymous_identity           0
    app_user                     1
    catalog_version              1
    country                      4
    email_token                  1
    flyway_schema_history        6
    fx_rate                      0
    import_job                   0
    import_job_error             0
    notification                 0
    nutrient                     38
    nutrient_form                21
    offer                        211
    outbox_event                 129
    plan                         2
    product                      110
    product_availability         135
    product_nutrient             1568
    promo_code                   0
    promo_redemption             0
    reference_set                2
    reference_value              54
    refresh_token                1
    saved_product                1
    subscription                 1
    ul_set                       2
    unit_conversion              11
    upper_limit                  43
    usage_counter                0

  rows in flyway_schema_history      OK    6
  rows in country                    OK    4
  rows in nutrient                   OK    38
  rows in product                    OK    110
  rows in offer                      OK    211
  rows in catalog_version            OK    1
  POST /recommendations/compare      OK    GOLD-A vs GOLD-B in TR -> 200 "verdict":"NOT_WORTH_IT"
[restore] restore verified; the isolated stack keeps running for inspection
[restore] inspect: docker compose --project-name vitadelta-restore exec api curl -s http://127.0.0.1:8080/api/v1/health
[restore] remove:  scripts/restore.sh --down
$ # drill only (writes a session row into the throwaway database): the restored account signs in
$ docker compose --project-name vitadelta-restore exec -T api curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:8080/api/v1/auth/login -H 'Content-Type: application/json' --data-binary @- < <login body file, mode 0600>
200
$ # same request with a wrong password
{"type":"https://vitadelta.dev/problems/invalid_credentials","title":"Invalid credentials","status":401,"detail":"The e-mail address or password is incorrect.","instance":"/api/v1/auth/login","requestId":"c1a8d797-7f37-489a-9a45-bea0f51ac324"}
401
$ docker compose --project-name vitadelta-restore exec -T mailpit wget -qO- http://127.0.0.1:8025/api/v1/messages | grep -o '"total":[0-9]*'
"total":0
$ docker compose --project-name vitadelta-restore exec -T api curl -sS --max-time 5 https://api.frankfurter.dev/v2/currencies
curl: (6) Could not resolve host: api.frankfurter.dev
$ bash scripts/restore.sh --down
[restore] restore stack and its volume removed
$ # live recovery, section 11.5
$ dc up -d --wait postgres
 Container vitadelta-postgres-1 Healthy
$ age --decrypt --identity <identity file> backups/vitadelta-20261125T113313Z.dump.age | dc exec -T postgres pg_restore --no-owner --no-acl --exit-on-error --single-transaction -U vitadelta -d vitadelta
$ VITADELTA_JOBS_ENABLED=false dc up -d --wait api
 Container vitadelta-api-1 Healthy
$ dc exec -T api printenv VITADELTA_JOBS_ENABLED
false
$ dc exec -T postgres psql -U vitadelta -d vitadelta -A -t -c "SELECT count(*) FROM app_user"
1
$ dc up -d --wait
 Container vitadelta-web-1 Healthy
$ curl -s -o /dev/null -w '%{http_code}\n' -X POST http://localhost:8080/api/v1/auth/login -H 'Content-Type: application/json' --data-binary @- < <login body file, mode 0600>
200
$ dc exec -T api printenv VITADELTA_JOBS_ENABLED
true
```

`fx_rate` is 0 because the drill stack was backed up seconds after its first start, before the
first FX refresh had stored a rate; health is `DEGRADED` for the same reason (no stored rate).

## 12. Security scanning

```bash
bash scripts/security-scan.sh
```

`scripts/test-all.sh` runs it as the `security` suite, and the CI workflow runs it in its own job
with gitleaks installed and the full history checked out. It exits non-zero on any finding.

| Check | What it does |
|---|---|
| `secrets-history` | gitleaks 8.30.1 over the full history of every ref with `.gitleaks.toml` (default rules plus an allowlist of reviewed synthetic test values, matched exactly); fails first when any object of the history cannot be read |
| `secrets-tree` | gitleaks over a copy of the tracked files and the untracked files that are not ignored; fails when the listing, the copy or any single file cannot be read |
| `env-files` | no `.env*` file other than `.env.example` is tracked; `.env` and `backups/` are ignored |
| `sql-concat` | no string-built SQL in `services/api/src/main` (JPA and JDBC calls whose SQL is not made of literals and literal constants: `+` with a variable, `String.format`, `formatted`, `concat`, `StringBuilder`), no f-string, `.format` or `%` SQL in `services/scoring` |
| `npm-audit` | `npm audit --omit=dev --audit-level=high` in `apps/web` |
| `pip-audit` | pip-audit 2.10.1 over every locked package of `services/scoring` (`uv export --locked --all-groups`) |
| `dependency-check` | OWASP Dependency-Check Gradle plugin in `services/api`, failing at CVSS >= 7 |
| `dependabot` | `.github/dependabot.yml` covers npm, uv, gradle, github-actions, docker and docker-compose weekly |
| `actions-pinned` | every action in `.github/workflows` is pinned by a full commit SHA with its version in a comment |

Results are `PASS`, `FAIL`, `DEGRADED` (ran in a reduced mode) or `SKIPPED(reason)`. A degraded or
skipped check is never shown as `PASS`; the last line then reads `RESULT: OK WITH <n> CHECK(S)
SKIPPED OR DEGRADED`. `--strict` turns that into exit status 3; `--no-fallback` turns a missing
gitleaks into a failure (used by CI). Each check runs in its own subshell: a check that fails or
stops on an error is recorded as `FAIL` and the remaining checks still run.

The script's own behaviour is tested in throwaway repositories (planted secret in the tree, secret
only in the history, hyphen-joined password, allowlisted value exact and as a substring,
unreadable file, missing history object, string-built SQL), with and without gitleaks:

```bash
bash scripts/security/selftest-security-scan.sh
```

Degradation modes:

- **No gitleaks on the `PATH`:** both secret checks fall back to a pattern set (private key
  headers, AWS access keys, GitHub and Slack tokens, JWTs, key/token/secret/password assignments
  with values of 16 or more characters): `grep` over the same copy of the working tree, and
  `git grep` over every revision of every ref (an execution error is a failure, never "no
  match"). A match is suppressed only when its credential value equals one of the reviewed values
  of `.gitleaks.toml` exactly; the checks report `DEGRADED`. Install gitleaks 8.30.1 to get the
  full rule set.
- **No `NVD_API_KEY`:** `dependency-check` reports `SKIPPED(no NVD_API_KEY)`. Without an NVD API key
  the initial NVD download is rate limited to the point of taking well over 25 minutes, so the
  check does not run. To enable it, request a key at https://nvd.nist.gov/developers/request-an-api-key,
  export it as `NVD_API_KEY` (and `NVD_DATA_DIR` to keep the feed between runs), and make sure the
  `org.owasp.dependencycheck` plugin is applied in `services/api/build.gradle.kts`; with a key but
  without the plugin the check fails instead of skipping.
- **No network:** `npm-audit` and `pip-audit` need their advisory services and fail when they
  cannot reach them; an audit that did not run is never reported as passed.

When a secret is found: revoke or rotate it at its issuer first (section 6), then remove it from
the code. A value that is synthetic test data is added to `.gitleaks.toml` only after confirming it
grants access to nothing. History is not rewritten without the owner's decision.

## 13. Health and metrics

| Endpoint | Access | Answer |
|---|---|---|
| `GET /api/v1/health` | public, through nginx | `{"status":"UP"}` or `DEGRADED` with `200`, `DOWN` with `503`; cached for 10 seconds; no detail |
| `GET /api/v1/admin/health` | ADMIN | scoring, database, FX freshness, outbox backlog, dead letters and the last run of every job; never cached |
| `GET /api/v1/admin/outbox/stats` | ADMIN | pending, failed, dead-letter counts and the oldest pending event |
| `/actuator/health`, `/actuator/metrics` | inside the API container only (management port 8081, not published, not proxied; nginx answers `404` for `/actuator`) | health without details; Micrometer metrics |
| `GET /internal/v1/health` | inside the compose `scoring` network only | scoring service status and engine parameters |

```bash
dc exec -T api curl -s http://127.0.0.1:8081/actuator/health
dc exec -T api curl -s http://127.0.0.1:8081/actuator/metrics
dc exec -T api curl -s http://127.0.0.1:8081/actuator/metrics/vitadelta.outbox.pending
dc exec -T scoring python -c "import urllib.request; print(urllib.request.urlopen('http://127.0.0.1:8000/internal/v1/health').read().decode())"
```

Only `health` and `metrics` are exposed; `env`, `heapdump`, `threaddump` and `configprops` are not.
Application metrics include `vitadelta.scoring.latency`, `vitadelta.recommendation.quota.rejected`,
`vitadelta.outbox.pending`, `vitadelta.outbox.failed`, `vitadelta.outbox.dead.letters`,
`vitadelta.alerts.email.sent`, `vitadelta.alerts.email.failed`, `vitadelta.mail.sent`,
`vitadelta.mail.failed`, `vitadelta.mail.dropped`, `vitadelta.fx.refresh.success`,
`vitadelta.fx.refresh.failure`, `vitadelta.fx.refresh.unsupported`, `vitadelta.fx.rate.age`,
`vitadelta.housekeeping.identity.purged`, `vitadelta.housekeeping.alerts.purged`,
`vitadelta.subscription.expiry.downgraded` and the Resilience4j circuit-breaker metrics
(`resilience4j.circuitbreaker.state`). Counters appear after their first increment.

## 14. Logs

```bash
dc logs -f api          # one JSON object per line (logstash layout)
dc logs -f scoring      # one JSON object per line
dc logs -f web          # nginx access log
```

- API: fields `@timestamp`, `level`, `logger_name`, `thread_name`, `message` and, where they apply,
  `requestId`, `principalType`, `country`, `catalogVersion` (requests) and `job` (scheduled jobs).
  Example:
  `{"@timestamp":"2026-11-25T11:34:58.06491672Z","@version":"1","message":"Started VitadeltaApiApplication in 4.966 seconds (process running for 5.243)","logger_name":"com.vitadelta.api.VitadeltaApiApplication","thread_name":"main","level":"INFO","level_value":20000}`
- Scoring service: `timestamp`, `level`, `logger`, `message`, `request_id`, `method`, `path`,
  `status`, `duration_ms`.
- nginx: `$remote_addr [time] "METHOD path PROTOCOL" status bytes seconds "user agent"
  request_id="..."`. The path is logged without its query string and without the referrer, and
  sanitised: everything after a `/promo-codes/` segment and everything from the first token-like
  segment on (32 or more base64url characters that are not a UUID, or a JWT) is replaced by
  `<redacted>`. Application routes served by the single-page app are logged as `/index.html`.
- Request ids: a client `X-Request-Id` matching `^[A-Za-z0-9-]{1,64}$` is passed through nginx and
  used by the API and the scoring service; any other value is dropped and a new id is generated.
  Every problem response carries `requestId`, so a user's error report leads to the log lines.
- Redaction: every API log line is filtered before it is written. `Authorization` header values
  and bearer tokens, JWTs anywhere in the text, `vd_refresh` and `vd_anon` cookie values, password
  and token fields (JSON, form and `key=value` notation, `#token=` fragments) are removed, and
  e-mail addresses are masked as `a***@domain`. This covers exception messages and third-party
  log lines. E-mail tokens travel in URL fragments, which browsers never send, so they never reach
  nginx either.
- Never paste raw logs into an issue without checking them; the redaction is the last line of
  defence, not a reason to log secrets.

## 15. Tests

```bash
bash scripts/test-all.sh                         # every suite, summary table, non-zero on failure
RUN_E2E=1 bash scripts/test-all.sh               # also nginx routing check and the browser suites
```

`RUN_E2E=1` needs the stack running and seeded with the bootstrap administrator (section 2) and
`JAVA_HOME` pointing to JDK 25. The e2e step downloads the headless Chromium shell once, runs
`npm run e2e`, restarts the API (its rate limits are in memory) and runs `npm run e2e:adversarial`.
For a second run on the same stack, restart the API first (`dc restart api`) or start from section
2 again. `RUN_SECURITY_SCAN=0` leaves the security scan out of `test-all.sh` (CI runs it in its own
job). The nginx check alone: `bash infra/nginx/check-routing.sh` (needs `npm run build` in
`apps/web` first; it builds the `runtime` stage of `infra/nginx/Dockerfile`, so it checks the
pinned image, the configuration and the unprivileged user exactly as shipped).

In CI the browser suites run with trace capture off (`--trace off`): a Playwright trace holds
request and response bodies, cookies and typed input, and the administrator flows type the
generated password. A failed run uploads only the JUnit reports, scrubbed of every secret value of
`.env` and of token-shaped strings, and the failure screenshots
(`scripts/ci/collect-e2e-artifacts.sh`); the generated secrets of `.env` are masked in the job log
(`scripts/ci/mask-env-secrets.sh`).

## 16. Common failures

| Symptom | Cause | Fix |
|---|---|---|
| `docker daemon is not reachable` | Docker is not running | start Docker Desktop or the daemon |
| `Bind for 0.0.0.0:8080 failed: port is already allocated` (or 8025) | another process or a second stack uses the port | stop it; only one VitaDelta stack runs per host |
| `Pool overlaps with other one on this network` | another Docker network uses `172.30.80.0/24` | remove that network or the other stack (`docker network ls`) |
| compose: `VITADELTA_... must be set in .env` | `.env` predates a new variable in `.env.example` | add the variable from `.env.example` to `.env` with a value, or move `.env` away and run `scripts/dev-up.sh` to create a new one |
| API container restarts, log `Failed to bind properties` or a `VITADELTA_` name | a required setting is missing or invalid (JWT secret shorter than 32 bytes, non-https base URL outside dev, missing privacy contact) | fix `.env` and `dc up -d --wait api` |
| API startup fails naming the bootstrap administrator | the bootstrap e-mail belongs to an existing USER | section 5 |
| `seed data is invalid; nothing was written` (exit 2) | a row under `data/` breaks a rule | fix the row named in the output and run the seed again |
| `503 scoring_unavailable` | scoring service down or the circuit breaker is open (opens at 50 % failures over 20 calls, stays open 30 seconds) | `dc ps scoring`, `dc logs scoring`; `dc up -d --wait scoring` |
| `429 rate_limited` while testing | in-memory per-IP limits (registration, login, anonymous queries) | wait for `Retry-After`, or `dc restart api` on a local stack |
| e2e: `The e2e suite needs the bootstrap admin` | no bootstrap administrator in `.env` | `bash scripts/dev-up.sh --bootstrap-admin`; on an existing database the account is created at the next API start (section 5) |
| e2e failures after a first green run | rate limits of the previous run | `dc restart api`, or start from section 2 |
| `./gradlew` uses the wrong Java | `JAVA_HOME` is not JDK 25 | `export JAVA_HOME=<path of the JDK 25 installation>` |
| API tests fail to start containers | Testcontainers needs a running Docker daemon | start Docker |
| `$'\r': command not found` in a script | the checkout converted line endings | `.gitattributes` keeps `*.sh` at LF; run `git add --renormalize .` or check out again |
| prices not converted | FX | section 8 |
| alert e-mails missing | SMTP or the outbox | section 9; locally check Mailpit at http://127.0.0.1:8025 |
| `[backup] ERROR: 'age' is required` | age not installed | install age (section 11.1); no backup is written without it |
| restore smoke fails on row counts | wrong or empty dump, or a dump of another database | check the dump file and its timestamp; `bash scripts/restore.sh --down` and retry with another dump |
