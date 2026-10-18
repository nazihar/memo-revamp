# Backend conventions for module authors

Rules every module follows. The decisions behind them are in `docs/adr/`.

## Tenant context (ADR-0006, ADR-0017)
- Tenant data is reached only through `TenantDsl.current()` inside a transaction that was started in a tenant scope. Outside one, it throws `MissingTenantContextException`; the database returns zero rows either way.
- `@Transactional` services pick up the tenant of the caller's scope automatically. Use `TenantTransactions.inTenant(tenant) { }` where no scope exists yet (seeders, tests).
- Never call `set_config` or `SET`; only `TenantAwareTransactionManager` does (checked by `ArchitectureRulesTest`).
- Platform code (no tenant) uses `PlatformTransactions`, which can list tenants and write the platform audit chain, nothing else.
- No Kotlin coroutines on the backend.

## Who acts (ADR-0017)
- Every entry point binds an `ActorContext`: the request filter (anonymous until E03), job helpers (`System(<task>)`), event helpers (`System(event:<listener>)`), seeders. Recording an audit entry without one throws.

## Audit (ADR-0019)
- Call `AuditLog.record(action, objectType, objectId, before, after)` in the same transaction as every state change. Actions look like `module.object.verb` (`tenant.setting.changed`).
- `before`/`after` hold ids and changed fields only: strings, integers, booleans, maps and lists. No document content, no secrets (secret-looking keys are redacted).
- Tests use `Audit.assertAudited(tenant, action, objectId, actorId)` from `testsupport`.

## Background jobs (ADR-0018)
- Declare tasks as beans built with `Jobs`: `tenantTask` (one tenant; the tenant travels in the task data), `platformTask` (recurring, no tenant data) or `platformOneOff`.
- Task data carries ids only. Use idempotency keys as task instance ids. Never use `@Scheduled`.

## Events (ADR-0017)
- Tenant events implement `TenantScopedEvent` (ids only; checked by `EventPayloadRulesTest`). Listeners are `@Async @TransactionalEventListener` methods whose body runs `tenantEvents.handle(event, "<listener>") { … }`.
- The outbox resubmits failed listeners; listeners must be idempotent.

## Caches (ADR-0020)
- Register read-mostly configuration caches with `TenantCaches.register(name)`. After a change, call `CacheInvalidation.invalidate(name, tenant, key)` in the same transaction; every node evicts at commit.
