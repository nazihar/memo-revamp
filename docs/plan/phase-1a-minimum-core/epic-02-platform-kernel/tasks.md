# E02 Platform kernel — tasks

Vertical slices for a backend-only epic: each one ends with working, tested behaviour on real PostgreSQL (and SeaweedFS for anchoring).

| # | Task | Size | Depends |
|---|---|---|---|
| T1 | **Tenant and actor scope + transaction hook:** `TenantContext`, `ActorContext`, `TenantAwareTransactionManager`, `TenantTransactions` on top of them, `TenantDsl` check, `ContextTaskDecorator`, `ActorContextFilter` (actor + client only); ArchUnit/source-scan updates | M | — |
| T2 | **Platform scope:** `PlatformTransactions`, `tenancy/V2` `platform_read` policy, `TenantRegistry.listActive()` for platform jobs; meta-test rule for `app.scope` | S | T1 |
| T3 | **db-scheduler:** Boot 4 starter, `system_scheduled_task` migration, Jackson 3 serializer, `TenantTask` / `PlatformTask` helpers, failure policy; two-instance test | M | T1, T2 |
| T4 | **Outbox:** `spring-modulith-starter-jdbc`, outbox migration, `TenantScopedEvent` / `PlatformEvent`, `TenantEvents.handle`, resubmission and archive-cleanup jobs; `TenantCreated` and `TenantSettingChanged` events; listener rules | M | T1, T3 |
| T5 | **Audit store:** `audit/V1`, partition callback, `AuditLog` with `beforeCommit` flush, canonical form, hashing, redaction; `tenant.created` and `tenant.setting.*` recorded; test helpers `assertAudited` | L | T1, T2 |
| T6 | **Verification:** pure `AuditChainVerifier` (property tests), incremental and full jobs, checkpoint, platform-chain failure event, metric | M | T3, T5 |
| T7 | **Anchoring:** anchor sweep and per-tenant anchor job, object-lock retention, `audit_anchor` table, verifier compares anchors; partition-check job | M | T3, T5, T6 |
| T8 | **Caches:** `TenantCaches`, `CacheInvalidation`, `PgNotificationListener`, tenant settings cache; two-listener test | M | T1 |
| T9 | **Volumes and docs:** V5 audit generator, nightly verification-throughput run, `dependencies.md`, ASVS rows (V8, V16), README section on context rules for module authors | S | T5–T8 |

T8 can run in parallel with T3–T7.

## Acceptance criteria

- **T1**
  - Given tenant A in scope, when a `@Transactional` service reads `current_setting('app.tenant_id', true)`, then it is A; the same holds for `TransactionTemplate` and `REQUIRES_NEW`.
  - Given no tenant in scope, when a `@Transactional` service calls `TenantDsl.current()`, then `MissingTenantContextException` is thrown; raw SQL on a tenant table returns zero rows.
  - Given a transaction bound to A, when code rebinds the scope to B and joins it, then `IllegalStateException` is thrown.
  - Given tenant A in scope, when work is submitted to the application executor, then the worker sees A; tasks for A and B interleaved on one executor each see their own tenant.
- **T2**
  - Given platform scope, when listing tenants, then every tenant is returned; when reading any tenant table (`tenancy_setting`, `audit_event` of a tenant), then zero rows.
  - Given platform scope, when `UPDATE tenancy_tenant` is attempted, then 0 rows are affected.
  - Given a tenant in scope, when `inPlatformScope` is called, then `IllegalStateException` is thrown.
- **T3**
  - Given two scheduler instances on one database, when a one-off task is scheduled, then it runs exactly once.
  - Given a tenant task scheduled for A, when it runs, then its handler sees tenant A and actor `System(<task>)`.
  - Given a tenant task payload without a tenant, when it runs, then the execution fails permanently (no retry) and a metric is incremented.
- **T4**
  - Given a tenant setting change in tenant A, when the transaction commits, then a listener receives `TenantSettingChanged(tenantId = A, key)` and runs in tenant A; when it rolls back, then no listener runs.
  - Given an incomplete publication older than the staleness threshold, when the resubmission job runs, then the listener runs once with the event's tenant.
  - An event class with a field other than ids, enums, numbers or timestamps fails `EventPayloadRulesTest`.
- **T5**
  - Given a tenant setting change, when the transaction commits, then exactly one `tenant.setting.changed` entry exists with the before/after values, actor and correlation id.
  - Given a business change plus `record()`, when the transaction rolls back (or the flush fails), then neither the change nor the entry exists.
  - Given `record()` called outside a read-write transaction, then `AuditContextException` is thrown.
  - Given the app role, when `UPDATE` or `DELETE` on `audit_event` is attempted, then it is refused; given the owner, the trigger refuses it.
  - Given a fresh database after migration, then partitions exist for the current month and 12 months ahead, each with ENABLE + FORCE RLS and the tenant policy.
- **T6**
  - Given any chain and any single modification, deletion, insertion, reordering or truncation, then the verifier reports the first broken `seq` (property test).
  - Given a row altered with the owner role, when the incremental job runs, then the tenant's status is `BROKEN` at that `seq`, an ERROR is logged and `audit.chain.verification_failed` is in the platform chain.
  - Given an unchanged chain, when the job runs twice, then the second run reads only new entries (checkpoint).
- **T7**
  - Given new entries in tenant A, when the sweep runs, then an anchor object for A's head exists in `records` with object-lock retention, and deleting or overwriting it is refused.
  - Given a chain whose tail was deleted and whose head was rewritten consistently, when verification runs, then the anchor mismatch is reported.
  - Given fewer than 3 future partitions, when the partition check runs, then it logs ERROR and sets the metric.
- **T8**
  - Given two listeners (nodes) on one database, when node A commits a settings change, then node B's cache entry is evicted within 1 s; when node A rolls back, then nothing is evicted.
  - Given node B's listener connection is killed, when it reconnects, then all of node B's caches are flushed.
- **T9**
  - The V5 generator creates a valid 10-year chain for one tenant (configurable rate), which the verifier accepts; the nightly job records throughput.
