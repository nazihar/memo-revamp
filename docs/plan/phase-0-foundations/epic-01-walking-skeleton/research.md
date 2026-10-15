# E01 Walking skeleton — research

> Step 1 of `AGENTS.md §5`. Versions come from the official registries listed below, **not** from memory or the master plan. All sources were accessed on **2026-10-14** unless a different date is given.

## R1 — Versions, licenses, maintenance

### Backend toolchain

| Component | Chosen | Latest seen | License | Maintenance / notes | Source |
|---|---|---|---|---|---|
| Java (LTS) | **25** (Temurin 25.0.4.1) | 25.0.4.1+1 | GPLv2+CE (runtime, not linked code) | Current LTS. The next LTS is 29 (Sep 2027). | api.adoptium.net release_names |
| Gradle | **9.8.1** | 9.8.1 (2026-10-07) | Apache-2.0 | Active | services.gradle.org/versions/current |
| Kotlin (JVM + KMP plugin) | **2.4.21** | 2.4.21 (2026-10-08); 2.5.0-Beta1 | Apache-2.0 | Boot 4.1 manages 2.3.21, but its Kotlin baseline is "2.3+". We use the latest stable patch; 2.4.21 is a hotfix of 2.4.20. | Maven Central kotlin-stdlib metadata |
| Spring Boot | **4.1.1** | 4.1.1 (2026-08-20); 4.2.0-M2 | Apache-2.0 | 4.0 OSS support ends end-2026; 4.2 GA is expected in November 2026 and will be taken as a normal update PR (ADR-0002). | Maven Central spring-boot metadata |
| Spring Modulith | **2.1.1** | 2.1.1; 2.2.0-M2 | Apache-2.0 | The 2.1 line is built on Boot 4.1 | Maven Central |
| springdoc-openapi | **3.1.1** | 3.1.1 (2026-09-06) | Apache-2.0 | 3.1 parent = Boot 4.1.0 | Maven Central springdoc-openapi pom |
| Flyway | **12.4.0** (Boot-managed) | 13.10.0 | Apache-2.0 (`flyway-parent` pom) | We stay on Boot's managed version so starter auto-config matches; we move with Boot upgrades | Maven Central flyway-parent 13.10.0 pom; spring-boot-dependencies 4.1.1 |
| jOOQ | **3.21.7** (Boot-managed) | 3.21.9 | Apache-2.0 for the Open Source Edition | The Open Source Edition supports the *latest* versions of open-source databases, PostgreSQL included. Commercial editions are needed only for older dialect versions. We run current PostgreSQL, so OSS is enough (ADR in E02). | jooq.org/download; jooq 3.21.9 pom |
| PostgreSQL JDBC | 42.7.13 (Boot-managed) | 42.7.14 | BSD-2 | Active | Maven Central |
| Testcontainers | **2.0.5** (Boot-managed) | 2.0.5 (2026-04-20) | MIT | Active; 2.x artifact names (`testcontainers-postgresql`) | Maven Central |
| AWS SDK for Java v2 (S3) | **2.55.16** | 2.55.16 (2026-10-13) | Apache-2.0 | Released daily; managed through the `bom` | Maven Central |
| ktlint (via `org.jlleitschuh.gradle.ktlint` 14.2.0) | **1.8.0** | 1.8.0 (2025-12) | MIT | Active, release cadence about quarterly | Maven Central; plugins.gradle.org |
| detekt | **2.0.0-alpha.x** pinned *or* 1.23.8 — decided by spike, see below | 1.23.8 (Feb 2025); 2.0.0-alpha.6 (2026-08-04) | Apache-2.0 | 1.23.x is frozen and its Gradle plugin targets older Kotlin/Gradle. 2.0 is pre-release but under active development. Dev-only. | GitHub releases detekt/detekt |
| JUnit Jupiter | 6.0.3 (Boot-managed) | — | EPL-2.0 | dev-only | spring-boot-dependencies 4.1.1 |

### Web toolchain

| Component | Chosen | License | Notes | Source |
|---|---|---|---|---|
| Node.js | **24 LTS ("Krypton", 24.21.0)** | MIT | Active LTS. Node 26 is "Current" and is not used. | nodejs.org/dist/index.json |
| pnpm | **10.x pinned via `packageManager`** (10.18.1 on dev machines; 12.11.2 is latest) | MIT | We stay on pnpm 10. The update bot proposes the move to 12 (E04). | npm registry |
| TypeScript | **6.0.3** | Apache-2.0 | **TS 7.0 (native, Go) is out, but `typescript-eslint` 8.71 peers `typescript <6.1.0` and `@hey-api/openapi-ts` peers `>=5.5 \|\| >=6`.** We stay on 6.0 until the ecosystem supports 7. | npm registry peerDependencies |
| React / React DOM | 19.3.0 | MIT | | npm |
| Vite | 8.3.4 | MIT | | npm |
| @vitejs/plugin-react | 6.1.2 | MIT | peers vite ^8 | npm |
| @tanstack/react-router + router-plugin | 1.170.44 / 1.168.45 | MIT | file-based routes via the Vite plugin | npm |
| @tanstack/react-query | 5.104.2 | MIT | | npm |
| @hey-api/openapi-ts | **0.99.0 (exact pin)** | MIT | pre-1.0; needs Node ≥ 22.18 | npm |
| Vitest | 5.0.4 | MIT | Node ^22.12 \|\| ^24 | npm |
| MSW | 3.0.3 | MIT | | npm |
| Playwright | 1.64.0 | Apache-2.0 | E03 | npm |
| ESLint / typescript-eslint / Prettier | 10.12.0 / 8.71.2 / 3.9.10 | MIT | E03 ADR | npm |
| Biome | 2.5.15 | MIT/Apache-2.0 | alternative considered in E03 | npm |
| react-intl / @formatjs/cli | 12.1.4 / 6.16.34 | BSD-3 / MIT | E03 ADR | npm |
| tailwindcss / shadcn | 4.3.3 / 4.21.4 | MIT | E03 | npm |

### Local-stack images

| Service | Image (pinned) | License | Notes | Source |
|---|---|---|---|---|
| PostgreSQL | `postgres:18.6` | PostgreSQL | 18 is the current major; 19 is beta4 | Docker Hub library/postgres |
| SeaweedFS | `chrislusf/seaweedfs:4.48` | Apache-2.0 | Released 2026-09-28 | GitHub releases seaweedfs/seaweedfs |
| ONLYOFFICE Docs | `onlyoffice/documentserver:9.4.0.1` | AGPL-3.0 (separate unmodified service) | **10.0.0 came out 2026-10-12 (2 days ago). We pin 9.4 and take 10.x through the update bot after a bake period.** | Docker Hub onlyoffice/documentserver |
| ClamAV | `clamav/clamav:1.5.4` | GPL-2.0 (separate unmodified service) | | Docker Hub clamav/clamav |
| Mail catcher | `axllent/mailpit:v1.31.5` | MIT | Active (weekly releases) | Docker Hub axllent/mailpit |

## R2 — Gradle structure (Spring Boot + KMP in one build)

| Option | Pros | Cons |
|---|---|---|
| **A. One root build + `build-logic` included build with precompiled convention plugins** | One `gradlew` and one version catalog; shared conventions; works with the configuration cache; this is the documented Gradle pattern ("sharing build logic") | KMP and the JVM Kotlin plugin must use the same Kotlin version (they do) |
| B. Separate builds per area, joined as a composite build | Isolation | Two catalogs or a shared catalog file; more wrappers; harder in CI |
| C. `buildSrc` | Simple | Any change invalidates the whole build; Gradle recommends included builds |

**Recommendation: A.** `build-logic/` holds the convention plugins `memo.kotlin-jvm`, `memo.spring-boot`, `memo.kmp-library` and `memo.quality`. Each plugin reads versions from `gradle/libs.versions.toml`. The configuration cache and the build cache are on (`gradle.properties`). JDK 25 comes through Gradle toolchains with the foojay resolver, so a developer only needs some JDK ≥ 17 to start Gradle. Source: docs.gradle.org "Sharing build logic between subprojects" and "Toolchains for JVM projects".

## R3 — Cross-platform one-command entry point

| Option | Windows | Extra install | Notes |
|---|---|---|---|
| `make` | Not available by default (the dev machine has none) | yes (choco/msys) | Classic, but fails on a stock Windows machine |
| Task (go-task) | yes | yes (single binary) | YAML, cross-platform |
| just | yes | yes | Needs a shell on Windows |
| **Node script behind `pnpm dev`** | yes | **none**: Node + pnpm are already required for `/web` | The orchestration (compose up, wait for health, `gradlew bootRun`, vite) is plain JS, testable, and has no shell quirks |

**Recommendation: `pnpm dev`** (root `package.json` → `scripts/dev.mjs`), with subcommands `pnpm stack:up|down|reset`. It also generates `deploy/compose/.env` with random local passwords on first run, so no secret is committed. Decided in ADR-0003.

## R4 — SeaweedFS (dev) and the object-lock bucket bootstrap

- Object lock **must be requested at bucket creation** (`create-bucket --object-lock-enabled-for-bucket`). Existing buckets cannot be converted. Enable versioning explicitly as well. Maximum retention is 100 years. Source: https://github.com/seaweedfs/seaweedfs/wiki/S3-Object-Lock-and-Retention
- S3 credentials are configured with an identities JSON file (`-s3.config`) that lists access/secret keys and allowed actions.
- **Dev topology:** one `weed server -s3` container (master + volume + filer + S3 in one process), with a volume for data. Production topology (separate master, volume and filer, filer store in PostgreSQL) is decided in E05.
- **Bootstrap:** a one-shot `storage-init` Compose service uses the official `amazon/aws-cli` image (Apache-2.0) and the documented commands. It creates the `records` bucket with object lock and versioning, plus a plain `work` bucket, and is idempotent (`head-bucket` first). The same image and script become a Helm Job in E05, so the application never holds bucket-admin rights. Alternative rejected: bucket creation inside the backend at startup, which would give the app identity admin rights on storage. An integration test reads the configuration back with `GetObjectLockConfiguration`.

## R5 — ONLYOFFICE and ClamAV on developer machines

- ONLYOFFICE Document Server needs about 2–4 GB RAM. ClamAV needs about 1.5–2.5 GB once the signature DB is loaded, and the first freshclam download takes a few minutes. On a 16 GB Windows/WSL2 machine both fit, but together they are the bulk of the stack.
- **No outbound network for ONLYOFFICE:** Compose attaches it **only** to an `internal: true` network shared with the backend. Internal networks have no external route, and published ports do not work on them, so it is reachable from the backend only. In Phase 3 browser access to the editor comes through the backend/reverse proxy.
- **ClamAV needs outbound access to update signatures** (freshclam → database.clamav.net). That is acceptable in dev. For on-prem and air-gapped installs, a private signature mirror is an E05/Phase 7 item, recorded in the threat model.
- **Profiles:** `core` = postgres, seaweedfs, mailpit; `full` = core + onlyoffice + clamav. `pnpm dev` starts `full` (exit criterion); `pnpm dev --core` is for small machines. The health endpoint reports services that are not configured as `DISABLED`, not `DOWN`.

## Industry practice adopted

- Walking-skeleton approach (Cockburn; *Growing Object-Oriented Software*, Freeman & Pryce): every layer is deployed end to end before features.
- The health endpoint is split as Kubernetes and Spring Boot do: liveness and readiness probes (E05) plus a public, information-poor status endpoint. GitHub and Atlassian status pages also show only component names and UP/DOWN, never versions or hosts.
