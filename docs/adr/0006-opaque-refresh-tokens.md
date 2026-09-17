# ADR 0006 — Opaque refresh tokens with rotation and family revocation

- Status: accepted
- Date: 2026-09-17

## Context

The MVP issued only a short-lived access token. `AuthResponse` already declared a
`refreshToken` field that was always null, and `securityhub.jwt.refresh-expiration-days` was
bound and validated but read by nothing — the shape was reserved, the behaviour was not built.

A refresh token has to survive logout, a password change, a deactivation and a role change.
All four are revocation, and revocation needs server-side state.

## Decision

The refresh token is an **opaque random string**: 32 bytes from `SecureRandom`, Base64-URL
encoded. The database stores only its **lowercase SHA-256 hex**, under a unique index, with a
`CHECK` that the column matches `^[0-9a-f]{64}$`.

Every refresh **rotates**: the presented token is marked used and a new one is issued into the
same family. Presenting an already-rotated token outside a 30-second grace window is treated
as theft and **revokes the whole family**.

### Why opaque and not a JWT

Revocation forces a database row either way. Once that row exists, the JWT's only advantage —
validating without a lookup — is gone, and what remains is a second signing path.

It also removes a type-confusion problem instead of patching it. `JwtService.parse` has no
token-type claim, so a JWT refresh token would be accepted as an access token and vice versa.
Adding a `typ` claim would work, but an opaque token makes the confusion impossible: an access
token presented at `/auth/refresh` hashes to nothing, and an opaque token in an `Authorization`
header is not parseable as a JWT.

### Why SHA-256 and not BCrypt

The input is 256 bits from a CSPRNG, not a human password, so a dictionary attack is
meaningless and the slow hash buys nothing. BCrypt at cost 12 is also unsearchable: finding
which row a token belongs to would mean comparing against every row of that user. The property
that matters — a database dump yields no usable token — holds with SHA-256.

### Why a 30-second grace window

Two browser tabs, a network retry or a timeout make the same token arrive twice within
seconds. Without a window, reuse detection would log a legitimate user out on every benign
race. The lookup takes a row lock, so the second transaction re-reads the already-rotated row
and lands on the grace branch deterministically rather than racing the same update.

The window does not meaningfully help an attacker: a stolen token must be used within 30
seconds of the victim's own rotation, and the family still dies on the first reuse outside it.

## Consequences

- Refresh requires one indexed lookup. That is the cost of being able to revoke.
- The caller cannot tell which rejection fired: unknown, expired, revoked and reused all return
  the same status and message. Telling an attacker their theft was noticed helps only them; the
  audit trail is where that fact belongs.
- Expired rows are collected lazily on the login path. Rows belonging to someone who never logs
  in again are never collected — bounded by their historical session count, and the alternative
  was a scheduler firing inside every integration-test context.
- `family_id` is stored as `VARCHAR(36)` rather than `uuid`: Hibernate 5.6 maps `java.util.UUID`
  to `uuid-binary`, which fails `ddl-auto: validate` against a `uuid` column unless the entity
  carries `@Type("pg-uuid")`.
