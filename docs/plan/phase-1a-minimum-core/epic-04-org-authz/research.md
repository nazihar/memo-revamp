# E04 Org directory and authorisation — research

All sources were accessed on **2026-10-29**.

## R1 — Versions, licences, maintenance

| Component | Current | Decision |
|---|---|---|
| jOOQ, PostgreSQL, Caffeine, Bucket4j | already in the build (E02, E03), unchanged | Reuse; no new runtime dependency |
| PostgreSQL `ltree` | contrib extension, ships with PostgreSQL 18; available on the major managed services | Not adopted (R12) |
| `pg_trgm`, `btree_gist` | contrib extensions | Not adopted: the people picker and the overlap guards work without them at our volumes (R12, R14) |
| jCasbin (`org.casbin:jcasbin`) | 1.9x line (Apache-2.0, Apache incubation of Casbin), active | Not adopted (R13) |
| Cedar Java (`com.cedarpolicy:cedar-java`) | 4.9.0 (2026-05), Apache-2.0, AWS-maintained; JNI to a Rust core; three CVEs fixed in 2026 (CVE-2026-55771/55772/55773) | Not adopted (R13) |

Sources: https://github.com/apache/casbin-jcasbin, https://repo1.maven.org/maven2/com/cedarpolicy/cedar-java/4.9.0/, https://nvd.nist.gov/vuln/detail/CVE-2026-55772, https://www.postgresql.org/docs/current/ltree.html

## R12 — Org model storage, effective dating, history

### Units tree

| Option | Subtree query | Move a subtree | Integrity | Notes |
|---|---|---|---|---|
| Adjacency list + `WITH RECURSIVE` | recursive query each time | one row | FK on `parent_id` | Simple; fine for a few thousand nodes, but every permission-filtered list would recurse |
| `ltree` materialised path + GiST | `path <@ 'a.b'`, indexed | rewrite descendants' paths | none on the path | Needs `CREATE EXTENSION` (some on-prem DBAs restrict extensions); labels limited to `[A-Za-z0-9_]` |
| Closure table | join, indexed | delete + insert O(subtree × depth) rows | FKs on both ends | Most rows; most write code |
| **Adjacency list + `uuid[]` ancestor array + GIN** | `ancestors @> array[:unit]` (GIN) or `unit_id = any(:subtree)` | one `UPDATE` of descendants' arrays | FK on `parent_id`; array checked by a consistency test | No extension; the array is derived data rebuilt from `parent_id` |

Expected size: a bank has 50–1,500 units, depth ≤ 10, a few changes a month. Reads dominate (every permission check with a unit scope). The org tree is cached per tenant (E02 cache, ADR-0020), so most subtree questions are answered in memory; the array serves SQL pushdown (R13).

Cybertec measured `WITH RECURSIVE` as fast enough for moderate trees, with `ltree` ahead for large read-heavy ones; neither changes the picture at our size.

**Recommendation:** adjacency list with a derived ancestor array (ADR-0026).

### Effective dating and "who held this position on date D"

| Option | Pros | Cons |
|---|---|---|
| Audit-only history | No extra tables | "As of D" means replaying audit; slow and fragile |
| Full bitemporal tables (valid time + transaction time) for every entity | Answers every question | Heavy for 1a; every query needs two time predicates |
| **Valid-time ranges where the business asks "as of D"** (assignments, reporting lines, unit and position existence), audit for everything else (renames, attribute edits) | "As of D" is one indexed query; corrections stay visible in the audit trail | Renames are not queryable "as of D" (acceptable: the record snapshots names, R12 note) |

- Ranges are half-open calendar dates `[valid_from, valid_to)` in the tenant's time zone, `valid_to` null for open-ended. "As of D": `valid_from <= D and (valid_to is null or valid_to > D)`.
- Overlap rules (one primary holder per position, one primary position per user at a time) are checked in the service under a row lock on the position and the user; an exclusion constraint would need `btree_gist`.
- Workday and SAP SuccessFactors separate the **position** (a seat in an organisation that persists when its holder leaves) from the **job** of the person; changes are effective-dated and moving positions between organisations takes an effective date. We adopt the seat model ("positions belong to units; people are assigned to positions"), which is also how e-office systems route files ("send to the Deputy Director, Credit", not to a person).
- **The record snapshots names.** Later phases (note sheets, approvals) store the actor's position title and unit name at the time of the action, so history of the record never depends on today's directory.

Sources: https://www.cybertec-postgresql.com/en/postgresql-ltree-vs-with-recursive/, https://doc.workday.com/workday-education/en-us/course-manuals/hcm-core-for-administrators/staffing-models.html, https://www.suretysystems.com/insights/workday-position-management-101/, https://www.postgresql.org/docs/current/arrays.html#ARRAYS-SEARCHING, https://www.postgresql.org/docs/current/gin.html

## R13 — Authorisation approach

| Option | SQL pushdown for lists | Auditability / explainability | On-prem simplicity | Performance | Risk |
|---|---|---|---|---|---|
| **In-code policy service** (Kotlin; permissions in a code catalogue, roles and grants as tenant data) | Native: the service returns jOOQ `Condition`s | Decisions are plain code + data, unit-tested | Nothing to deploy | In-memory check over cached grants | We own the logic; mitigated by the matrix tests |
| jCasbin (model + policy files, RBAC with domains) | Filtered policy loading only; no translation of business rows into SQL | Policies readable, but our rules (subtree scope, clearance) need custom functions | Library only | Fast enforcement | Model language is another thing to learn; scope rules end up in custom code anyway |
| Cedar (`cedar-java`) | Partial evaluation yields residual policies, not SQL; we would translate ourselves | Strong (formal analysis) | Native library via JNI; three 2026 CVEs | Fast | Translation layer is new, security-critical code |

Industry: GitHub, GitLab and Atlassian implement RBAC in application code with fixed permission catalogues and role templates; tenant admins combine permissions into custom roles. Board portals (Diligent, OnBoard) expose fixed roles plus per-workspace permissions. Classification versus clearance comes from government document handling (e-office systems, document classification schemes).

**Recommendation:** in-code policy service with a fixed permission catalogue, tenant-defined roles, unit-scoped grants and a jOOQ condition builder (ADR-0027). Revisit a policy engine when committee membership, recusal and delegation (Phases 2–4) make rules data-driven enough to justify it; the `Policy` interface keeps that door open.

Sources: https://casbin.org/docs/get-started, https://docs.cedarpolicy.com/, https://docs.github.com/en/organizations/managing-user-access-to-your-organizations-repositories/managing-repository-roles/about-custom-repository-roles, https://docs.gitlab.com/user/custom_roles/

## R14 — People picker search without extensions
- Volumes: 5,000 users and 2,000 positions per large tenant (V3 synthetic volume).
- `lower(display_name) like :q || '%'` on a `(tenant_id, lower(display_name) text_pattern_ops)` index handles prefix search; a word-start match (`'% ' || :q || '%'`) over 7,000 rows is a scan of well under 5 ms. `pg_trgm` is not needed at this size; Phase 5 search (OpenSearch) covers full text.
- Arabic names: no case folding; we normalise to NFC and strip tatweel on both sides.
- Rate limit with the E03 mechanism (ADR-0025): 60 searches per minute per user.

## R15 — Endpoint-coverage meta-test
- Spring MVC exposes every handler through `RequestMappingHandlerMapping.getHandlerMethods()`; a test can enumerate method + path patterns and compare them with the union of the matrix files.
- Deny-by-default at runtime: a `HandlerInterceptor` (or an `AuthorizationManager` keyed by handler) that rejects any handler without an explicit access annotation gives the same guarantee in production, not only in tests.
