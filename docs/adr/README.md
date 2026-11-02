# Architecture decision records

We use the MADR format (see [0001](0001-record-architecture-decisions.md)). Start every new ADR from [template.md](template.md).

| # | Title | Status | Date |
|---|---|---|---|
| [0001](0001-record-architecture-decisions.md) | Record architecture decisions with MADR | accepted | 2026-10-14 |
| [0002](0002-toolchain-baseline.md) | Toolchain baseline | accepted | 2026-10-14 |
| [0003](0003-developer-entry-point.md) | Cross-platform developer entry point (`pnpm dev`) | accepted | 2026-10-14 |
| [0004](0004-gradle-build-structure.md) | Gradle build structure | accepted | 2026-10-14 |
| [0005](0005-seaweedfs-dev-topology-and-bucket-bootstrap.md) | SeaweedFS dev topology and object-lock bucket bootstrap | accepted | 2026-10-14 |
| [0006](0006-database-roles-and-rls-conventions.md) | Database roles, RLS policy conventions and tenant-context semantics | accepted | 2026-10-14 |
| [0007](0007-jooq-codegen-and-flyway-layout.md) | jOOQ edition, code generation pipeline and per-module Flyway layout | accepted | 2026-10-14 |
| [0008](0008-backend-test-libraries.md) | Backend test libraries (assertions, property-based testing, ids) | accepted | 2026-10-14 |
| [0009](0009-web-lint-and-format.md) | Web lint and format: ESLint + Prettier | accepted | 2026-10-14 |
| [0010](0010-web-i18n.md) | Web internationalisation: react-intl (FormatJS) | accepted | 2026-10-14 |
| [0011](0011-ci-platform-and-stages.md) | CI platform, runner model and pipeline stages | accepted | 2026-10-14 |
| [0012](0012-container-images-and-signing.md) | Container base images and image signing | accepted | 2026-10-14 |
| [0013](0013-supply-chain-scanning.md) | Supply-chain scanning stack | accepted | 2026-10-14 |
| [0014](0014-dependency-update-bot.md) | Dependency update bot: Renovate | accepted | 2026-10-14 |
| [0015](0015-environments-iac-and-deployment.md) | Environments, IaC and deployment topology | accepted | 2026-10-14 |
| [0016](0016-operability-baseline.md) | Operability baseline: structured logs, correlation id, OpenTelemetry, probes, migration job | accepted | 2026-10-14 |
| [0017](0017-tenant-context-propagation.md) | Tenant context propagation | accepted | 2026-10-18 |
| [0018](0018-background-jobs-db-scheduler.md) | Background jobs with db-scheduler | accepted | 2026-10-18 |
| [0019](0019-audit-store.md) | Audit store: same-transaction hash chain, verification and anchoring | accepted | 2026-10-18 |
| [0020](0020-cache-invalidation-listen-notify.md) | Caffeine caches with LISTEN/NOTIFY invalidation | accepted | 2026-10-18 |
| [0021](0021-modal-dialogs-native-dialog.md) | Modal surfaces on the native `<dialog>` element, no style nonce | accepted | 2026-10-18 |
| [0022](0022-tenant-resolution-and-accounts.md) | Tenant resolution from the host name; one account per tenant | accepted | 2026-10-18 |
| [0023](0023-local-authentication-and-sessions.md) | Local authentication and server-side sessions for the SPA | accepted | 2026-10-18 |
| [0024](0024-totp-mfa-and-step-up.md) | TOTP multi-factor authentication, recovery codes and step-up | accepted | 2026-10-18 |
| [0025](0025-rate-limiting.md) | Rate limiting with Bucket4j on PostgreSQL | accepted | 2026-10-18 |
| [0026](0026-org-model.md) | Org model: units tree, positions, effective-dated assignments | accepted | 2026-10-29 |
| [0027](0027-authorisation-model.md) | Authorisation: permission catalogue, roles, policy service, SQL pushdown | accepted | 2026-10-29 |
