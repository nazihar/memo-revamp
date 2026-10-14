# E01 Walking skeleton — tasks

Vertical slices, in order. "∥" marks tasks that can run in parallel.

| # | Task | Size | Depends |
|---|---|---|---|
| T1 | Gradle skeleton: wrapper, catalog, build-logic, `:backend` boots, `:shared` KMP, ktlint/detekt | M | — |
| T2 | Modulith modules + `verify()` test + Spring Security deny-by-default + Problem Details | S | T1 |
| T3 | Compose local stack (core/full) + storage-init + generated `.env` | M | — ∥ T1 |
| T4 | Flyway + jOOQ `system_info` + health slice (all five probes) with tests | M | T2, T3 |
| T5 | OpenAPI 3.1 contract generation + drift test | S | T4 |
| T6 | Web skeleton: Vite/React/TS strict/TanStack Router+Query, generated client, health page, Vitest | M | T5 |
| T7 | `pnpm dev` entry point (ADR-0003) | S | T3, T4, T6 |
| T8 | Minimal CI (GitHub Actions: backend build+test, web typecheck+test+build) | S | T1, T6 |
| T9 | Docs scaffolding: dependency register, threat-model template, ASVS checklist skeleton, mobile READMEs, root README | S | ∥ |

## Acceptance criteria

**T1**
- Given a clean checkout and any JDK ≥ 17, when I run `./gradlew build`, then JDK 25 is provisioned, `:backend` and `:shared` compile, and the tests, ktlint and detekt all pass.
- `:shared` `jvmTest` runs a `commonTest` test.

**T2**
- When `ModularityTest` runs, all 18 expected modules are detected and `verify()` passes.
- Given no credentials, when I call `GET /api/v1/anything`, then I get 401 and never 200.
- An unhandled error returns `application/problem+json` without a stack trace.

**T3**
- When I run `pnpm stack:up --core` (or `--full`), then all services become healthy.
- `records` has object lock + versioning, and `work` exists.
- ONLYOFFICE has no route to the internet.
- Running it a second time is a no-op.

**T4**
- Given the stack is up, when I call `GET /api/v1/health`, then I get 200 with `status=UP` and the five components.
- Given ClamAV stopped, status is `DEGRADED` (200).
- Given the DB is down, status is `DOWN` (503).
- A component that is not configured shows `DISABLED`.
- The body contains only the documented fields.

**T5**
- `./gradlew :backend:updateContract` writes `contracts/openapi.json` with `"openapi": "3.1.x"`.
- Changing an endpoint without updating the contract fails `./gradlew :backend:test`.

**T6**
- `pnpm --filter web generate:api` reproduces the committed client, with no diff.
- The health page renders the overall status and the components from the generated `queryOptions`.
- Vitest covers the loading, success and error states.

**T7**
- `pnpm dev` (full) and `pnpm dev --core` bring up the stack, backend and web. Ctrl-C stops backend and web; the stack keeps running.

**T8**
- A push or PR runs the backend job (`./gradlew build`, including Testcontainers tests) and the web job (`pnpm install --frozen-lockfile`, typecheck, test, build).

**T9**
- `docs/dependencies.md` lists every dependency added in E01 with version, license, purpose, maintenance and alternatives.
