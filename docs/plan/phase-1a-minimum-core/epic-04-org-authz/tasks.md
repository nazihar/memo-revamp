# E04 Org directory and authorisation — tasks

| # | Task | Size | Depends |
|---|---|---|---|
| T1 | **Policy kernel:** `common.authz` (catalogue, `Policy`, targets, annotations), `authz` module with roles, grants, clearance tables, bootstrap task (built-in roles, levels, grants from `tenant_admin`, clearances), cached evaluation, access interceptor (deny by default), `/me/access`; E03 admin endpoints and MFA "admins" moved onto the policy | L | — |
| T2 | **Authorisation matrix:** YAML format, generated tests, `EndpointCoverageTest`, E03 backfill (`e03-identity.yaml`), test subjects (roles, scoped admin, other tenant, stale step-up) | M | T1 |
| T3 | **Org units and legal entities:** tables, tree service (create, rename, move with ancestor rewrite, close), tree cache + `OrgChanged`, scope checks, `OrgTree` for the policy, API, audit | L | T1 |
| T4 | **Positions, assignments, reporting lines:** tables, effective-dated services with overlap and cycle guards, "as of D" queries, deactivation ends assignments, API, audit, history endpoint (audit read API for org objects) | L | T3 |
| T5 | **Roles, grants, classification and clearance API:** custom roles CRUD, grants (scoped), last-admin guard, privileged-grant MFA rule, levels CRUD and reorder, clearance, step-up, `Policy.condition` with SQL property test | M | T1, T3 |
| T6 | **People picker:** `/directory/search` (users, positions, rate limit, normalisation), indexes, performance at V3 | S | T4 |
| T7 | **Demo tenant and volumes:** Demo Bank org tree, positions, reporting lines, demo users per role; synthetic V3 org volume; `EXPLAIN` checks | M | T4, T5 |
| T8 | **Web — org:** org tree editor (tree view, detail, dialogs), position detail with holder history, people picker component, history tab, navigation by `/me/access` | L | T3–T6 |
| T9 | **Web — access:** user detail (positions, roles, clearance), roles list and form, classification levels, step-up on authorisation changes | M | T5, T8 |
| T10 | **E2E and docs:** Playwright roles (`orgAdmin`, `branchAdmin`, `auditor`), org journey (create unit → position → assign → history), scoped admin denied outside scope, role grant journey, visual baselines, Arabic strings, docs and STATUS | M | T8, T9 |

T3 and T5 can run in parallel after T1; T6 after T4; T8 can start on T3's API.

## Acceptance criteria (selection; full list in test-plan.md)
- **T1:** Given a handler without an access annotation, any request to it is 403 and the meta-test fails. Given an existing tenant with a `tenant_admin = true` user, after start-up that user holds a tenant-wide `tenant_admin` grant and every user has the lowest clearance. Given a tenant whose MFA policy is `admins`, a user granted `org_admin` must use MFA at the next sign-in.
- **T2:** Given the matrix files, every allowed cell has a passing positive test and every denied cell a passing negative test; removing an endpoint's entry fails the build.
- **T3:**
  - Given a unit, when it is moved under its own descendant, then 409 `CYCLE` and nothing changes.
  - Given a move, then every descendant's `ancestors` equals its `parent_id` chain.
  - Given a branch admin scoped to region R, when they create a unit under R's child, then 201; under another region, 403; moving R itself, 403 `SCOPE_ROOT`.
- **T4:**
  - Given a position held by A from 2026-01-01, when B is assigned from 2026-06-01 without ending A, then 409 `OVERLAP`.
  - Given A held the position until 2026-05-31 and B from 2026-06-01, then "holder on 2026-05-15" is A and "on 2026-06-15" is B.
  - Given two concurrent assignments of the same seat, exactly one succeeds.
  - Given a deactivated user, their open assignment ends on the deactivation date, audited with actor `System`.
- **T5:** Given two tenant admins revoking each other's `tenant_admin` grant at once, exactly one succeeds (409 `LAST_ADMIN` for the other). Given a built-in role, edit or delete gives 409 `BUILT_IN`. Given seeded rows, `Policy.condition` selects exactly the rows `Policy.check` allows.
- **T6:** Given "ahm", the picker returns users and positions whose name or title has a word starting with "ahm", at most 20, never from another tenant; the 61st search in a minute gets 429.
- **T8–T10:** Playwright: org admin creates a department, adds a position, assigns a user from the picker, ends the assignment, and sees both holders in the history; the branch admin cannot see edit actions outside their region and the API refuses them.
