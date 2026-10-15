# E02 Data and tenancy foundation — tasks

| # | Task | Size | Depends |
|---|---|---|---|
| T1 | Modulith Flyway layout (`spring-modulith-runtime`, `__root`, per-module folders); explicit module detection in production config | S | — |
| T2 | `IdGenerator` (UUIDv7, JUG) + `TenantId` + `TenantTransactions`/`TenantDsl` (fail-closed) | M | T1 |
| T3 | `tenancy` migrations: tenant registry, reference table `tenancy_setting`, view, function | S | T1 |
| T4 | jOOQ codegen pipeline (`JooqCodegenTest`, `updateJooq`, committed sources) and the tenancy repositories on generated code; `system_info` moves to generated code | M | T3 |
| T5 | `RlsMetaTest`, `CrossTenantIsolationIT`, `RlsNegativeIT` | M | T3, T4 |
| T6 | Testcontainers base: `TestDatabase` (done in E01), `TestObjectStorage` extracted; builders `aTenant()`, `aSetting()` | S | T4 |
| T7 | Demo tenant as code + seeder (dev and test), refusing under `prod` | S | T4 |
| T8 | ArchUnit rules + no-session-SET source scan | S | T2 |

## Acceptance criteria
- **T2**
  - Given no tenant transaction, when `TenantDsl.current()` is called, then `MissingTenantContextException` is thrown.
  - Given `inTenant(A)`, when `inTenant(B)` is nested, then `IllegalStateException` is thrown.
  - After `inTenant(A)` commits, a fresh query on a pooled connection sees `current_setting('app.tenant_id', true)` as NULL or `''`.
- **T3/T5**
  - Given tenants A and B, each with settings, when acting as B, then: reading, listing, the view, the function and a join of A's data return nothing; updating or deleting A's rows affects 0 rows; inserting a row with A's `tenant_id` fails.
  - Given RLS disabled on `tenancy_setting`, the same probes **do** see A's data (negative test).
- **T4**
  - `./gradlew :backend:updateJooq` regenerates `backend/src/main/jooq`.
  - A migration change without regeneration fails `./gradlew :backend:test` with an instruction.
- **T5 (meta)**
  - Every table with `tenant_id` has ENABLE + FORCE and a tenant policy with USING and WITH CHECK.
  - The app role owns nothing and has no BYPASSRLS or superuser.
  - Views are `security_invoker`; there are no `SECURITY DEFINER` functions.
  - Multi-column indexes on tenant tables start with `tenant_id`; every FK has a supporting index.
  - Every table name starts with a module identifier.
- **T7**
  - Seeding twice leaves exactly one demo tenant and the same settings.
  - Under the `prod` profile the seeder refuses.
