# SecurityHub architecture

This document describes the stable structural decisions of the system. Individual technology
decisions are recorded in `docs/adr/`.

## 1. Overview

```text
┌──────────────┐      HTTPS/JSON      ┌──────────────────┐      JDBC       ┌──────────────┐
│  Angular 16  │ ───────────────────▶ │  Spring Boot 2.7 │ ──────────────▶ │ PostgreSQL 15│
│  (nginx)     │  Bearer JWT           │  Java 11         │   HikariCP      │              │
└──────────────┘                       └──────────────────┘                 └──────────────┘
```

The frontend is an SPA served by nginx, which also acts as a reverse proxy for `/api` to the
backend — so the browser sees a single origin and there is no CORS in production. In
development, `ng serve` plays the same role through `proxy.conf.json`.

The backend is a stateless modular monolith: every request carries its own authentication
context in the token, which allows horizontal scaling without a shared session.

## 2. Backend organisation

The code is organised **by feature**, not by technical layer. Each module groups what changes
together:

```text
com.securityhub
├── config/          cross-cutting configuration (JPA auditing, OpenAPI)
├── security/        JWT, authentication filter, principal, CORS, filter chain
├── shared/
│   ├── error/       error envelope, domain exceptions, @RestControllerAdvice, traceId
│   ├── model/       BaseEntity with audited timestamps
│   ├── repository/  Specs — filter construction through the Criteria API
│   └── web/         PageResponse and Pageable sanitisation
├── company/  user/  auth/
├── project/  asset/  vulnerability/  comment/
├── audit/    dashboard/
└── SecurityHubApplication
```

Inside a module: `Controller` → `Service` → `Repository`, with `dto/` and a static mapper.
Business rules live in the service; the controller only translates HTTP.

### Why JPA entities do not leave through the API

`spring.jpa.open-in-view` is **disabled**. The Hibernate session closes at the end of the
transactional method, so mapping to a DTO happens inside the service. This avoids
`LazyInitializationException`, prevents the table shape from leaking into the HTTP contract and
eliminates queries fired accidentally during serialisation.

## 3. Isolation between companies

This is the most important security property of the system.

1. The `companyId` lives in the signed token and is revalidated against the user's row on
   **every** request by `JwtAuthenticationFilter`. A token whose `companyId` or `role` does not
   match the database is rejected, not merely ignored.
2. The `companyId` is **never** read from the body, the query string or a header. A `companyId`
   sent by the client is simply ignored.
3. Every repository signature carries the `companyId`: there is no loose `findById(id)` in the
   service layer. Filters are built with `Specs.company(...)`/`Specs.companyColumn(...)` as the
   first predicate.
4. A resource belonging to another company answers **404**, never 403. A 403 would confirm that
   the resource exists and turn the API into an enumeration oracle.

## 4. Authentication and authorisation

- Passwords with BCrypt cost 12.
- HS256 JWT access token containing `sub`, `companyId`, `role`, `iat` and `exp`. The secret
  comes from `SECURITYHUB_JWT_SECRET`; the application refuses to start without it or with
  fewer than 32 bytes.
- Login answers the same generic message for a non-existent e-mail, a wrong password and an
  inactive account, and performs a throwaway BCrypt check for an unknown e-mail to equalise the
  response time.
- Role authorisation is applied with `@PreAuthorize` **on service methods**. The Angular
  interface hides buttons for convenience, but no rule depends on that: the integration tests
  exercise every role against every endpoint.

Permission matrix: see `docs/permissions.md`, which is the source of truth.

## 5. HTTP contract

Prefix `/api/v1`, camelCase JSON, ISO-8601 instants in UTC.

Listings always use server-side pagination with the same envelope:

```json
{ "content": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0, "sort": "createdAt,desc" }
```

The `sort` parameter goes through a per-module allowlist (`PageableSupport.sanitize`). An
unexpected property is discarded instead of reaching Spring Data as an arbitrary path — which
avoids 500s and access to unintended associations. Page size is capped at 100.

Errors follow a single envelope produced by `GlobalExceptionHandler`, with no stack trace, SQL
or internal detail, and with a `traceId` that also goes in the `X-Request-Id` header and in
every log line of the request.

## 6. Filters with the Criteria API

Optional filters are **not** written as `:param is null or column = :param`. PostgreSQL cannot
infer the type of a null parameter in that position and answers
`could not determine data type of parameter`. Each module builds a `Specification` with
`shared/repository/Specs`, which simply omits the missing predicate — which also lets the
planner use the partial indexes.

## 7. Auditing

`audit_logs` is append-only: the entity has no `updated_at`, no endpoint writes to it and the
repository is not exposed to the API layer.

There are two write semantics, and the choice matters:

| Method | Propagation | When to use |
| --- | --- | --- |
| `record` | `REQUIRED` | domain mutations — the change and its record go in together or not at all |
| `recordIndependently` | `REQUIRES_NEW` | authentication events — has to survive the rollback of a refused login |

Using `REQUIRES_NEW` for rows created in the same transaction breaks the foreign keys, because
the independent transaction cannot yet see the new rows.

Before serialising, `AuditSanitizer` replaces with `***` any key whose name contains sensitive
fragments (`password`, `senha`, `hash`, `token`, `secret`, `credential`, …), recursively in maps
and lists.

## 8. Database

Flyway migrations versioned in `backend/src/main/resources/db/migration`, applied at
application startup. `ddl-auto` is `validate`: Hibernate never changes the schema, it only
checks that the mapping matches what the migration created.

Enums are stored as `VARCHAR` with a `CHECK`, not as PostgreSQL enum types: adding a value
becomes a constraint change, with no `ALTER TYPE` and without locking the table.

Every foreign key and frequently filtered column has an index, always with `company_id` as the
first column of the composite index, which is how the queries actually arrive.

## 9. Frontend

Angular modules lazily loaded per feature. State in services with RxJS; there is no NgRx in the
MVP because there is no state shared between features that would justify the cost.

- `core/` — session, interceptors, guards, shared models. Imported exactly once.
- `shared/` — re-export module for Material and reusable components (`confirm-dialog`,
  `state-message`).
- `layout/` — the authenticated shell (toolbar, responsive sidenav, skip link).
- `features/` — one folder per domain, each one a lazy module.

The token is attached only to calls to our own API. A 401 outside the authentication screens
ends the session and redirects to `/login` preserving `returnUrl`; a 403 leads to the `/403`
page. Every asynchronous screen handles the loading, empty, error and permission states.

## 10. Tests

| Level | Tooling | What it covers |
| --- | --- | --- |
| Backend unit | JUnit 5 + Mockito | isolated business rules, no Spring context |
| Backend integration | Spring Boot Test + Testcontainers | real PostgreSQL 15, real HTTP through MockMvc, roles and isolation between companies |
| Frontend unit | Jasmine/Karma | services, guards, interceptors and components |
| End to end | `scripts/smoke-test.sh` | the whole stack running, with real data |

The integration tests do not use H2. An in-memory database with a different dialect would not
prove partial indexes, `CHECK` constraints, `TIMESTAMPTZ` types or the null-parameter behaviour
that motivated section 6.

Each test runs against a truncated database, not inside a rolled-back transaction, so that the
commit path is actually exercised.
