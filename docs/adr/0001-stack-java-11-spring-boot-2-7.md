# ADR 0001 — Java 11 with Spring Boot 2.7.18

- Status: accepted
- Date: 2026-09-17

## Context

SecurityHub is a portfolio project that must demonstrate competence on a stack that is
still widely deployed in enterprises. A large share of the Java systems in production
today run on Java 11 and the Spring Boot 2.7 line, which is the last 2.x release train.

Spring Boot 3 requires Java 17 and moves the whole platform from `javax.*` to
`jakarta.*`. Adopting it would make the codebase unable to demonstrate the Java 11
target, and would silently invalidate every code sample in this repository for teams
still on the 2.x line.

## Decision

- Backend targets **Java 11** and **Spring Boot 2.7.18**.
- All Jakarta EE APIs are imported from **`javax.*`** — `javax.persistence`,
  `javax.validation`, `javax.servlet`. `jakarta.*` is forbidden.
- Language level stays at 11: no records, no sealed types, no pattern matching for
  `instanceof`, no text blocks in `src/main`.
- Spring Security 5.7 APIs are used (`SecurityFilterChain` bean style, not the
  deprecated `WebSecurityConfigurerAdapter`).
- Upgrading any of these requires superseding this ADR.

## Consequences

- The project stays compatible with the very common Java 11 baseline.
- We give up Spring Boot 3 features (native images via AOT, Micrometer tracing 1.x
  observability API, Problem Details `RFC 7807` support out of the box). The error
  envelope is therefore implemented by hand in `shared/error`.
- Spring Boot 2.7 reached end of OSS support, so dependency versions that ship known
  CVEs must be pinned explicitly rather than inherited (see ADR 0002).
