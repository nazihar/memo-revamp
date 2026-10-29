# E04 Org directory and authorisation — threat model (STRIDE)

## Data flows
1. Browser → backend: directory reads, people picker, org edits, role/grant/clearance changes.
2. Backend ↔ PostgreSQL: org and authz tables (RLS), audit entries.
3. Node ↔ node: cache evictions through `LISTEN/NOTIFY` (ADR-0020).

| # | Threat | Flow | Mitigation | ASVS 5.0 | Test |
|---|---|---|---|---|---|
| S1 | A stale cached grant keeps access after revocation | 1, 3 | Eviction at commit on every node; reconnect flushes all caches; TTL as a safety net; revocation of a privileged grant also re-evaluates the session's MFA requirement | V8.3.x | `GrantCacheIT` (two nodes: revoke on A, denied on B) |
| T1 | Scoped admin edits outside their subtree, or moves their subtree to escape scope | 1 | Scope checked on the object's unit **and** the move target; scope root cannot be moved or closed by its scoped holder | V8.2.2 | matrix (`branchAdmin` outside scope), `OrgScopeIT` |
| T2 | Tree corruption (cycle, wrong ancestors) breaks subtree checks and widens access | 2 | Single service for tree writes; cycle check under lock; consistency test of `ancestors` vs `parent_id` | V8.x, V15.x | `OrgTreeIT` |
| T3 | Concurrent edits create overlapping assignments (two holders of one seat) | 1, 2 | Row locks on position and user; guarded updates with version | V15.4.x | `AssignmentConcurrencyIT` (latch) |
| R1 | Denial of an authorisation change (who granted admin to whom) | 1 | Audit events with before/after and actor; step-up on authorisation changes | V16.3.x | `AuthzAuditIT` |
| I1 | Directory search leaks other tenants' people or enumerates the directory at scale | 1, 2 | RLS; `directory.read`; 60/min rate limit; results capped at 20 | V8.4.1, V2.4 | `DirectoryCrossTenantIT`, `PickerRateLimitIT` |
| I2 | Existence of another tenant's objects leaks through 403 vs 404 | 1 | Ids are loaded under RLS first: absent → 404 regardless of permission | V8.4.x | matrix (`otherTenantAdmin`) |
| I3 | Classified object reachable through a list endpoint that filters after fetching | 2 | `Policy.condition` in SQL; property test that SQL filter = in-memory decision | V8.2.x | `PolicyConditionIT` |
| E1 | An endpoint ships without an authorisation rule | 1 | Access annotation required on every handler (runtime deny), endpoint-coverage meta-test | V8.1.x | `EndpointCoverageTest`, `AccessAnnotationIT` |
| E2 | A tenant admin creates a custom role that escalates beyond the catalogue, or edits a built-in role | 1 | Permissions only from the code catalogue; built-in roles immutable; unknown keys rejected | V8.2.x | `RoleAdminIT` |
| E3 | Last tenant admin removed (tenant locked out) | 1 | Guard on revoke and on deactivation, under a lock on the `tenant_admin` role | V8.x | `LastAdminIT` (two admins revoke each other concurrently) |
| E4 | A newly privileged user acts without MFA | 1 | Sessions revoked when a grant makes a user privileged without MFA; next sign-in requires enrolment | V6.x | `PrivilegeMfaIT` |
| D1 | Huge tree or picker query exhausts the database | 1, 2 | Rate limit, result cap, indexed queries, tree cached | V2.4 | `OrgPerformanceIT` |

## Residual risks
- Unit renames are not queryable "as of D"; the record must snapshot names (Phase 2 obligation, recorded in ADR-0026).
- No maker-checker on role or clearance changes in 1a (step-up and audit only); revisit with the workflow governance work.
