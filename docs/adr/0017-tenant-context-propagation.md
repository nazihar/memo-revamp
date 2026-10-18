---
status: accepted
date: 2026-10-18
deciders: product owner (delegated), agent
---

# 0017 — Tenant context propagation

## Context and problem statement
ADR-0006 gave us transaction-local tenant context through `TenantTransactions.inTenant { }`. Phase 1a needs the tenant to reach **every** transaction, whatever opens it (requests, `@Transactional` services, db-scheduler jobs, async event listeners, executors), and to fail closed when it is missing (AGENTS §3.2). Platform jobs also need to list tenant ids without seeing tenant data. Research: `phase-1a-minimum-core/epic-02-platform-kernel/research.md §R4`.

## Decision drivers
- No code path may touch tenant data without an explicit tenant; no "all tenants" fallback.
- Context must not leak across pooled connections or reused (virtual) threads.
- Work that runs later (jobs, resubmitted events) must not depend on the original thread.
- One mechanism, testable in one place.

## Considered options
1. `ThreadLocal` holder plus AOP around `@Transactional`
2. Transaction binding only (status quo): every caller opens `inTenant { }` itself
3. **`ScopedValue` scope + a transaction-manager hook + explicit carriers for jobs and events**

## Decision outcome
Chosen option: **3**.
- **Scope:** `TenantContext` is a `ScopedValue<TenantId>`, bound by the request filter (E03 resolves the tenant), by the job and listener helpers, and by `TenantTransactions`. `ActorContext` (who acts: user, acting-as, system job, platform operator; client IP and user agent) is a second `ScopedValue`, bound the same way and read by the audit module.
- **Transactions:** `TenantAwareTransactionManager` extends `JdbcTransactionManager` and overrides `prepareTransactionalConnection`. When a tenant is in scope, it runs `select set_config('app.tenant_id', ?, true)` and binds the tenant to the transaction (the binding `TenantDsl` checks). When none is, the transaction is tenant-less: RLS returns zero rows, and `TenantDsl.current()` throws `MissingTenantContextException`. Joining a transaction bound to a different tenant throws.
- **Jobs:** every tenant task's data carries `tenantId`. The task helper binds the scope before the handler runs; a missing or unknown tenant fails the execution without retry (ADR-0018).
- **Async listeners:** events that concern a tenant implement `TenantScopedEvent` (`tenantId` plus ids only, never content). Listeners are `@Async @TransactionalEventListener` methods whose body calls `tenantEvents.handle(event) { … }`, which binds the scope and opens the tenant transaction. `@ApplicationModuleListener` is not used for tenant events, so no advisor ordering is involved. ArchUnit checks that every event-listener parameter implements `TenantScopedEvent` or `PlatformEvent`.
- **Executors:** a `TaskDecorator` bean captures the scope at submission and re-binds it in the worker.
- **Platform scope:** `PlatformTransactions.inPlatformScope { }` sets `set_config('app.scope', 'platform', true)`. Only the `tenancy_tenant` policy accepts it, and only for `SELECT`, so platform code can list tenants and nothing else. Platform scope and a tenant scope cannot be combined.
- **Coroutines:** not used on the backend (ArchUnit forbids `kotlinx.coroutines` in main code).

### Consequences
- Good: `@Transactional` services work with the request's tenant automatically; context cannot leak; delayed work carries its own tenant; one hook covers every transaction.
- Good: platform jobs can enumerate tenants without a second registry and without access to tenant tables.
- Bad: we depend on a protected Spring hook. A test asserts that `set_config` happens for `@Transactional`, `TransactionTemplate` and listener-started transactions, so an upstream change cannot silently disable it.
- Bad: listener authors must use `tenantEvents.handle`. Forgetting it fails closed (the publication stays incomplete and visible), not open.
- Follow-ups: E03 binds the tenant from the session or the host name; E07 builds operator access on platform scope (ADR-OPS).

### Confirmation
- `TenantContextPropagationIT`: request, `@Transactional`, `TransactionTemplate`, db-scheduler task, async listener (including resubmission of an incomplete publication) and executor each see the right tenant; each fails closed without one.
- `PlatformScopeIT`: platform scope lists tenants and sees zero rows in every tenant table.
- ArchUnit: listener parameter types; no `kotlinx.coroutines`; `app.scope` set only in `PlatformTransactions` (source scan, next to the existing session-`SET` scan).

## More information
Sources (accessed 2026-10-18): https://openjdk.org/jeps/506, https://docs.spring.io/spring-modulith/reference/events.html
