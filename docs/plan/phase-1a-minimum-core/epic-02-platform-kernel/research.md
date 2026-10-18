# E02 Platform kernel — research

All sources were accessed on **2026-10-18**.

## R1 — Versions, licenses, maintenance

| Component | Current | In use | License | Maintenance | Decision |
|---|---|---|---|---|---|
| Spring Boot | 4.1.1 GA; 4.2.0-M2 (2026-09-24) | 4.1.1 | Apache-2.0 | Active; GA minors in May and November | **Stay on 4.1.x.** Take 4.2 through Renovate after GA, when every gate is green (not a milestone) |
| Spring Modulith | 2.1.1 GA; 2.2.0-M2 | 2.1.1 | Apache-2.0 | Active | Stay; add `spring-modulith-starter-jdbc` (BOM-managed) for the event publication registry |
| db-scheduler | 16.12.0 (2026-05-29); **`db-scheduler-spring-boot-4-starter`** exists | — | Apache-2.0 | Active (last push 2026-10-13, 1.6k stars, maintainer plus contributors) | **Adopt 16.12.0** with the Boot 4 starter |
| Caffeine | 3.3.1 (2026-10-13) | — | Apache-2.0 | Active (Ben Manes) | Adopt the Boot-managed version (override only if Boot's is older than a security fix) |
| pgjdbc | 42.7.14 (2026-10-07) | Boot-managed | BSD-2-Clause | Active | `PGConnection.getNotifications(timeout)` for `LISTEN` |
| jOOQ | 3.21.9 | Boot-managed | Apache-2.0 (OSS edition) | Active | No change |

Notes on db-scheduler:
- Issue #935 (16.12.0 "depends on itself with a different groupId") is a corrupted artifact from a JitPack proxy, not Maven Central. The Central POM depends on `com.github.kagkarlsson:db-scheduler:${project.version}`, as expected.
- Issue #933 (open): the `lock-and-fetch` polling strategy on PostgreSQL can pick more executions than the limit. **We keep the default `fetch-and-lock-on-execute` strategy**, which is not affected.

Sources: Maven Central metadata (`repo1.maven.org/maven2/...`), https://github.com/kagkarlsson/db-scheduler/releases, https://github.com/kagkarlsson/db-scheduler/issues/933, https://github.com/kagkarlsson/db-scheduler/issues/935, https://spring.io/blog/2026/08/20/spring-boot-4-2-0-M1-available-now/

## R4 — Tenant context propagation

### Where the context lives
| Option | Pros | Cons |
|---|---|---|
| `ThreadLocal` holder | Familiar | Must be cleared by hand; leaks across pooled and virtual threads if a `finally` is missed |
| Bound only to the transaction (today's `TenantTransactions`) | Already works; cannot outlive the transaction | Every caller must know the tenant and open the transaction itself; `@Transactional` services cannot pick it up |
| **`ScopedValue` (JEP 506, final in JDK 25)** | Immutable, bounded to a lexical scope, cannot leak, cheap with virtual threads (we run with `spring.threads.virtual.enabled=true`) | Does not inherit into executor threads, only into `StructuredTaskScope` children, so executors need a decorator |

**Recommendation: `ScopedValue`** for the request/job/listener scope, plus today's transaction binding as the proof that a transaction really carries the tenant.

### How every transaction gets `set_config`
| Option | Pros | Cons |
|---|---|---|
| AOP around `@Transactional` | No framework subclassing | Misses `TransactionTemplate` and Modulith-internal transactions; ordering issues with other advisors |
| `TransactionTemplate` only (today) | Explicit | Every call site has to remember it; `@Transactional` services stay tenant-less |
| **Subclass `JdbcTransactionManager`; override `prepareTransactionalConnection(con, definition)`** (the protected hook Spring calls right after `begin`) | Covers every transaction, whatever started it; one place | Couples to a protected Spring hook (stable since Spring 4.3; covered by a test that fails if it stops being called) |

**Recommendation: the transaction-manager hook.** If a tenant is in scope it runs `set_config('app.tenant_id', ?, true)` and binds the tenant to the transaction; if not, the transaction is tenant-less (RLS returns nothing; `TenantDsl` throws).

### Jobs, async listeners, executors
- **db-scheduler:** task data is serialized per execution. The tenant must travel **in the task data** (`tenantId` field). The handler binds the scope before any database access. Missing tenant: the execution fails and is not retried (fail closed).
- **Spring Modulith async listeners:** `@ApplicationModuleListener` = `@Async` + `@Transactional(REQUIRES_NEW)` + `@TransactionalEventListener`. A listener may run long after the publishing request (resubmission of incomplete publications after a crash), so the publisher's thread context **cannot** be the source of truth: the tenant must travel **in the event**. Options:
  - an aspect that reads the tenant from the event and binds it before the transactional advice opens the transaction (works, but depends on advisor ordering between `@Async`, our aspect and `@Transactional`);
  - **our own listener convention**: `@Async @TransactionalEventListener` methods whose body runs `tenantEvents.handle(event) { … }`, which binds the scope and opens the tenant transaction. Explicit, no ordering magic; forgetting it fails closed (`TenantDsl` throws, the publication stays incomplete and visible).
  - **Recommendation: the explicit convention**, enforced by ArchUnit (listener parameters must implement `TenantScopedEvent` or `PlatformEvent`).
- **Executors:** a Spring `TaskDecorator` captures the bound scope in the submitting thread and re-binds it in the worker. Boot applies a `TaskDecorator` bean to its auto-configured executors, including the virtual-thread one.
- **Kotlin coroutines:** not used on the backend. They would need a separate `ThreadContextElement` and add a propagation path nobody tests. Forbidden by an ArchUnit rule; revisit by ADR if a need appears.

### Tenant enumeration for platform jobs
Audit anchoring and verification run for every tenant, so a platform job must list tenant ids. Today `tenancy_tenant` is visible only to its own tenant (`tenant_self`). Options:
- per-tenant recurring task instances created at tenant creation (still needs enumeration to reconcile existing tenants);
- a non-RLS mirror table of tenant ids (a second source of truth);
- **a transaction-local platform scope** (`set_config('app.scope', 'platform', true)`) that a policy on `tenancy_tenant` accepts **for SELECT only**. No other table accepts it, so platform scope sees the registry and nothing else.

**Recommendation: platform scope**, set only by `PlatformTransactions` (source scan forbids it elsewhere). E07 builds operator access on it (ADR-OPS).

Sources: https://openjdk.org/jeps/506, https://docs.spring.io/spring-framework/reference/data-access/transaction.html, https://docs.spring.io/spring-modulith/reference/events.html, https://github.com/kagkarlsson/db-scheduler#readme

## R5 — Audit hash chain under concurrent writes

Requirement: written in the same transaction as the change, append-only, hash-chained per tenant, tamper detection, anchoring.

| Option | Same transaction? | Contention | Complexity | Notes |
|---|---|---|---|---|
| A. Chain-head row per tenant, `SELECT … FOR UPDATE`, entries hashed and inserted **at `beforeCommit`** | Yes | Lock held only from flush to commit (milliseconds); serializes audited commits per tenant | Low | Lock always taken last, so no lock-order inversion with business rows |
| B. Advisory lock per tenant + read the last entry | Yes | Same as A | Low | Lock is invisible in the schema; reading the last entry of a partitioned table is slower than one head row |
| C. Write unsealed entries in the transaction; a sealer job hashes them in commit order | Entry yes, hash no | None on the write path | High | Commit order is not knowable from sequence values; needs gap handling; unsealed rows can be changed undetected until sealed |
| D. Outbox only: publish an event in the transaction, write the audit row after commit | No (publication only) | None | Medium | Between commit and processing the change exists without its audit row. Rejected: AGENTS §3.3 says a change without its audit record must be impossible |

**Recommendation: A.** Expected load at the NFR target (1,000 concurrent users per mid-size tenant) is tens of audited commits per second per tenant; a single-row lock held for a few milliseconds sustains hundreds per second. A concurrency test with 32 writers measures it; C is the documented fallback if a tenant's write rate ever outgrows A.

Further findings:
- **Canonical form.** Hashing a re-serialization of stored columns is fragile (number formats, key order, `jsonb` normalisation). Instead the app produces **canonical JSON bytes once** (sorted keys, no insignificant whitespace, UTF-8; the RFC 8785 subset we need, since we control every field) and **stores those exact bytes**. Verification re-hashes the stored bytes and checks that the queryable columns match the parsed canonical text.
- **Hash.** `hash = SHA-256(prev_hash ‖ canonical_bytes)`; the genesis `prev_hash` is `SHA-256("memo-audit-genesis:" + tenantId)`. A plain hash is verifiable by an auditor from an export with no key. A database-only attacker could recompute the whole chain, which is why chain heads are **anchored** outside the database; a keyed hash or signed anchors can be added with 1b's key provider.
- **Anchoring frequency.** Every 15 minutes per tenant with new entries (configurable). This bounds the window in which a full-chain rewrite goes unnoticed. Anchors go into the object-locked `records` bucket (ADR-0005) with a retention date.
- **Verification at scale.** SHA-256 runs at hundreds of MB/s per core; 10 years of a large tenant (tens of millions of ~0.5 KB entries) is a few GB. Reading is the bottleneck, so verification is **incremental** (checkpoint of the last verified sequence and hash) and a full re-verification is a scheduled, throttled job.
- **Partitioning.** Declarative range partitions by month on `occurred_at`. PostgreSQL requires unique constraints on a partitioned table to include the partition key, so `(tenant_id, seq)` uniqueness is guaranteed by the chain-head lock and checked by the verifier, not by a constraint. RLS on the parent does not cover direct queries on a partition, so every partition gets ENABLE + FORCE + the policy (the meta-test checks partitions too). The app role has no DDL, so partitions are created **with the migrator connection** by a Flyway callback on every migrate run (12 months ahead), with a DEFAULT partition as a safety net.
- **Append-only.** The app role gets `INSERT, SELECT` only on the audit table (`UPDATE, DELETE, TRUNCATE` revoked), plus a trigger that rejects `UPDATE` and `DELETE` for every role.

Sources: https://www.postgresql.org/docs/18/ddl-partitioning.html, https://www.postgresql.org/docs/18/ddl-rowsecurity.html, https://www.postgresql.org/docs/18/explicit-locking.html, https://www.rfc-editor.org/rfc/rfc8785, https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html

## R6 — Event publication registry as the outbox

- Properties (Spring Modulith 2.1 appendix): `spring.modulith.events.completion-mode` (`update` | `delete` | `archive`), `jdbc.schema`, `jdbc.schema-initialization.enabled`, `republish-outstanding-events-on-restart` (default false; **not recommended with several instances**), and staleness monitoring (`staleness.processing`, `staleness.published`, `staleness.check-interval`).
- The table name is fixed (`event_publication`, `event_publication_archive`); only the schema is configurable. It has no `tenant_id` column.
- Decisions:
  - **Schema initialization off**; our own Flyway migration creates the tables (the app role has no DDL).
  - **Completion mode `archive`**, with archive cleanup by a db-scheduler job after a retention period (default 30 days).
  - **No republish on restart.** A cluster-safe db-scheduler job resubmits stale publications (`IncompleteEventPublications`), once per cluster.
  - The two tables are **platform tables** (allow-listed in the RLS meta-test). Because the app role can read every tenant's publications, **events carry ids only** (tenant id, object ids, action), never content. Listeners load what they need inside the tenant transaction.
- The audit row is **not** written through the outbox (R5, option D rejected). The outbox serves side effects: notifications, cache invalidation, later search indexing.

Source: https://docs.spring.io/spring-modulith/reference/appendix.html

## R7 — `LISTEN/NOTIFY` from the JVM

- `NOTIFY` inside a transaction is delivered **only at commit**, and not at all on rollback; this gives exactly the semantics cache invalidation needs. Payloads are limited to 8,000 bytes; the server queue is 8 GB by default.
- pgjdbc receives notifications on a connection that has run `LISTEN`, through `PGConnection.getNotifications(timeoutMillis)`. That connection must not return to the pool, so the listener opens **one dedicated connection** (same app credentials) outside Hikari and loops on a virtual thread.
- **Missed notifications:** anything sent while the listener is disconnected is lost. On every (re)connect the node **flushes all local caches**; caches also have a TTL as a safety net.
- **PgBouncer in transaction mode** does not support `LISTEN`. The listener connection must go directly to PostgreSQL. This becomes a documented install requirement (configuration reference, Phase 7), with a separate optional URL for the listener.
- The publishing node also receives its own notification; it invalidates locally right after commit as well, so its own next read is correct without waiting.

Sources: https://www.postgresql.org/docs/18/sql-notify.html, https://www.postgresql.org/docs/18/sql-listen.html, https://jdbc.postgresql.org/documentation/server-prepare/#listen--notify, https://www.pgbouncer.org/features.html

## Industry practice

- **Hash-chained audit logs** (Certificate Transparency's Merkle logs; AWS CloudTrail log-file integrity validation with signed digest files; QLDB journals). We adopt CloudTrail's model in a simpler form: a per-tenant chain plus periodic digests (anchors) stored where the database operator cannot rewrite them. A Merkle tree is not needed at our volumes; the chain gives the same detection guarantees for an ordered log.
- **Board portals** (OnBoard, Diligent) market "complete audit trails" tied to retention and legal hold, but publish no tamper-evidence design. Offering auditor-verifiable exports (chain plus anchors) is a differentiator for regulated customers.
- **db-scheduler and transactional outboxes** are the standard JVM pattern for "no Redis, no broker" systems; Spring Modulith's registry is the outbox the AGENTS decisions already name.
