# Dependency register

Required by `AGENTS.md §3.1`. Every dependency has a row here: what it is, its license, why we use it, and how healthy it is. All entries were verified on **2026-10-14** (sources: Maven Central metadata, the npm registry, GitHub releases, Docker Hub; see `docs/plan/phase-0-foundations/epic-01-walking-skeleton/research.md`).

Column meanings:
- **Scope:** `runtime` (shipped), `dev-only` (build, test or lint; never shipped), `service` (separate process we run, unmodified).
- **Maintenance:** latest release date and a judgement of maintainer activity.

## Backend (JVM)

| Name | Version | License | Scope | Purpose | Maintenance | Alternatives considered |
|---|---|---|---|---|---|---|
| Kotlin (stdlib, reflect, Gradle plugin) | 2.4.21 | Apache-2.0 | runtime | Language | 2026-10-08; JetBrains, very active | — (locked) |
| Spring Boot (webmvc, security, jooq, flyway starters) | 4.1.1 | Apache-2.0 | runtime | Application framework | 2026-08-20; VMware/Broadcom, very active | — (locked) |
| Spring Modulith (core, test) | 2.1.1 | Apache-2.0 | runtime / dev-only | Module boundaries, verification, event registry | 2026; active | ArchUnit only (less expressive) |
| springdoc-openapi (webmvc-api) | 3.1.1 | Apache-2.0 | runtime | OpenAPI 3.1 generation | 2026-09-06; active, small core team | swagger-core directly (more code) |
| Jackson 3 Kotlin module | Boot-managed (3.1.x) | Apache-2.0 | runtime | JSON for Kotlin types | active | — |
| Flyway (core, database-postgresql) | 12.4.0 (Boot-managed) | Apache-2.0 | runtime | Schema migrations | 2026; Redgate, active | Liquibase (XML or YAML, heavier) |
| jOOQ (Open Source Edition) | 3.21.7 (Boot-managed) | Apache-2.0 | runtime | Typed SQL | 2026-09; Data Geekery, active. OSS edition supports the latest PostgreSQL | Spring Data JDBC (less SQL control) |
| PostgreSQL JDBC | 42.7.13 (Boot-managed) | BSD-2-Clause | runtime | DB driver | 2026-10; active | — |
| AWS SDK for Java v2 (s3, url-connection-client) | 2.55.16 | Apache-2.0 | runtime | S3 API client (locked decision) | released daily | — (locked) |
| JSpecify | Boot-managed | Apache-2.0 | runtime | Nullness annotations | active | — |
| Spring Modulith runtime | 2.1.1 | Apache-2.0 | runtime | Per-module Flyway migrations in dependency order (ADR-0007) | 2026; active | Single Flyway location (no module ownership) |
| FasterXML java-uuid-generator (JUG) | 5.2.0 | Apache-2.0 | runtime | UUIDv7 ids (`TimeBasedEpochGenerator`, ADR-0008) | 2025-12; FasterXML, active | uuid-creator (single maintainer); hand-written |
| jOOQ codegen + meta | 3.21.7 (Boot-managed) | Apache-2.0 | dev-only | Generates committed jOOQ sources in `JooqCodegenTest` (ADR-0007) | active | jOOQ Gradle plugin at compile time |
| jqwik | 1.10.2 | EPL-2.0 | dev-only | Property-based and stateful tests (ADR-0008) | 2026-10-10; one main maintainer (accepted, dev-only) | Kotest property |
| JUnit Jupiter | 6.0.3 (Boot-managed) | EPL-2.0 | dev-only | Test framework | active | — |
| AssertJ | 3.27.7 (Boot-managed) | Apache-2.0 | dev-only | Assertions | active | Kotest assertions (decided in E02, ADR-TL) |
| MockK | 1.14.11 | Apache-2.0 | dev-only | Mocks at boundaries only | 2026-05; active | Mockito-Kotlin |
| Testcontainers (junit-jupiter, postgresql) | 2.0.5 (Boot-managed) | MIT | dev-only | Real dependencies in tests | 2026-04; active | — |
| ArchUnit | via Spring Modulith | Apache-2.0 | dev-only | Architecture rules | 2026-09; active | — |

## Build tooling

| Name | Version | License | Scope | Purpose | Maintenance | Alternatives considered |
|---|---|---|---|---|---|---|
| Gradle (wrapper) | 9.8.1 | Apache-2.0 | dev-only | Build | 2026-10-07; active | Maven (no KMP) |
| foojay toolchain resolver | 1.0.0 | Apache-2.0 | dev-only | Provisions JDK 25 | 2025-05; stable | Preinstalled JDKs only |
| ktlint (via org.jlleitschuh.gradle.ktlint 14.2.0) | 1.8.0 | MIT | dev-only | Kotlin formatting and style | 2025-12 / 2026-03; active | Spotless + ktlint |
| detekt | **2.0.0-alpha.6** (pre-release, pinned) | Apache-2.0 | dev-only | Kotlin static analysis | 2026-08-04; active | detekt 1.23.8 (Feb 2025) embeds Kotlin compiler 2.0.21 and cannot analyse Kotlin 2.4 code. The 2.0 alpha embeds 2.4.10. Justification: dev-only, pinned, does not affect the shipped artifact; move to 2.0.0 GA when released |

## Web

| Name | Version | License | Scope | Purpose | Maintenance | Alternatives considered |
|---|---|---|---|---|---|---|
| React / React DOM | 19.3.0 | MIT | runtime | UI | 2026-10; Meta, very active | — (locked) |
| @tanstack/react-router | 1.170.44 | MIT | runtime | SPA routing (file-based) | 2026-10; very active | — (locked) |
| @tanstack/react-query | 5.104.2 | MIT | runtime | Server state | 2026-10; very active | — (locked) |
| @hey-api/openapi-ts | **0.99.0 (exact pin, pre-1.0)** | MIT | dev-only (generates runtime code) | Typed client + TanStack Query options from the contract | 2026-09-30; active. Pre-1.0 is justified by the locked decision (`AGENTS.md §2`); generated output is committed and the drift is checked in CI | openapi-typescript + openapi-fetch |
| @tanstack/router-plugin | 1.168.45 | MIT | dev-only | Route tree generation | 2026-10; active | — |
| TypeScript | 6.0.3 | Apache-2.0 | dev-only | Type checking | 2026-04. TS 7.0.2 (native) exists but typescript-eslint supports `<6.1` (ADR-0002) | TS 7 later |
| Vite / @vitejs/plugin-react | 8.3.4 / 6.1.2 | MIT | dev-only | Dev server and bundler | 2026-10; very active | — |
| Vitest | 5.0.4 | MIT | dev-only | Unit and component tests | 2026-10; active | Jest |
| @testing-library/react / jest-dom | 16.3.3 / 7.0.1 | MIT | dev-only | Component testing | active | — |
| jsdom | 30.1.2 | MIT | dev-only | DOM for tests | active | happy-dom |
| MSW | 3.0.3 | MIT | dev-only | Network mocking typed from generated types | 2026-10; active | — |
| @types/node, @types/react, @types/react-dom | 24.19.2 / 19.3.0 / 19.3.0 | MIT | dev-only | Types | active | — |
| Node.js | 24 LTS | MIT | dev-only | Toolchain runtime | active LTS | — |
| pnpm | 10.18.1 | MIT | dev-only | Package manager | active | — (locked) |

## Services (separate, unmodified processes)

| Name | Version | License | Purpose | Obligations / notes | Maintenance |
|---|---|---|---|---|---|
| PostgreSQL | 18.6 | PostgreSQL License | Database | — | active |
| SeaweedFS | 4.48 | Apache-2.0 | S3 object storage (default server) | — | 2026-09-28; very active |
| ONLYOFFICE Docs | 9.4.0.1 | AGPL-3.0 + additional terms | Document editing and conversion | **AGPL**: runs as a separate, unmodified service. If we distribute it on-prem, we must provide its source or point to it and keep its notices. **Legal review is required before on-prem distribution** (`MASTER_PLAN §5`). 10.0 is available; we take it after a bake period | active |
| ClamAV | 1.5.4 | GPL-2.0 | Malware scanning | **GPL**: separate, unmodified service; same obligations as above. freshclam needs outbound access, so air-gapped installs need a mirror | active (Cisco Talos) |
| Mailpit | v1.31.5 | MIT | Local mail catcher (dev only) | — | weekly releases |
| amazon/aws-cli image | 2.37.13 | Apache-2.0 | Bucket bootstrap job | — | very active |
| alpine/socat image | 1.8.1.1 | GPL-2.0 (socat) | Dev-only TCP gateway into the internal ONLYOFFICE network | dev-only, unmodified | active |

## CI

| Name | Version (SHA-pinned) | License | Purpose |
|---|---|---|---|
| actions/checkout | v7.0.1 | MIT | Checkout |
| actions/setup-java | v6.0.1 | MIT | JDK for Gradle |
| actions/setup-node | v7.1.0 | MIT | Node |
| gradle/actions/setup-gradle | v6.4.0 | MIT | Gradle caching |
| pnpm/action-setup | v6.1.0 | MIT | pnpm |
| actions/upload-artifact | v7.0.2 | MIT | Test reports |
