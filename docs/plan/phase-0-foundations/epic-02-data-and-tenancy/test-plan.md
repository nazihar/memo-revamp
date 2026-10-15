# Test plan — E02 Data and tenancy foundation

## Scope and risks
The highest risk in the product is cross-tenant leakage. See [design.md](design.md) and [threat-model.md](threat-model.md).

## Tests per slice
| Slice | Layer | Test (behaviour) | Data / fixtures | CI stage |
|---|---|---|---|---|
| T2 | Property (jqwik) | `IdGeneratorProperties`: ids are UUID version 7, unique, and strictly increasing for sequential generation | — | PR |
| T2 | Persistence | `TenantTransactionsTest`: fail-closed outside context; cross-tenant nesting rejected; same-tenant nesting joins; setting reverts after commit and after rollback | Testcontainers DB | PR |
| T4 | Contract (schema) | `JooqCodegenTest`: generated sources equal the committed ones | Testcontainers DB via app boot | PR |
| T5 | Persistence/RLS | `RlsMetaTest`: catalog rules (design §7, ADR-0006) | migrated schema | PR |
| T5 | Persistence/RLS | `CrossTenantIsolationIT`: read, list, update, delete, insert foreign `tenant_id`, join, view, function, no-context → 0 rows; `EXPLAIN` shows InitPlan | fresh tenants A, B per class | PR |
| T5 | Persistence/RLS | `RlsNegativeIT`: with `tenancy_setting` RLS disabled (owner, restored in `finally`), the probes detect leaks | same | PR |
| T6 | — | builders used by all of the above | — | — |
| T7 | Integration | `DemoTenantSeedIT`: idempotent; refuses under `prod` | — | PR |
| T8 | Architecture | `ArchitectureRulesTest` (ArchUnit) and `NoSessionSetTest` (source scan of `src/main` and migrations) | — | PR |
| T3 | Persistence | `TenantSettingsIT`: put, get, list, delete; key validation; optimistic-lock conflict on a stale version | fresh tenant | PR |

## Authorization matrix tests
Database roles: `SystemInfoRepositoryIT` (E01) + `RlsMetaTest`.

## State-machine tests
Tenant status `ACTIVE ⇄ SUSPENDED`: no transitions are exposed in Phase 0, so none are tested yet (Phase 1a).

## Security tests
STRIDE I1–I4, T1–T2 → `CrossTenantIsolationIT`; E1–E2 → `RlsMetaTest` + `RlsNegativeIT`; I3 → `TenantTransactionsTest` + `NoSessionSetTest`; S1 → `DemoTenantSeedIT`.

## Performance
`EXPLAIN` InitPlan check (D1). Seeded volumes start in Phase 1a.

## Exploratory charters
1. Try to read another tenant's settings through any SQL the app role can run (30 min).
2. Break pooled-connection hygiene: exceptions mid-transaction, nested calls (30 min).

## Acceptance mapping
T2 → `TenantTransactionsTest`; T3/T5 → `CrossTenantIsolationIT`, `RlsNegativeIT`; T4 → `JooqCodegenTest`; meta → `RlsMetaTest`; T7 → `DemoTenantSeedIT`.

## Exit checklist
- [x] All listed tests implemented and green in their stage (backend total 66; `TenantTransactionsTest` was implemented as `TenantTransactionsIT`, `NoSessionSetTest` as a rule inside `ArchitectureRulesTest`)
- [x] No new quarantined tests
- [x] ASVS checklist updated (V8 multi-tenant rows)
