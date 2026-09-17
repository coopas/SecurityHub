# ADR 0005 — Pin the Docker API version used by Testcontainers

- Status: accepted
- Date: 2026-09-17

## Context

`./mvnw test` failed on every integration test with `Could not find a valid Docker
environment`, although the Docker daemon was reachable and `docker ps` worked. The real
error was only visible with `-Dlogging.level.org.testcontainers=DEBUG`, because
`application-test.yml` quiets those loggers:

```
UnixSocketClientProviderStrategy: failed with exception BadRequestException (Status 400:
{"message":"client version 1.32 is too old. Minimum supported API version is 1.44,
please upgrade your client to a newer version"}
```

The `docker-java` client bundled with Testcontainers negotiates Docker Engine API **1.32**.
Docker Engine **29** dropped support for every API below **1.44**, so the two cannot talk.

This is not a local quirk: the same failure hits any machine or CI runner with a recent
Docker Engine, and the `compose` job in `.github/workflows/ci.yml` runs on `ubuntu-latest`,
whose Docker version moves on its own schedule.

Upgrading Testcontainers does not fix it. Both **1.20.6** and **1.21.3** were tried and
both still negotiated 1.32, so the pinned API version — not the Testcontainers version —
is the actual variable.

## Decision

Pass the API version to `docker-java` as a system property from Surefire, driven by an
overridable Maven property in `backend/pom.xml`:

```xml
<docker.api.version>1.44</docker.api.version>
...
<systemPropertyVariables>
  <api.version>${docker.api.version}</api.version>
</systemPropertyVariables>
```

Testcontainers stays at **1.19.8**. The version was left untouched because the upgrade was
measured and changed nothing here; bumping it would have been an unverified stack change.

## Alternatives considered

- **`~/.testcontainers.properties` with `api.version=1.44`** — works, but it is a per-machine
  file outside the repository. A fresh clone would fail again, which is exactly what
  `` forbids.
- **`DOCKER_API_VERSION` environment variable** — tried and **does not work**; `docker-java`
  does not read that name. Only the `api.version` system property took effect.
- **Upgrading Testcontainers to 1.20.6 or 1.21.3** — tried, still negotiated 1.32.

## Consequences

- `./mvnw test` and `./mvnw verify` work with no external flags on Docker Engine 25.0+
  (API 1.44 first appeared in Engine 25.0, January 2024).
- On an engine older than 25.0 the build needs `-Ddocker.api.version=1.41`. This is
  documented in the README next to the test commands.
- The pin is a client-side ceiling, not a feature gate: Testcontainers only creates,
  starts, inspects and removes containers, all of which exist well below API 1.44.
- Revisit when Docker Engine raises its minimum again, or when a Testcontainers release
  negotiates the API version instead of hardcoding it.
