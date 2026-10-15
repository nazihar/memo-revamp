# E02 Data and tenancy foundation — threat model (STRIDE)

## Data flows
1. Backend (as `memo_app`) → PostgreSQL: tenant-scoped SQL inside `inTenant` transactions.
2. Flyway (as `memo_migrator`) → PostgreSQL: DDL at startup or as a deploy job.
3. Demo seeder → PostgreSQL (dev and test only).

| # | Threat | Flow | Mitigation | ASVS 5.0 | Test |
|---|---|---|---|---|---|
| I1 | A tenant reads another tenant's rows through a missing `WHERE` | 1 | RLS ENABLE + FORCE with a policy on every tenant table; app role not owner, NOBYPASSRLS | V8.2.2 (data-level access), V8.4.1 (multi-tenant isolation) | `CrossTenantIsolationIT` (read, list) |
| T1 | A tenant updates or deletes another tenant's rows | 1 | Same policy as I1, applied to UPDATE/DELETE | V8.2.2 | `CrossTenantIsolationIT` (update, delete → 0 rows) |
| T2 | A tenant inserts rows labelled with another tenant's id | 1 | `WITH CHECK` | V8.2.2 | `CrossTenantIsolationIT` (insert foreign `tenant_id` → error) |
| I2 | Leak through a view or function that runs as its owner | 1 | `security_invoker` views; no `SECURITY DEFINER` (meta-test) | V8.2.2 | `CrossTenantIsolationIT` (view, function); `RlsMetaTest` |
| I3 | Tenant context leaks across pooled connections | 1 | Transaction-local `set_config(…, true)` only; no session `SET` (source scan) | V8.4.1 | `TenantTransactionsTest` (after commit, the same connection sees no tenant); `NoSessionSetTest` |
| I4 | Code runs without a tenant and silently sees "all tenants" | 1 | App-level `MissingTenantContextException`; DB returns zero rows when unset (`nullif`) | V8.4.1 | `TenantTransactionsTest`, `CrossTenantIsolationIT` (no context → 0 rows) |
| E1 | The app role escalates by owning tables, or migrations hand it DDL | 1, 2 | Role script: default privileges are DML only; meta-test checks ownership and BYPASSRLS | V8.x least privilege | `RlsMetaTest`, `SystemInfoRepositoryIT` |
| E2 | A new table forgets RLS | 2 | The catalog meta-test fails the build | V8.4.1 | `RlsMetaTest` + `RlsNegativeIT` |
| D1 | Policy predicate degrades queries (per-row `current_setting`) | 1 | InitPlan form; `tenant_id`-leading indexes | V15 performance | `EXPLAIN` assertion in `CrossTenantIsolationIT` |
| S1 | The demo seeder runs in production and creates a known tenant | 3 | Refuses under the `prod` profile; explicit flag only | V13 configuration | `DemoTenantSeedIT` (prod profile → refuses) |

## Residual risks
- Platform-level (cross-tenant) operations, such as listing all tenants, have no path yet. Phase 1a adds a separate audited role or path; until then nothing can enumerate tenants.
- Connection-pool behaviour under `statement_timeout` or errors was not exercised beyond rollback. Phase 1a tests add resilience cases.
