# E02 Platform kernel — log

## 2026-10-18
- Plan written (research, ADR-0017 to ADR-0020, design, threat model, tasks, test plan). Gate delegated (overview Q1).
- Changes to the overview from the detailed design: E02 ships no REST endpoint (the audit query API and export move to E07, after authentication exists); platform scope (tenant enumeration for platform jobs) is pulled forward from E07.
- Implemented T1–T9 on `feat/1a-e02-platform-kernel`:
  - T1/T2: `TenantContext` and `ActorContext` as `ScopedValue`s; `TenantAwareTransactionManager` sets `app.tenant_id` (or `app.scope`) on every transaction; context task decorator; platform scope with a read-only `platform_read` policy on the registry.
  - T3/T4: db-scheduler (Boot 4 starter, `system_scheduled_task`), `Jobs` helpers, Spring Modulith JDBC outbox in archive mode with a cluster-safe resubmission job, `TenantScopedEvent` + `TenantEvents`, ids-only payload rule.
  - T5–T7: audit store (monthly partitions created by a Flyway callback, append-only grants and trigger, RLS incl. the platform chain), `AuditLog` with the `beforeCommit` flush, canonical JSON and SHA-256 chain, pure verifier, incremental/full verification jobs, anchors in the object-locked `records` bucket, partition check.
  - T8: `TenantCaches`, `CacheInvalidation` (`pg_notify` at commit), `PgNotificationListener` (dedicated connection, flush on reconnect), cached tenant settings.
  - T9: nightly V5 verification-throughput test (`-PauditVolume`), dependency register, ASVS rows, `backend/README.md`.
- Findings:
  - **Spring already has a `requestContextFilter` bean**: our filter is `ActorContextFilter`.
  - **FORCE RLS applies to the owner as well**: tamper tests must set the tenant on the owner connection, otherwise UPDATE matches nothing and the immutability trigger never fires. Good for defence in depth.
  - **SeaweedFS needs `PutObjectRetention`** (plus `GetObjectRetention`) for object-lock headers on PutObject; the app identity got both on `records` only (not `BypassGovernanceRetention`). A new `S3_IDENTITIES_REVISION` in the compose files recreates SeaweedFS so the identities reload on deploy.
  - **Audit flush cost**: 4 round trips measured 10.6 ms per flush on the Docker Desktop laptop; reduced to 2 (lock + read head; one CTE that inserts entries and moves the head): 5.9 ms uncontended, 171 audited commits/s per tenant with 32 writers. Design §10 budgets rewritten to uncontended cost plus per-tenant throughput.
  - **Several cached Spring test contexts each run a scheduler on the same database**: job tests must not depend on context-local state (metrics); they assert on the database.
  - **Per-chain audit tasks cannot run in a tenant scope**: recording a verification failure writes the platform chain, and tenant and platform scopes cannot nest. They use `Jobs.platformOneOff` and open scoped transactions themselves.
