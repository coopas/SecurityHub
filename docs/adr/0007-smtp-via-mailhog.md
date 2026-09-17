# ADR 0007 — Transactional mail over SMTP, with MailHog for local runs

- Status: accepted
- Date: 2026-09-17

## Context

Password recovery and user invitations both deliver a single-use link by e-mail. The project
had no mail dependency, no SMTP configuration and no provider.

Whatever is chosen has to keep `docker compose up` working as one command for someone who has
just cloned the repository, because that is how the demo is meant to be evaluated.

## Decision

Use `spring-boot-starter-mail` against plain SMTP, and add a **MailHog** container to
`docker-compose.yml` (SMTP on 1025, web inbox on 8025). Host and port come from `MAIL_HOST` and
`MAIL_PORT`, so a real deployment points them at a real relay without touching code.

## Alternatives considered

- **A hosted provider (SendGrid, SES, Mailgun).** Needs an API key, which cannot be committed,
  so `docker compose up` would no longer demonstrate the flow. Rejected.
- **Log the link instead of sending it.** No dependency, but the reader has to dig through
  container logs, and the code path that a real deployment uses would never be exercised.
- **A real SMTP host configured by the reader.** Correct for production and useless for a first
  run.

MailHog accepts everything, stores nothing outside the container and has no credentials, so it
is the local half of the same code path a relay would serve.

## Consequences

- One more container in the development stack. It carries no healthcheck and nothing
  `depends_on` it: the image is scratch-based with no shell, and the backend is written to
  tolerate a dead SMTP.
- **Delivery failure must never fail the request.** Sending happens after the transaction
  commits and off the request thread, and an exception is logged rather than propagated. Two
  reasons: a link must not point at a row that has not been committed yet, and the SMTP round
  trip is the only timing difference between a known and an unknown address on the
  password-reset endpoint — leaving it on the request thread would turn that endpoint into an
  account-enumeration oracle.
- This is the first `@Async` in the project. `@EnableAsync` was already present with no
  annotated method and no executor, which silently promised an unbounded thread per call; a
  bounded `ThreadPoolTaskExecutor` with an explicit rejection policy is defined alongside it.
