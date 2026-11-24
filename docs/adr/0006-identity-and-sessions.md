# ADR-0006: Identity: Argon2id passwords, HS256 access tokens with key id, rotating refresh tokens, signed anonymous cookie

- Status: Accepted
- Date: 2026-11-24

## Context

The product has three principals: anonymous visitors with a small trial quota, registered users (FREE or PRO plan) and administrators. The web app and the API are served from one origin behind nginx. The identity design must store passwords safely, allow secret rotation without logging everyone out, survive concurrent tabs refreshing at the same time, detect stolen refresh tokens, and identify anonymous visitors without accounts.

## Decision

**Password hashing.** Argon2id through Spring Security's `Argon2PasswordEncoder` on BouncyCastle, with explicit parameters (not the library defaults):

| Parameter | Value |
|---|---|
| Salt length | 16 bytes |
| Hash length | 32 bytes |
| Parallelism | 1 |
| Memory | 19 MiB (19456 KiB) |
| Iterations | 2 |

For an unknown e-mail, login verifies the password against a fixed dummy Argon2id hash with the same parameters, so both paths take the same time. Registration performs exactly one hash on both the new-account and the already-registered path.

**Access token.** JWT signed with **HS256** using `VITADELTA_JWT_SECRET` (at least 32 bytes; startup fails with a clear message if missing or shorter), lifetime 15 minutes, header **`kid`**, claims `sub`, `role`, `email_verified`, `jti`. There is no `plan` claim; the plan is always read from the database, so plan changes take effect immediately. `kid` is the first 16 hex characters of the SHA-256 of the signing secret. During a rotation the decoder accepts the current secret and `VITADELTA_JWT_SECRET_PREVIOUS`, selected by `kid`; tokens with an unknown `kid` are rejected. Tokens are issued with Spring Security's Nimbus `JwtEncoder` and validated by the resource-server filter. The web app keeps the access token in memory only.

**Refresh token.** 256-bit random value, sent only as cookie `vd_refresh` (`httpOnly`, `SameSite=Strict`, `Path=/api/v1/auth`, `Secure` outside `dev`), lifetime 30 days, stored as a SHA-256 hash with a `family_id`.

- `POST /auth/refresh` rotates: the presented token is revoked with `replaced_by`, and a new token in the same family is issued.
- **Grace window of 20 seconds:** if a token rotated less than 20 seconds ago is presented again (concurrent tabs, lost response), the API returns the same successor token with a fresh access token instead of treating it as reuse. The successor's plaintext is never stored; it is kept encrypted with a key derived from `VITADELTA_COOKIE_SECRET` only until the grace window ends.
- **Reuse detection:** a revoked token presented outside the grace window, or a token whose successor was itself already rotated, revokes the whole family (`401 refresh_reuse_detected`).
- Refresh re-reads the user status and returns `403 account_disabled` for a disabled user; disabling a user revokes all of the user's families in the same transaction.

**Anonymous identity.** Cookie `vd_anon`: 128-bit random id, HMAC-SHA256 signed with `VITADELTA_COOKIE_SECRET`, `httpOnly`, `SameSite=Lax`, `Secure` outside `dev`, max-age 180 days. The server row is keyed by the SHA-256 of the id and created with `INSERT ... ON CONFLICT DO NOTHING`, so concurrent first requests never fail or double-create. Clearing cookies resets the trial quota; per-IP rate limits make this best effort, which is accepted.

**Request forgery protection.** Refresh and logout require `X-Requested-With: vitadelta`; every cookie-setting or cookie-reading auth endpoint also requires `Content-Type: application/json` and, when an `Origin` header is present, an origin in `VITADELTA_WEB_ORIGINS`. Same-origin deployment is required for the `SameSite` model.

## Consequences

- Password hashes cost 19 MiB of memory per verification; login and registration rate limits bound the total cost.
- JWT secrets can be rotated without a forced logout by setting the previous secret for one access-token lifetime.
- Access tokens of a disabled user remain valid for at most 15 minutes; this window is accepted.
- The grace window keeps multi-tab sessions stable at the cost of a short-lived encrypted successor per rotation, which housekeeping purges.
- Because HS256 is symmetric, only the API can issue and validate tokens; no third party verifies them in v1.

## Alternatives considered

- **Library default Argon2 parameters.** Rejected: defaults can change between library versions and silently change hash cost; explicit parameters are testable and documented.
- **bcrypt.** Rejected: not memory-hard; Argon2id is the current recommendation for new systems.
- **RS256 or ES256 with a JWKS endpoint.** Rejected for v1: there is a single issuer and a single validator; asymmetric keys add key management without a consumer that needs them.
- **Refresh token in local storage, or access token in a cookie.** Rejected: exposes the long-lived token to script access, or makes every API call cookie-authenticated and CSRF-sensitive.
- **Strict reuse detection without a grace window.** Rejected: concurrent tabs refreshing at the same moment would log users out.
