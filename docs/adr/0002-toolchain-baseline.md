---
status: accepted
date: 2026-10-14
deciders: product owner (delegated), agent
---

# 0002 — Toolchain baseline

## Context and problem statement
`AGENTS.md §2` locks the stacks (Kotlin + Spring Boot 4.x, React + TS) but leaves the exact versions to be verified. Versions were checked on 2026-10-14; see `docs/plan/phase-0-foundations/epic-01-walking-skeleton/research.md §R1`.

## Decision drivers
- Use the latest GA lines that have OSS support through the pilot (M1).
- Keep the ecosystem compatible: Spring Boot ↔ Kotlin ↔ Gradle, and TypeScript ↔ typescript-eslint ↔ hey-api.
- Pin every version: `gradle/libs.versions.toml`, `pnpm-lock.yaml`, `packageManager`, `.node-version`.

## Considered options
- Spring Boot **4.1.x (GA)** vs 4.2.0-M2 (milestone)
- Kotlin **2.4.x** vs 2.3.x (the version Boot manages)
- TypeScript **6.0** vs 7.0 (native)
- Node **24 LTS** vs 26 Current

## Decision outcome
| Tool | Version |
|---|---|
| Java (toolchain, via the Gradle foojay resolver) | 25 (LTS) |
| Gradle (wrapper) | 9.8.1 |
| Kotlin | 2.4.21 |
| Spring Boot | 4.1.1. Spring Modulith 2.1.1 and springdoc 3.1.1. Flyway, jOOQ, Testcontainers and JDBC use Boot-managed versions. |
| Node | 24 LTS (`.node-version`) |
| pnpm | 10.18.1 (`packageManager`) |
| TypeScript | 6.0.3 |

- **Boot 4.2:** start on 4.1.1. Take 4.2.0 as a normal update PR once it is GA (expected November 2026).
- **Kotlin 2.4 rather than the managed 2.3.21:** Boot 4.1's baseline is Kotlin 2.3+, and the KMP module benefits from the newer compiler. If we hit an incompatibility, we fall back to the managed version.
- **TypeScript 7 is rejected for now:** `typescript-eslint` peers `<6.1.0`, and `@hey-api/openapi-ts` does not list 7. Re-check this every phase.
- **Node 26 is rejected:** it is not an LTS release.

### Consequences
- Good: every component is on a supported GA line.
- Bad: developers need Node 24 and a JDK ≥ 17 to start Gradle. JDK 25 itself is provisioned by Gradle.
- Follow-ups: the update bot (E04) proposes minor and patch updates. Major upgrades are reviewed by hand.

### Confirmation
CI uses exactly these versions. pnpm refuses a mismatched `packageManager` version, and Gradle uses the toolchain.
