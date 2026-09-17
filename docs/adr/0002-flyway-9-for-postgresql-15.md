# ADR 0002 — Override Flyway to 9.22.3

- Status: accepted
- Date: 2026-09-17

## Context

Spring Boot 2.7.18 manages **Flyway 8.5.13**. Flyway validates the database version it
connects to against a supported range, and 8.5.13 predates PostgreSQL 15. Running it
against the PostgreSQL 15 image required by the project rules fails at startup with
`Unsupported Database: PostgreSQL 15.x`.

Options considered:

1. Downgrade the database to PostgreSQL 14 — rejected, the project rules §2 pins PostgreSQL 15.
2. Set `flyway.validate-migration-naming`/ignore flags — does not apply, the failure is
   a hard database-version check, not a naming check.
3. Override the managed Flyway version.

## Decision

Override the Spring Boot managed property in `backend/pom.xml`:

```xml
<flyway.version>9.22.3</flyway.version>
```

9.22.3 is the last 9.x release, supports PostgreSQL 15 and 16, and still targets
Java 11, so it does not conflict with ADR 0001. Flyway 10 is **not** used because it
requires Java 17.

## Consequences

- Migrations run on an empty PostgreSQL 15 database without extra flags.
- Flyway 9 changed the default for `baselineOnMigrate` handling of non-empty schemas;
  the project always migrates from an empty schema, so no baseline is configured.
- The override is a deliberate deviation from the Spring Boot BOM and must be revisited
  whenever Spring Boot or PostgreSQL is upgraded.
