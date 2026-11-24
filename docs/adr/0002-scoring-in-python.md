# ADR-0002: Scoring in Python as a stateless service; Java is the only database owner

- Status: Accepted
- Date: 2026-11-24

## Context

The scoring model (reference resolution, coverage ratios and utility, upper-limit handling, value score, ranking and pairwise verdicts) is pure numeric computation over a request payload. It has to be easy to read, test with property-based tests and recalibrate. At the same time the product needs persistence, identity, entitlements, caching, FX conversion and an outbox, which belong in the core API. The question is where the scoring code lives and who may touch the database.

## Decision

- Scoring is implemented in `services/scoring` as a separate **Python** service (Python 3.14, FastAPI, Pydantic, managed with `uv` and a committed `uv.lock`).
- The service is **stateless**: every request carries the candidates, the market profile, the reference values, the upper limits and the weights. Its functions are pure functions over that payload.
- The scoring service has **no database access, makes no outbound HTTP calls and does no money conversion**. It is reachable only from the core API on `/internal/v1/*` and has no published port in the default Compose file.
- The core API (`services/api`, Java) is the **only owner of the database**: it reads catalog data, builds the scoring payload, calls the service, and applies locking, display-currency conversion and paging after scoring.
- The interface is defined in `contracts/scoring-api.openapi.yaml`; the Java client is generated from it (ADR-0001). Numeric values crossing the JSON boundary follow the rounding rules of the project specification so that both sides agree on every displayed figure.

**Owner decision.** The alternative below was presented at the Phase 0 gate. On 2026-11-24 the owner decided to keep scoring in Python as described here. This decision can be revisited only through a new ADR that supersedes this one.

## Consequences

- The scoring model can be developed and tested in isolation (pytest, Hypothesis) with no database or network fixtures.
- Each query pays one internal network hop. Timeouts, a retry policy, a circuit breaker and the degraded `503 scoring_unavailable` mode are required (ADR-0003).
- Request and response models exist on both sides (Pydantic models and the generated Java client). They are kept in sync only through the contract file, and contract tests guard it.
- A third toolchain (Python with `uv`), its CI job and its dependency audit (`pip-audit`) must be maintained.
- Values that cross the JSON boundary carry a cross-language rounding risk; the specification's rounding rules and the golden tests cover it.
- Because the service holds no state, it can be restarted or scaled without coordination.

## Alternatives considered

- **Scoring as a module inside the Java API (modular monolith).** The scoring rules would be implemented as a package of the core API with an enforced module boundary. This would remove:
  - the `services/scoring` deployable and its container;
  - `contracts/scoring-api.openapi.yaml` and the generated Java client;
  - the network hop with its timeout, retry and circuit-breaker configuration, and the `503 scoring_unavailable` degraded mode;
  - the duplicated request and response models;
  - the Python toolchain, its CI job and `pip-audit`;
  - the cross-language rounding risk at the JSON boundary.

  It was not chosen: the owner prefers the separate Python service for the readability and testability of the numeric model and for keeping the scoring code free of persistence concerns. The cost listed above is accepted.
- **Scoring service with its own read access to the database.** Rejected: two database owners would split schema ownership and migrations and make catalog-version consistency between ranking and caching harder to guarantee.
