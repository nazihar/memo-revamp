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

## Deploy

| Target | How |
|---|---|
| Small on-prem install / our dev & staging VM | `deploy/compose/onprem` (Compose profile with a separate migration job); VM provisioning in `deploy/ansible`; `deploy/scripts/deploy.sh` (digest-pinned, automatic rollback). See ADR-0015. |
| Kubernetes (SaaS, customer clusters) | `deploy/helm/memo` (migration hook Job, restricted pods, NetworkPolicies). |
| Images | `backend/Dockerfile` (distroless Java 25), `web/Dockerfile` (nginx-unprivileged). Releases are signed with cosign: verify with `cosign verify --key deploy/cosign.pub --insecure-ignore-tlog <image@digest>`. |

Operability (ADR-0016): ECS JSON logs with `correlation.id` and `tenant.id`; probes on management port 8081; OpenTelemetry export off unless `MEMO_OTEL_EXPORT=true`.

The CI gates and how to run each one locally are listed in [`ci/README.md`](ci/README.md).

## Layout

```
backend/     Spring Boot modular monolith (Spring Modulith), Kotlin
shared/      Kotlin Multiplatform code shared with mobile
web/         React + TypeScript SPA (Vite, TanStack Router/Query)
mobile/      iOS and Android apps (from Phase 6a)
contracts/   Generated OpenAPI spec (committed)
deploy/      Local + on-prem Compose, Helm chart, Ansible, DB role script, storage bootstrap, deploy scripts
ci/          Gate scripts used by the GitHub workflows (portable to any CI)
perf/        Gatling simulations
build-logic/ Gradle convention plugins
docs/        Plans, ADRs, security checklists, dependency register
```
