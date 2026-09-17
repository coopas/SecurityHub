# ADR 0004 — E-mail is globally unique, not unique per company

- Status: accepted
- Date: 2026-09-17

## Context

The domain model specifies that the user e-mail is "unique per company". The API contract specifies
`POST /auth/login` with no company discriminator in the request — the client sends only
an e-mail and a password.

Those two rules conflict. With uniqueness scoped per company, the same address can exist
in two companies, and the login endpoint has no way to decide which account the
credential refers to. Resolving it by trying the password against every matching row
would leak account existence across tenants and turn login into an oracle.

Alternatives considered:

1. Add a company slug to the login request — changes the API contract in §8 and worsens
   the demo flow.
2. Pick the first match — non-deterministic and a cross-tenant information leak.
3. Tighten the constraint to a global unique index.

## Decision

`users.email` carries a **global** `UNIQUE` constraint. Since §5 states that a user
belongs to exactly one company in the MVP, global uniqueness is strictly stronger than
the documented per-company rule and therefore still satisfies it.

E-mails are normalised to lowercase in the application layer, and the database enforces
that with `CHECK (email = lower(email))` so no code path can bypass the normalisation.

## Consequences

- Login stays a single deterministic lookup by e-mail.
- One physical person cannot hold accounts in two companies with the same address. If
  multi-company membership is ever introduced (out of scope per §4), this ADR must be
  superseded together with a membership table and a company selector at login.
- Registration returns `409 CONFLICT` when the address is already taken, which is
  acceptable: the registration endpoint is public and creating a company is a deliberate
  act, unlike login where enumeration must be prevented.
