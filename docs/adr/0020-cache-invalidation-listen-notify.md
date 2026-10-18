---
status: accepted
date: 2026-10-18
deciders: product owner (delegated), agent
---

# 0020 — Caffeine caches with LISTEN/NOTIFY invalidation

## Context and problem statement
AGENTS §2 and §4 lock Caffeine for read-mostly configuration, invalidated across nodes through PostgreSQL `LISTEN/NOTIFY`, with stateless app nodes and no Redis. This ADR fixes the mechanism. Research: `phase-1a-minimum-core/epic-02-platform-kernel/research.md §R7`.

## Decision drivers
- Invalidate only what committed; never serve stale configuration after a node's own write.
- Survive listener disconnects without silent staleness.
- Work on customer PostgreSQL setups, including those behind a connection pooler.

## Considered options
1. **`pg_notify` in the writing transaction + one dedicated listener connection per node**
2. Polling a version table
3. TTL-only caches (stale permissions for up to a TTL)

## Decision outcome
Chosen option: **1**.
- **Publish:** `CacheInvalidation.invalidate(cache, tenantId, key?)` runs `select pg_notify('memo_cache', payload)` in the current transaction (delivered at commit only) and registers a local `afterCommit` eviction.
- **Listen:** each node opens one connection outside the pool, runs `LISTEN memo_cache` and loops on `getNotifications(timeout)` on a virtual thread. An optional `MEMO_DB_LISTEN_URL` lets installs behind PgBouncer point the listener directly at PostgreSQL.
- **Reconnect:** with backoff. **Every (re)connect flushes all registered caches.**
- **Caches:** registered by name, keyed by tenant (no cache entry without the tenant in its key), with a TTL safety net (default 10 minutes) and a size bound.
- **Payload:** cache name, tenant id, optional key; no content; far below 8,000 bytes.
- First consumers: tenant settings (E02); org tree and permissions (E04).

### Consequences
- Good: exact commit semantics; no extra infrastructure.
- Bad: one extra database connection per node; `LISTEN` needs a direct connection (documented install requirement).
- Follow-ups: the same pattern serves real-time fan-out in Phase 5, on a separate channel.

### Confirmation
- `CacheInvalidationIT`: two listeners on one database; a committed change evicts on the other node; a rolled-back change evicts nothing; killing the listener connection triggers a full flush on reconnect.

## More information
Sources (accessed 2026-10-18): https://www.postgresql.org/docs/18/sql-notify.html, https://jdbc.postgresql.org/documentation/server-prepare/#listen--notify
