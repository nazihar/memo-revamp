# Test plan — E02 Platform kernel

## Scope and risks
New: tenant context everywhere, platform scope, jobs, outbox, audit chain, anchoring, cache invalidation. Highest risks: tenant context lost or wrong in background work (I1, I2), an unaudited change (R1), undetected tampering (T1–T3). See [design.md](design.md) and [threat-model.md](threat-model.md).

## Tests per slice
| Slice | Layer | Test (behaviour) | Data / fixtures | CI stage |
|---|---|---|---|---|
| T1 | Persistence | `TenantContextPropagationIT` (request filter, `@Transactional`, `TransactionTemplate`, `REQUIRES_NEW`, executor; fail-closed cases; cross-tenant rebinding refused) | fresh tenants A, B | PR |
| T1 | Unit | `TenantContextTest`: scope nesting rules (design §1 table) | — | PR |
| T1 | Architecture | `ArchitectureRulesTest`: no coroutines; `set_config('app.tenant_id'` only in the transaction manager; `app.scope` only in `PlatformTransactions`; no `@Scheduled` | — | PR |
| T2 | Persistence/RLS | `PlatformScopeIT`: lists tenants; zero rows in every tenant table; no updates; no mixing with tenant scope | tenants A, B with data | PR |
| T2 | Persistence/RLS | `RlsMetaTest` additions: partitions checked; platform tables allow-listed with reasons; `app.scope` only in `platform_*` policies; platform-chain policy shape | migrated schema | PR |
| T3 | Concurrency | `SchedulerSingleExecutionIT`: two scheduler instances, one execution | — | PR |
| T3 | Integration | `TenantTaskIT`: tenant and actor bound; missing tenant → permanent failure, metric | tenant A | PR |
| T4 | Module | `TenantEventsIT` (Modulith Scenario API): event after commit only; listener runs in the event's tenant; rollback → no listener | tenants A, B | PR |
| T4 | Integration | `OutboxResubmissionIT`: incomplete publication resubmitted once by the job | tenant A | PR |
| T4 | Architecture | `EventPayloadRulesTest`: listener parameter types; event fields are ids, enums, numbers or timestamps only | — | PR |
| T5 | Persistence | `AuditSameTransactionIT`: commit → change + entry; rollback → neither; injected flush failure → change rolled back | tenant A | PR |
| T5 | Persistence | `AuditRecordIT`: settings put/change/delete and tenant creation produce the expected entries (action, object, before/after, actor, correlation id) via `assertAudited` | fresh tenant | PR |
| T5 | Persistence | `AuditAppendOnlyIT`: app role UPDATE/DELETE refused; owner blocked by trigger | tenant A | PR |
| T5 | Persistence | `AuditPartitionIT`: current + 12 months of partitions with RLS; DEFAULT partition catches an out-of-range row; app role cannot call `audit_ensure_partitions` | migrated schema | PR |
| T5 | Unit | `CanonicalJsonTest` (stable bytes for equal input; key order; escaping; microsecond UTC); `AuditRedactionTest` | — | PR |
| T5 | Concurrency | `AuditConcurrencyIT`: 32 writers × 50 transactions in one tenant → gapless `seq`, valid chain, no deadlock; flush p95 logged and asserted < 5 ms on CI (soft: warns at 5 ms, fails at 25 ms) | tenant A | PR |
| T6 | Property (jqwik) | `AuditChainVerifierProperties`: random chains; any single modification/deletion/insertion/reordering/truncation is detected at the right `seq`; unmodified chains always verify | generated | PR (200 tries), nightly (10,000) |
| T6 | Persistence | `AuditTamperIT`: owner edits `canonical`; owner edits a column only; owner deletes a middle row → reported; checkpoint behaviour | tenant A | PR |
| T7 | Integration | `AuditAnchorIT`: anchor object written with retention; delete/overwrite refused by object lock; tail deletion + consistent head rewrite detected by anchor mismatch | SeaweedFS Testcontainer, tenant A | PR |
| T7 | Integration | `AuditPartitionCheckIT`: alert when < 3 future partitions | — | PR |
| T8 | Integration | `CacheInvalidationIT`: two listeners; commit evicts remote within 1 s; rollback evicts nothing; killed connection → full flush on reconnect | tenant A | PR |
| T8 | Integration | `TenantSettingsCacheIT`: hit avoids the database; put/delete invalidate | tenant A | PR |
| T9 | Performance | `AuditVerificationThroughput` (nightly): V5 chain (10 years, configurable rate) verified; throughput recorded against the ≥ 20,000 events/s budget | V5 generator | Nightly |
| — | Mutation | PIT on `audit` (verifier, canonical form, flush) and `common.tenant` | — | Nightly |

## Authorization matrix tests
No endpoints in E02. Database-level authorization: grants on audit tables (`AuditAppendOnlyIT`, `RlsMetaTest`), platform scope (`PlatformScopeIT`).

## State-machine tests
`audit_verification.status`: `OK → BROKEN` (on detection), `BROKEN` stays `BROKEN` until a human resets it (E07 viewer). Tested: `OK → OK` (clean incremental run), `OK → BROKEN`, `BROKEN` not cleared by a later clean run (`AuditTamperIT`).

## Security tests
STRIDE → tests as listed in [threat-model.md](threat-model.md): I1/I2 `TenantContextPropagationIT`, `TenantTaskIT`, `TenantEventsIT`; I3 `PlatformScopeIT`, `RlsMetaTest`; I4 `EventPayloadRulesTest`; T1–T3 `AuditAppendOnlyIT`, `AuditTamperIT`, `AuditAnchorIT`, `AuditChainVerifierProperties`; R1 `AuditSameTransactionIT`; R2 `AuditRecordIT`; D1 `AuditConcurrencyIT`; D2 `AuditPartitionIT`; D3 `CacheInvalidationIT`; D4 `TenantTaskIT`; E1 `AuditPartitionIT`; I5 `AuditRedactionTest`.
ASVS rows updated: V8 (tenant context in background work, platform scope), V16 (audit integrity, same-transaction logging, protection of logs).

## Performance
Budgets in design §10. PR: flush latency in `AuditConcurrencyIT`. Nightly: verification throughput on V5.

## Accessibility and i18n
None (no UI).

## Exploratory charters
1. Try to make any background path (job, listener, executor, resubmission) touch tenant data without its tenant, or with another tenant's (45 min).
2. Try to change the audit history so that verification still passes (45 min).
3. Break the listener connection and the scheduler (kill connections, restart one node mid-job) and look for stale caches or double executions (30 min).

## Acceptance mapping
T1 → `TenantContextPropagationIT`, `TenantContextTest`; T2 → `PlatformScopeIT`; T3 → `SchedulerSingleExecutionIT`, `TenantTaskIT`; T4 → `TenantEventsIT`, `OutboxResubmissionIT`, `EventPayloadRulesTest`; T5 → `AuditSameTransactionIT`, `AuditRecordIT`, `AuditAppendOnlyIT`, `AuditPartitionIT`; T6 → `AuditChainVerifierProperties`, `AuditTamperIT`; T7 → `AuditAnchorIT`, `AuditPartitionCheckIT`; T8 → `CacheInvalidationIT`, `TenantSettingsCacheIT`; T9 → `AuditVerificationThroughput`.

## Exit checklist
- [ ] All listed tests implemented and green in their stage
- [ ] No new quarantined tests
- [ ] ASVS checklist updated (V8, V16)
