# ADR 0003 — Hand-written mappers instead of MapStruct

- Status: accepted
- Date: 2026-09-17

## Context

the project rules §7 asks for a `mapper` component inside each backend module so that JPA
entities are never returned by the API. MapStruct is the usual choice, but it is an
annotation processor that must be ordered relative to Lombok's processor through
`annotationProcessorPaths` plus `lombok-mapstruct-binding`. That ordering breaks in
subtle ways across IDEs and Maven versions, and the failure mode is a confusing
"unknown property" at compile time.

The mappings in this project are flat: every DTO is a projection of one aggregate plus
one or two denormalised labels (project name on an asset, asset name on a
vulnerability). There is no nesting, no inheritance and no type conversion beyond enums.

## Decision

Each module gets a small `XxxMapper` class with plain static methods
(`toResponse`, `applyCreate`, `applyUpdate`). No MapStruct dependency.

## Consequences

- One less annotation processor; the build is reproducible and IDE-agnostic.
- Mappers must be kept in sync with DTOs by hand — acceptable because the mapper for
  each aggregate is under 60 lines and is exercised by the module's own tests.
- If the DTO surface grows substantially (nested projections, partial updates across
  aggregates), this decision should be revisited.
