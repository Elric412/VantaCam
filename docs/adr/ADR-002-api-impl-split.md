# ADR-002: API/Implementation module split for every Layer 2+ module

## Status
Accepted

## Context
The Leica Cam platform is built around a layered architecture with strict downward-only dependencies to ensure a sealed module contract. In the past, single modules might have exposed implementation details inadvertently, leading to tight coupling between engines and orchestration layers. We considered using a single module approach with `@Internal` annotations, but this can still be bypassed or misconfigured, weakening the architectural boundaries.

## Decision
We will enforce an API/Implementation module split for every Layer 2+ module (e.g., `camera-core-api` vs `camera-core-impl`). All cross-module dependencies must rely solely on the `-api` artifacts, which contain only sealed interfaces, data models, and type aliases. The implementation modules will never be referenced across module boundaries, enforced by Gradle's `api`/`implementation` scoping.

## Consequences
- **Positive:** Strongly sealed module contracts prevent tight coupling and hidden dependencies.
- **Positive:** Build times and parallel compilation will improve as API modules change less frequently.
- **Negative:** Increased number of modules to maintain, requiring more boilerplate for API definitions.
