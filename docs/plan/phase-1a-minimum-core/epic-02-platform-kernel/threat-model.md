# E02 Platform kernel — threat model (STRIDE)

## Data flows
1. Request / job / listener / executor → tenant scope → transaction manager → PostgreSQL (`set_config`, RLS).
2. Service → `AuditLog` → `beforeCommit` flush → `audit_event`, `audit_chain_head`.
3. Anchor job → SeaweedFS `records` bucket (object lock).
4. Verification job → `audit_event` (read) → result, alerts.
5. Writer transaction → `pg_notify` → listener connection on every node → local cache eviction.
6. Publisher → `event_publication` (platform table) → async listener; db-scheduler → `system_scheduled_task` (platform table).

| # | Threat | Flow | Mitigation | ASVS 5.0 | Test |
|---|---|---|---|---|---|
| I1 | A job or listener runs without a tenant and reads "all tenants" | 1 | Tenant travels in payloads/events; helpers bind it; no context → tenant-less transaction (RLS zero rows) and `MissingTenantContextException` | V8.4.1 | `TenantContextPropagationIT` (fail-closed cases) |
| I2 | A job or listener runs with the **wrong** tenant (context inherited from an unrelated thread) | 1 | `ScopedValue` cannot leak past its scope; decorator copies only at submission; rebinding to another tenant throws; listener tenant comes from the event, not the thread | V8.4.1 | `TenantContextPropagationIT` (two tenants interleaved on one executor; resubmitted event) |
| I3 | Platform scope is used to read tenant data | 1 | Only `tenancy_tenant` (SELECT) and platform-chain audit rows accept `app.scope = 'platform'`; meta-test restricts `app.scope` to `platform_*` policies; source scan restricts who sets it | V8.2.2, V8.4.1 | `PlatformScopeIT`, `RlsMetaTest` |
| I4 | Event or task payloads expose tenant content across tenants (platform tables are readable by the app role) | 6 | Ids-only rule for `TenantScopedEvent` and task payloads; review in every epic's threat model; payload classes are small data classes in `api` packages | V14.2.x (data minimisation) | `EventPayloadRulesTest` (ArchUnit: event classes have only id/enum/time fields) |
| T1 | Audit rows are modified or deleted to hide an action | 2 | App role has no UPDATE/DELETE; trigger blocks every role; hash chain; verification job; anchors in an object-locked bucket | V16.4.2 (log protection), V16.4.3 | `AuditAppendOnlyIT`, `AuditTamperIT`, `AuditChainVerifierProperties` |
| T2 | Whole-chain rewrite by someone with owner/superuser access | 2, 3 | Anchors (every 15 min) are immutable for their retention; verification compares head with anchors | V16.4.3 | `AuditTamperIT` (rewritten chain detected by anchor mismatch) |
| T3 | Audit row edited only in a queryable column, so the viewer lies while `canonical` is intact | 2, 4 | Verifier parses `canonical` and compares it with every column | V16.4.2 | `AuditTamperIT` (column-only edit) |
| R1 | A business change commits without its audit record | 2 | Flush in `beforeCommit` of the same transaction; flush failure aborts the commit; `record()` outside a read-write transaction throws | V16.3.x (security events logged) | `AuditSameTransactionIT` (rollback leaves neither; injected flush failure rolls back the change) |
| R2 | Actor is wrong or missing in the audit record | 1, 2 | `ActorContext` bound by every entry point; `Anonymous` and `System(name)` are explicit values, never null | V16.2.1 (log content) | `AuditActorIT` |
| D1 | Audit head lock throttles a busy tenant or deadlocks | 2 | Lock taken last and held only until commit; consistent order | V15.x | `AuditConcurrencyIT` (16 writers on a 10-connection pool, gapless, no deadlock, throughput recorded) |
| D2 | Missing future partitions break inserts | 2 | DEFAULT partition; migrator callback 12 months ahead on every deploy; daily alert below 3 months | V15.x | `AuditPartitionIT` |
| D3 | Listener disconnect leaves caches stale (for example a revoked permission still cached) | 5 | Full flush on reconnect; TTL safety net; metric on connection state | V8.x (authorization freshness) | `CacheInvalidationIT` (kill listener → flush) |
| D4 | A poison task or event retries forever | 6 | Bounded retries with backoff; permanent failure for missing tenant; staleness monitor | V15.x | `TenantContextPropagationIT` (missing tenant → failed, not retried) |
| S1 | Forged `pg_notify` from another database user evicts caches | 5 | Only an eviction (never a value) travels; eviction is always safe | — | (by design; noted) |
| E1 | The app role gains DDL to create partitions | 2 | Partitions created by the migrator callback only; `audit_ensure_partitions` not executable by `memo_app` | V8.x least privilege | `RlsMetaTest` (grants), `AuditPartitionIT` (app role cannot call it) |
| I5 | Sensitive values (passwords, secrets, document text) end up in audit before/after | 2 | `@Sensitive` redaction; review rule; before/after hold changed fields only | V16.2.5 (no sensitive data in logs) | `AuditRedactionTest` |

## Residual risks
- An attacker with database owner access can rewrite at most the last 15 minutes of a chain undetected by hashes; anchors bound it. Keyed hashes or signed anchors are a 1b option (key provider).
- `pg_notify` traffic is visible to any role connected to the database (cache names and ids only).
- The platform tables (`event_publication`, `system_scheduled_task`) are readable across tenants by the app role; the ids-only rule depends on review plus the ArchUnit field-type check.
