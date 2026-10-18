---
status: accepted
date: 2026-10-18
deciders: product owner (delegated), agent
---

# 0018 — Background jobs with db-scheduler

## Context and problem statement
AGENTS §2 locks db-scheduler (PostgreSQL-backed, no Redis) for jobs and timers. This ADR fixes how we integrate it: version, table, task conventions, tenant carriage and the jobs the kernel itself runs. Research: `phase-1a-minimum-core/epic-02-platform-kernel/research.md §R1, §R4, §R6`.

## Decision drivers
- Single execution across nodes; survives restarts.
- Tenant context in every tenant job (ADR-0017); fail closed.
- Fits the RLS conventions (ADR-0006) and the migration discipline (the app role has no DDL).

## Considered options
1. **db-scheduler with its Spring Boot 4 starter**
2. db-scheduler wired by hand
3. Spring `@Scheduled` with ShedLock (no persistent one-off tasks or retries, and another library)

## Decision outcome
Chosen option: **1**, `com.github.kagkarlsson:db-scheduler-spring-boot-4-starter:16.12.0` (Apache-2.0).
- **Table:** `system_scheduled_task` (configured table name, to follow the module-prefix rule), created by our Flyway migration from the upstream PostgreSQL DDL. It is a **platform table**, allow-listed in the RLS meta-test, so task data carries ids only, never content.
- **Polling:** the default `fetch-and-lock-on-execute` strategy (`lock-and-fetch` has open issue #933 on PostgreSQL).
- **Serialization:** Jackson 3 with explicit payload data classes; no Java serialization.
- **Task kinds:**
  - `TenantTask<P : TenantScopedPayload>` (one-off or recurring) binds `TenantContext` and `ActorContext(system:<task name>)` from the payload;
  - `PlatformTask` runs in platform scope and fans out per tenant by scheduling tenant tasks.
- **Failure policy:** bounded retries with exponential backoff per task type. A missing or unknown tenant is a permanent failure (no retry), logged and counted.
- **Kernel jobs:** audit anchoring sweep (every 15 minutes), incremental audit verification (hourly), full verification (weekly, throttled), resubmission of stale event publications (every 5 minutes), event-archive cleanup (daily), audit partition check (daily; it alerts only, because the app role cannot create partitions).
- **Shutdown:** graceful. A running execution either finishes or is picked up by another node after its heartbeat expires.

### Consequences
- Good: one PostgreSQL-backed mechanism for every background job; cluster-safe; tenant-safe by construction.
- Bad: another platform table that the app role can read across tenants. Mitigated by the ids-only rule, reviewed in each threat model.
- Follow-ups: progress status for user-visible jobs (E05 onwards) builds on task data plus a module table.

### Confirmation
- `SchedulerSingleExecutionIT`: two scheduler instances on one database run a task exactly once.
- `TenantContextPropagationIT`: a tenant task sees its tenant; a payload without one fails permanently.
- RLS meta-test allow-list entry with its reason.

## More information
Sources (accessed 2026-10-18): https://github.com/kagkarlsson/db-scheduler, https://github.com/kagkarlsson/db-scheduler/issues/933
