---
status: accepted
date: 2026-10-18
deciders: product owner (delegated), agent
---

# 0019 — Audit store: same-transaction hash chain, verification and anchoring

## Context and problem statement
AGENTS §3.3 requires an append-only audit log, hash-chained per tenant, written in the same transaction as the business change, with chain heads periodically anchored into an object-locked bucket. AGENTS names the Spring Modulith event publication registry as the outbox. This ADR fixes the concrete design. Research: `phase-1a-minimum-core/epic-02-platform-kernel/research.md §R5, §R6`.

## Decision drivers
- A business change without its audit record must be impossible; rollback must leave neither.
- Tampering (modify, delete, reorder, insert, truncate) must be detectable, including by an auditor holding an export.
- No measurable slowdown at the NFR load; no deadlocks.
- Ten years of events must stay verifiable and queryable.

## Considered options
1. **Chain-head row per tenant, locked at `beforeCommit`; entries hashed and inserted in the business transaction**
2. Advisory lock instead of a head row
3. Unsealed entries in the transaction, sealed later by a job in commit order
4. Audit rows written after commit from the event publication registry

## Decision outcome
Chosen option: **1**.
- **API:** module services call `AuditLog.record(action, objectType, objectId, before?, after?)`. Entries are buffered on the current transaction. Calling it outside a read-write tenant (or platform) transaction throws.
- **Flush:** a `TransactionSynchronization.beforeCommit` callback locks the tenant's `audit_chain_head` row (`SELECT … FOR UPDATE`), assigns consecutive `seq` values, computes the hashes, inserts the entries and updates the head. The lock is taken last in the transaction and held only until commit, so it cannot invert lock order with business rows. A flush failure aborts the commit.
- **Entry:** `tenant_id`, `seq`, `occurred_at` (server UTC, at flush), actor (type, id, acting-as id), client (IP, user agent), correlation id, action, object type and id, before/after (JSON with ids and changed fields only; no document content, no secrets), `prev_hash`, `hash`, and `canonical` (the exact bytes hashed).
- **Hash:** `SHA-256(prev_hash ‖ canonical)`, where canonical is sorted-key, compact, UTF-8 JSON that includes `"v":1`. The genesis `prev_hash` is `SHA-256("memo-audit-genesis:" + tenantId)`.
- **Chains:** one per tenant, starting with `tenant.created`. Platform operations (E07) use a dedicated platform chain id.
- **Append-only:** the app role has `INSERT, SELECT` only; a trigger rejects `UPDATE` and `DELETE` for every role.
- **Partitioning:** monthly range partitions on `occurred_at`, each with ENABLE + FORCE RLS and the tenant policy. They are created 12 months ahead by a Flyway `afterMigrate` callback on the migrator connection. A DEFAULT partition catches overflow, and a daily job alerts when fewer than 3 future partitions remain.
- **Verification:** a pure-Kotlin verifier recomputes hashes and checks sequence continuity, head consistency, agreement between columns and canonical text, and anchors. Jobs run incrementally every hour (checkpoint per tenant) and in full every week (throttled). A failure logs at ERROR, increments a metric and records `audit.chain.verification_failed` in the platform chain.
- **Anchoring:** every 15 minutes, for each tenant with new entries, `audit-anchors/<tenantId>/<seq>.json` (tenant, seq, hash, time) is written to the object-locked `records` bucket with a retention date. COMPLIANCE mode in production; GOVERNANCE with a short period in dev and tests (configurable).
- **The outbox** (event publication registry) carries the side effects of business changes, **not** the audit row itself. This is stricter than an audit written through the outbox: the audit row is part of the same commit.
- **Read and export** (viewer, export with a verifiable manifest) are built in E07, once authentication and authorization exist. E02 ships no audit endpoint.

### Consequences
- Good: same-commit guarantee; tamper detection by anyone holding an export plus the anchors; simple, measurable concurrency.
- Bad: the audited commits of one tenant serialize for a few milliseconds each. Measured by a 32-writer test; option 3 is the fallback if a tenant ever outgrows it.
- Bad: an attacker with database write access can rewrite the chain since the last anchor (at most 15 minutes) without the hashes alone revealing it. Keyed hashes or signed anchors can follow with 1b's key provider.
- Bad: `(tenant_id, seq)` uniqueness cannot be a constraint on a partitioned table. The head lock guarantees it and the verifier checks it.
- Follow-ups: retention and legal hold per partition (Phase 7); viewer and export (E07); sensitive-read events (E05).

### Confirmation
- `AuditSameTransactionIT`: rollback leaves neither the change nor the audit entry; commit leaves both.
- `AuditChainVerifierProperties` (jqwik): any modification, deletion, insertion, reordering or truncation is detected.
- `AuditTamperIT`: a row altered with the owner role is reported at its `seq`; a deleted tail is caught by the head and by the anchor.
- `AuditConcurrencyIT`: 32 concurrent writers produce a gapless chain without deadlock; flush latency is recorded.
- `AuditAppendOnlyIT`: `UPDATE` and `DELETE` fail for the app role and, through the trigger, for the owner.
- The RLS meta-test covers partitions.

## More information
Sources (accessed 2026-10-18): https://www.postgresql.org/docs/18/ddl-partitioning.html, https://www.rfc-editor.org/rfc/rfc8785, https://docs.aws.amazon.com/awscloudtrail/latest/userguide/cloudtrail-log-file-validation-intro.html
