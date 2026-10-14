---
status: accepted
date: 2026-10-14
deciders: product owner (delegated), agent
---

# 0004 — Gradle build structure

## Context and problem statement
The backend (Spring Boot) and `/shared` (Kotlin Multiplatform) are both Kotlin and must share versions and quality rules (`AGENTS.md §4`).

## Considered options
1. One root build plus a `build-logic` included build with precompiled convention plugins
2. Separate builds joined as a composite build
3. `buildSrc`

## Decision outcome
Chosen option: **1**.
- `settings.gradle.kts` includes `:backend` and `:shared` and calls `includeBuild("build-logic")`.
- Convention plugins: `memo.kotlin-jvm`, `memo.spring-boot`, `memo.kmp-library`, `memo.quality` (ktlint + detekt).
- All versions live in `gradle/libs.versions.toml`.
- JDK 25 comes from Gradle toolchains through the foojay resolver.
- The configuration cache and the build cache are enabled.

### Consequences
- Good: one wrapper, one catalog and the same quality rules everywhere. The configuration cache keeps CI fast.
- Bad: KMP and JVM modules must use the same Kotlin Gradle plugin version. We want that anyway.

## More information
- https://docs.gradle.org/current/userguide/sharing_build_logic_between_subprojects.html (accessed 2026-10-14)
