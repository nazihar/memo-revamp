# E04 Org directory and authorisation — log

## 2026-10-29
- E03 merged (PR #20). Plan written: research, ADR-0026 (org model) and ADR-0027 (authorisation model), design, threat model, tasks, test plan. Gate delegated (overview Q1).
- Findings: no new runtime dependency is needed. The units tree uses an adjacency list with a derived ancestor array (GIN), not `ltree` or a closure table, so no PostgreSQL extension is required on-prem. Effective dating covers assignments, reporting lines and unit/position existence; renames are audit-only history, so Phase 2 records must snapshot position and unit names.
- Authorisation is an in-code policy service over a code-defined permission catalogue (jCasbin and Cedar compared; neither translates rules into SQL filters, which list endpoints need). Deny by default is enforced twice: an access annotation on every handler at runtime, and the endpoint-coverage meta-test in the build.
- E03's `tenant_admin` flag becomes a role grant (expand → migrate → contract): the flag is still written for one release and dropped later.
- Screens reuse the E01 templates and tree view approved in the E01 UX review; the owner sees them at the epic demo.
- T1 done (policy kernel):
  - `common.authz` holds the catalogue, `BuiltInRole`, `Policy` and `Resource` (renamed from the design's `Target`, which clashes with Kotlin's `@Target`), the access annotations, and `AccessAdministration` (built-in grants and the locked last-admin check), so `identity` and `demo` use the policy without depending on `authz`.
  - Built-in roles keep their permissions in code (no `authz_role_permission` rows). A user without a clearance row has the lowest level.
  - No foreign keys across modules: the first codegen run failed because the `authz` migration ran before `identity`'s. Modules migrate in no guaranteed order (as E03 noted for `tenant_id`).
  - Bootstrap: a synchronous `TenantCreated` listener creates roles and levels in the creating transaction; a start-up runner brings older tenants up to date and turns `tenant_admin = true` into grants. `ON CONFLICT DO NOTHING` (with `UNIQUE NULLS NOT DISTINCT` on grants) keeps it safe on several nodes.
  - `identity_user.tenant_admin` is written in step with the grant (`LegacyTenantAdminFlag`) and read only by the bootstrap; `UserAccount` no longer has the field. `ROLE_TENANT_ADMIN` is gone: `/admin/users/**` needs `identity.users.manage` through `@RequiresPermission`. The session principal ignores E03's `tenantAdmin` property, so signed-in users stay signed in across the upgrade.
  - "MFA for admins" now means privileged (holding any privileged permission): `AuthSettings.mfaRequired` asks `Policy.isPrivileged`.
  - Tenant suspension is not evaluated yet (no way to suspend before E07); E07 adds it to the policy and to tenant resolution.
- T2 done (authorisation matrix): `backend/src/test/resources/authz/rules.yaml` defines the subjects and shared rules; one file per epic lists endpoints with a rule, overrides and optional sample body/headers. `AuthorizationMatrixTest` generates one test per cell (156 for E03 + `/me/access`), each with a fresh session; `EndpointCoverageTest` fails on an endpoint missing from the matrix, a matrix entry without an endpoint, or a handler without exactly one access declaration.
  - "Allowed" means not 403, not 5xx, and no 401 other than `invalid-credentials` (a wrong code is not a denial).
  - Two E03 endpoints validate body or headers before the step-up check (`PUT /me/password`, `POST /admin/users`), so their entries carry sample requests; a partial session gets 403 on enrolment for an already-enrolled account (E03 T5 rule, recorded as an override).
  - JUnit dynamic tests did not get the test actor; `TestActorExtension` now covers them.
