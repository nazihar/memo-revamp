---
status: accepted
date: 2026-10-29
deciders: product owner (delegated), agent
---

# 0027 — Authorisation: permission catalogue, roles, policy service, SQL pushdown

## Context and problem statement
AGENTS §3.4 requires deny-by-default, server-side authorisation for every request, combining roles with attributes (unit, classification, later forum membership and recusal). E03 used a single `tenant_admin` flag. E04 builds the general model that every later epic uses. Research: `epic-04-org-authz/research.md §R13, §R15`.

## Decision drivers
- List endpoints must filter in SQL, never after fetching.
- Rules must be explainable and testable from a matrix.
- Nothing extra to deploy or operate on-prem.
- Tenant admins configure roles; the meaning of a permission is fixed by code.

## Considered options
1. **In-code policy service** over a code-defined permission catalogue and tenant-defined roles
2. jCasbin with an RBAC-with-domains model
3. Cedar (`cedar-java`) with partial evaluation

## Decision outcome
Chosen option: **1**.
- **Permission catalogue** in code (`com.memo.common.authz.Permission`): stable keys such as `org.manage`, `identity.users.manage`, `authz.roles.manage`, `audit.read`, `directory.read`. Each permission declares whether it can be scoped to a unit subtree and whether it is **privileged** (holders count as administrators for the MFA policy, ADR-0024).
- **Roles** are tenant data: a name and a set of permissions. Four built-in roles are seeded per tenant (`tenant_admin`, `org_admin`, `auditor`, `user`); their permission sets come from code and cannot be edited or deleted, custom roles can. Every active user implicitly holds `user`.
- **Grants** give a role to a user, tenant-wide or scoped to a unit subtree (for scopable permissions only). At least one active user must keep a tenant-wide `tenant_admin` grant.
- **Classification levels** are an ordered, tenant-defined list (rank); each user has one clearance level (default: the lowest). Access to a classified object needs clearance rank ≥ the object's rank, in addition to the permission.
- **Policy service** (`com.memo.common.authz.Policy`, implemented in the `authz` module):
  - `check(permission, target)` / `require(...)` for single objects (target: tenant, unit, or classified object in a unit);
  - `condition(permission, unitField, rankField)` returns a jOOQ `Condition` for list queries (`true`, `false`, or `unit_id = any(:units) and rank <= :clearance`);
  - also evaluates account state and tenant state (suspended tenants deny everything, E07);
  - effective grants and the org tree are cached per tenant (ADR-0020) and evicted on change, so a check needs no query.
- **Deny by default:** every controller method declares its access (`@Public`, `@SignedIn`, or `@RequiresPermission`), and an interceptor rejects handlers with none. A meta-test fails the build for any endpoint missing from the authorisation matrix files.
- The `common.authz` API lets `identity`, `org` and later modules depend on the policy without depending on the `authz` module; `authz` depends on `org` and `identity`, never the reverse.

### Consequences
- Good: SQL pushdown and explainable decisions in one place; no new runtime dependency.
- Good: the catalogue changes only with code, so permissions never mean different things in different tenants.
- Bad: we own a security-critical component; mitigated by the generated matrix tests and the coverage meta-test.
- Neutral: a policy engine can replace the implementation behind `Policy` later.

### Confirmation
- Generated matrix tests (`authz/*.yaml`), `EndpointCoverageTest`, `PolicyTest` (unit and property tests over scope and clearance), `PolicyConditionIT` (SQL filter equals in-memory decision on seeded data).
