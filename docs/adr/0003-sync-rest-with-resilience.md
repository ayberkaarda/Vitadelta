# ADR-0003: Synchronous REST between API and scoring with timeout, retry and circuit breaker

- Status: Accepted
- Date: 2026-11-24

## Context

A recommendation query or a pairwise comparison needs the scoring result before the API can answer (ADR-0002). The user waits for the response, so the call is on the request path. The scoring service can be slow or unavailable; the API must stay responsive, must not hold database resources while waiting, and must not charge an anonymous user's trial quota for a query that failed.

## Decision

- The API calls the scoring service synchronously over REST/JSON (`POST /internal/v1/score`, `POST /internal/v1/compare`) through the client generated from `contracts/scoring-api.openapi.yaml`.
- Resilience parameters (Resilience4j):

| Parameter | Value |
|---|---|
| Connect timeout | 500 ms |
| Read timeout | 2 s |
| Retry | 1 retry, on connection errors only; never on 4xx or 5xx responses |
| Circuit breaker failure threshold | 50 % |
| Circuit breaker sliding window | 20 calls |
| Circuit breaker open state | 30 s |

- **Degraded mode:** when the circuit is open or the call fails, the API responds `503` with problem type `scoring_unavailable`. Rankings already in the cache continue to be served for cache hits.
- **Quota:** an anonymous query that ends in `503` does not consume quota. The quota is reserved by an atomic conditional `UPDATE` in its own short transaction before scoring and refunded on a scoring failure in a second short transaction (`UPDATE anonymous_identity SET queries_used = queries_used - 1 WHERE id_hash = ? AND queries_used > 0`).
- **No resources held across the call:** catalog data for the scoring input is read in a separate read-only transaction that is closed before the call; no database connection or transaction is held while waiting for the scoring service.
- Circuit-breaker state and scoring latency are exposed as metrics.

## Consequences

- Worst-case latency of a failing call is bounded (connect timeout plus one retry, or the read timeout), and an outage of the scoring service degrades to fast `503` responses once the circuit opens.
- The connection pool cannot be exhausted by slow scoring calls.
- Because retries happen only on connection errors, a request that reached the service is never executed twice by the client.
- The web app must handle `scoring_unavailable` with a clear message and must not count the attempt against the trial quota shown to the user.

## Alternatives considered

- **Asynchronous scoring through a queue with polling or push.** Rejected: it adds a broker and a result store for a computation that takes milliseconds, and the user waits for the result anyway.
- **Retries on 5xx responses.** Rejected: a 5xx from a deterministic, stateless service is almost always a defect that repeats; retrying only adds latency and load.
- **No circuit breaker, timeouts only.** Rejected: during an outage every request would wait for the full timeout before failing.
