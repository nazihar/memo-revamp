# memo-revamp

A memo and meeting management platform. It carries an organization's decisions from the first memo, through approval chains and committee or board meetings, to minutes, resolutions and action items. It runs as multi-tenant SaaS, single-tenant, or on-premise.

- **What and why:** [`docs/plan/MASTER_PLAN.md`](docs/plan/MASTER_PLAN.md)
- **Rules for contributors and agents:** [`AGENTS.md`](AGENTS.md)
- **Where work stands:** [`docs/plan/STATUS.md`](docs/plan/STATUS.md)
- **Decisions:** [`docs/adr/`](docs/adr/README.md)

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| Docker (Desktop or Engine) with Compose v2 | recent | about 8 GB RAM free for the full stack, about 4 GB for `--core` |
| JDK | any ≥ 17 | only to start Gradle; JDK 25 is downloaded automatically (toolchains) |
| Node.js | 24 LTS (`.node-version`) | |
| pnpm | 10.18.1 (`packageManager`) | `corepack enable` or `npm i -g pnpm@10.18.1` |

## Run it

```sh
pnpm install
pnpm dev            # full stack: PostgreSQL, SeaweedFS, ONLYOFFICE, ClamAV, Mailpit + backend + web
pnpm dev --core     # without ONLYOFFICE and ClamAV (lighter machines)
```

| URL | What |
|---|---|
| http://localhost:5173 | Web app (health page) |
| http://localhost:8080/api/v1/health | Backend health |
| http://localhost:8025 | Mailpit (captured mail) |

The first run writes random local credentials to `deploy/compose/.env`. This file is git-ignored; never commit it. The first full start can take several minutes because ClamAV downloads its signatures.

Stack only: `pnpm stack:up [--core]`, `pnpm stack:down`, `pnpm stack:reset` (the last one deletes volumes).

## Build and test

```sh
./gradlew build                      # backend + shared: compile, tests (Testcontainers: needs Docker), ktlint, detekt
./gradlew :backend:updateContract    # after an API change: regenerate contracts/openapi.json
pnpm --filter web generate:api       # then regenerate the web client from the contract
pnpm --filter web typecheck && pnpm --filter web test && pnpm --filter web build
```

The OpenAPI contract in `/contracts` is committed. The build fails if the running API and the contract differ, and CI fails if the generated web client is stale.

If an IDE builds the same tree with a different Gradle version (seen with VS Code's Java extension), set `MEMO_BUILD_ROOT=<repo>/.build-cli` for CLI builds. The build output then goes to a separate, git-ignored directory on the same drive.

## Layout

```
backend/     Spring Boot modular monolith (Spring Modulith), Kotlin
shared/      Kotlin Multiplatform code shared with mobile
web/         React + TypeScript SPA (Vite, TanStack Router/Query)
mobile/      iOS and Android apps (from Phase 6a)
contracts/   Generated OpenAPI spec (committed)
deploy/      Compose stack, DB role script, storage bootstrap (Helm from E05)
build-logic/ Gradle convention plugins
docs/        Plans, ADRs, security checklists, dependency register
```
