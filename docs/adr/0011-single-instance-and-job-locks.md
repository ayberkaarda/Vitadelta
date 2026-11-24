# ADR-0011: Single API instance in v1 with advisory locks for scheduled jobs

- Status: Accepted
- Date: 2026-11-24

## Context

Rate limits (Bucket4j), the ranking and market-profile caches and the plan cache (Caffeine) are held in memory. The API also runs scheduled jobs that must not run twice at the same time: the outbox processor, the FX refresh, the subscription expiry and housekeeping, plus the admin bootstrap at startup. A distributed cache, a shared rate-limit store and a scheduler cluster would add infrastructure that v1 does not need.

## Decision

- v1 runs **exactly one API instance**. In-memory rate limits and caches are per instance and are correct only under this assumption.
- As a safeguard against an accidental second instance (for example during a deployment overlap), every scheduled job and the admin bootstrap wrap their run in `pg_try_advisory_lock(<fixed key>)` on a **dedicated connection**, with one fixed key per job. A run that does not obtain the lock **skips** and waits for its next schedule; it never blocks. The lock is released when the run ends.
- Jobs that take an advisory lock:

| Job | Package | Schedule |
|---|---|---|
| `OutboxProcessor` | `alerts` | fixed delay 10 s (ADR-0008) |
| `FxRefreshJob` | `fx` | at startup when rates are missing or older than 1 day, then every 6 hours (ADR-0009) |
| `SubscriptionExpiryJob` | `entitlement` / `billing` | daily at 00:05 UTC (ADR-0010) |
| `HousekeepingJob` | `identity` and `alerts` | daily at 03:15 UTC |
| Admin bootstrap | `identity` | once at startup |

- The fixed lock key constants are defined in the API `common` package when the first job lands in Phase 3, and this ADR is updated with the key of every job at that time. Keys are never reused across jobs.
- **Housekeeping** purges in batches of 1000 rows in short transactions: anonymous identities not seen for 180 days; outbox events processed more than 30 days ago (dead letters after 90 days) with their orphaned `alert_delivery` rows; e-mail tokens expired or used more than 7 days ago; refresh tokens expired or revoked more than 30 days ago and grace successors older than the grace window; unverified accounts older than 30 days that never logged in. Each purge logs counts and exposes a metric; retention values are configuration keys.
- **Horizontal scaling is out of scope** for v1. It would require moving rate limits and caches to shared storage.

## Consequences

- No extra infrastructure for coordination; PostgreSQL, which is already required, provides the locks.
- A second instance would not run jobs concurrently, but it would split rate-limit buckets and caches; this is why a single instance is a deployment rule, not only a lock.
- Session-level advisory locks need the dedicated connection to stay open for the run; the connection is returned to the pool afterwards.
- A test asserts that the advisory lock prevents concurrent job runs.

## Alternatives considered

- **ShedLock or a lock table.** Rejected: an additional dependency or table for what PostgreSQL advisory locks provide natively.
- **Quartz in clustered mode.** Rejected: heavy for four simple jobs.
- **Shared Redis for rate limits and caches now.** Rejected for v1: an additional service with no need while one instance serves the load.
- **Transaction-level advisory locks (`pg_try_advisory_xact_lock`).** Rejected: the jobs deliberately use several short transactions per run (ADR-0008), so the lock must outlive individual transactions.
