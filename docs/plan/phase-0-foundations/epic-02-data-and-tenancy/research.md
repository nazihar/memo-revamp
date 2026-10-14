# E02 Data and tenancy foundation — research

All sources were accessed on **2026-10-14**. The versions in E01's research (R1) still hold; they were re-checked the same day.

## R6 — jOOQ code generation with Flyway + Testcontainers

| Option | Pros | Cons |
|---|---|---|
| A. jOOQ Gradle plugin (`org.jooq.jooq-codegen-gradle`) against a DB that a custom Gradle task starts (Testcontainers on the build classpath) | Generation runs on every build that needs it | Docker becomes a **compile-time** requirement. The Flyway ordering of Spring Modulith (per-module history tables, dependency order) would have to be re-implemented in Gradle. IDE sync gets slow. |
| B. `DDLDatabase` (jOOQ parses the SQL scripts, no DB) | No Docker | Fails on PostgreSQL-specific DDL (policies, `FORCE ROW LEVEL SECURITY`, functions). Diverges from the real schema. |
| C. Third-party Testcontainers jOOQ plugin | Packaged | Another single-maintainer plugin on the build path, and it also bypasses Modulith ordering |
| **D. Generated sources committed; a JUnit "codegen" test migrates a Testcontainers DB *by booting the application* (real Modulith Flyway ordering, real role script) and runs jOOQ `GenerationTool`. In normal runs it fails on drift; `./gradlew :backend:updateJooq` rewrites the sources.** | The schema used for codegen is exactly the runtime one. Compilation needs no Docker. CI checks drift. The pattern matches the OpenAPI contract check. | Generated code is in git (reviewable diff, which is useful). A developer must run `updateJooq` after changing a migration; the drift test makes that explicit. |

**Recommendation: D** (ADR-0007). jOOQ Open Source Edition (Apache-2.0) supports the latest PostgreSQL release, and we run PostgreSQL 18 (https://www.jooq.org/download/).

## R7 — Spring Modulith per-module Flyway migrations

- Enabled with `spring.modulith.runtime.flyway-enabled=true` and the `spring-modulith-runtime` JAR.
- Root migrations live in `db/migration/__root` and use history table `flyway_schema_history`.
- Module migrations live in `db/migration/<module>`, each with its own history table `flyway_schema_history_<module>`, a baseline of 0, and version numbers scoped to the module.
- Modules migrate **in dependency order**. In tests, only the modules taking part in the test run are migrated.
- Source: https://docs.spring.io/spring-modulith/reference/runtime.html

**Mapping decision:** one PostgreSQL schema (`memo`), with **tables prefixed by module identifier** (`tenancy_tenant`, `system_info`, …). A separate schema per module was considered and rejected: RLS, grants and search_path would multiply for every module without improving isolation, because module boundaries are enforced in code (Modulith) and by a naming check. The prefix rule is checked by the catalog meta-test: every table name must start with an existing module identifier.

## R8 — RLS details

| Topic | Finding | Decision |
|---|---|---|
| `ENABLE` vs `FORCE` | `ENABLE` alone does not apply to the table owner. `FORCE` applies policies to the owner too (PostgreSQL docs, "Row Security Policies") | Both, always |
| `USING` vs `WITH CHECK` | `USING` filters reads, updates and deletes; `WITH CHECK` validates new rows. Without `WITH CHECK`, `USING` is reused for `INSERT`/`UPDATE`, but stating it explicitly is clearer | Policy `FOR ALL … USING (…) WITH CHECK (…)` |
| Evaluating `current_setting` once | Wrapping it in a scalar sub-select `(select …)` makes the planner evaluate it once as an InitPlan instead of once per row | Required form (AGENTS §3.2) |
| Unset vs reset setting | `current_setting('app.tenant_id', true)` returns **NULL** if the setting was never defined in the session, but **''** (empty string) once a `SET LOCAL` in an earlier transaction on the same pooled connection has ended. `''::uuid` raises an error, so behaviour would depend on connection history. | Use `nullif(…, '')`. With no tenant there are **always zero rows** and inserts are rejected. The application layer adds a loud fail-closed (ADR-0006). |
| Views | By default a view runs with the **view owner's** privileges, which bypasses RLS for the owner unless `FORCE` is set and policies match. `security_invoker = true` (PostgreSQL ≥ 15) applies the caller's RLS. | Every view must be `security_invoker = true`; the meta-test checks this |
| Functions | `SECURITY DEFINER` functions run as their owner (the migrator) and could bypass RLS when `FORCE` is not in effect, or leak through search_path | Forbidden unless allow-listed in the meta-test |
| Catalog queries | `pg_class.relrowsecurity` / `relforcerowsecurity`; `pg_policy` (`polqual`, `polwithcheck`, `pg_get_expr`); `pg_roles.rolbypassrls`; `pg_index.indkey`; `pg_constraint` (`contype = 'f'`, `conkey`); `pg_class.reloptions` for `security_invoker`; `pg_proc.prosecdef` | Used by the meta-test |

Source: https://www.postgresql.org/docs/18/ddl-rowsecurity.html, https://www.postgresql.org/docs/18/sql-createview.html

## R9 — Backend test libraries

| Need | Options | Recommendation |
|---|---|---|
| Assertions | **AssertJ** (Boot-managed; Spring's `MockMvcTester` is AssertJ-based) vs Kotest assertions (Kotlin-idiomatic infix style) | **AssertJ**: already in use, integrates with Spring test tooling, one style across the code base |
| Property-based | **jqwik 1.10.2** (EPL-2.0; JUnit Platform engine; shrinking; **stateful action-chain testing**, which the workflow invariants in Phase 2 need) vs Kotest property (Apache-2.0; coroutine-based `checkAll`; stateful testing is less mature) | **jqwik**. Risk: one main maintainer. It is dev-only, so this is acceptable; re-evaluate in Phase 2. |

## UUIDv7 generation

`AGENTS.md §4` requires UUIDv7 primary keys generated by the application. JDK 25 has no UUIDv7 factory.

| Option | Notes |
|---|---|
| **java-uuid-generator (FasterXML JUG) 5.2.0** | Apache-2.0; FasterXML org; `TimeBasedEpochGenerator` is monotonic within a millisecond (the `…Random…` variant is not) |
| uuid-creator 6.1.1 | MIT; single maintainer; last release April 2025 |
| Hand-written generator | Small, but we would own the monotonicity and randomness correctness |

**Recommendation: JUG**, wrapped in our own `IdGenerator` interface so tests can inject deterministic ids.

## Industry practice

- **AWS SaaS Factory, "Multi-tenant data isolation with PostgreSQL Row Level Security":** a session variable plus RLS policies plus a non-owner application role. We adopt this, using transaction-local `set_config` instead of session `SET`, because pooled connections otherwise leak tenant context.
- **Citus and Crunchy Data guidance:** keep `tenant_id` first in composite indexes and in partitioning keys. Adopted as a meta-test rule.
