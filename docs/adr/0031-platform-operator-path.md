---
status: accepted
date: 2026-11-08
deciders: product owner (delegated), agent
---

# 0031 — Platform operator path: separate host, accounts and session; platform scope only

## Context and problem statement
Phase 1a needs a platform console to create, configure and suspend tenants and to invite each tenant's first admin (overview E07). Operators must never see tenant data, and that must hold in the database, not only in the UI (overview §3, Q6: separate admin host, separate operator accounts with mandatory MFA; the owner is the only operator in 1a). ADR-0006 left "a separate audited path" as a follow-up, and ADR-0017 introduced platform scope for jobs. This ADR extends both. Research: `phase-1a-minimum-core/epic-07-tenant-admin/research.md §R18, §R19`.

## Decision drivers
- Tenant data unreachable from operator code paths, enforced by PostgreSQL.
- No operator credential or session usable on a tenant host, and the reverse.
- One deployable; reuse identity's reviewed password, TOTP, session and rate-limit code.
- Works for multi-tenant installs; single-tenant installs can switch the console off.

## Considered options
1. A separate application (process, port, image) for operators.
2. **Same application; separate host, filter chain, cookie, account table; operator requests run only in platform scope.**
3. Operators as privileged users inside each tenant.

## Decision outcome
Chosen option: **2**, because it gives host, credential and database separation without a second deployable, and option 3 contradicts the requirement.

**Host and routing.**
- `memo.platform.host` (default `admin.<baseDomain>` in multi-tenant mode; empty = console off, the default in single-tenant mode). The slug `admin` and the other reserved names (`www`, `api`, `mail`, `static`) cannot be tenant slugs (check constraint and validation).
- `/api/v1/platform/**` is served only on the operator host; any other host gets 404. On the operator host, every other `/api/**` path gets 404 (no tenant is bound there). `TenantResolutionFilter` decides both.
- A separate Spring Security filter chain for `/api/v1/platform/**` (ordered before the tenant chain): its public endpoints are `csrf`, `session`, `login`; everything else needs a complete operator session (password + TOTP). The tenant chain never accepts an operator authentication (different authority), and the operator chain never accepts a tenant one.

**Accounts.** Table `identity_operator` (owned by the identity module; a platform table: no `tenant_id`; RLS visible only in platform scope): email, display name, Argon2id password hash, TOTP secret encrypted with `DataKeys` (associated data `identity.operator:<id>`), status `INVITED | ACTIVE | DISABLED`, failed attempts and lock. TOTP is always required; enrolment is part of the first sign-in. No recovery codes in 1a: a lost authenticator is reset with the bootstrap command.

**Bootstrap.** The backend image gets a command mode, `operator-invite <email> [display name]`, which creates the operator (or resets an existing one to `INVITED`, clearing password and TOTP), records `platform.operator.invited` in the platform chain, and prints a one-time enrolment link (24 hours) to its standard output only. Non-production installs seed a demo operator (`operator@demo.example`, demo password and TOTP secret from `.env`).

**Sessions.** Spring Session JDBC as for users (ADR-0023), but: cookie `MEMO_OPERATOR` (HttpOnly, Secure, SameSite=Strict, host-only), principal attribute `memo.operator`, principal index `platform:<operatorId>`, idle 15 minutes, absolute 8 hours, step-up window 5 minutes for suspend, resume and first-admin invitation. The cookie serializer picks the name by host, so the operator cookie is never set on, or read from, a tenant host.

**Database access.** Every operator read runs in platform scope (`PlatformTransactions`, `app.scope = platform`, no tenant id). Tenant tables' RLS policies return no rows and reject writes in that scope (ADR-0006, ADR-0017). Operators see the registry (id, slug, name, status, created and changed times) and nothing else. Changes to a tenant's registry row (rename, suspend, resume) are made by the tenancy module's `TenantLifecycle` in that tenant's own scope (policy `tenant_self`), with the tenant-chain audit entry in the same transaction; the platform module records the platform-chain entry in platform scope. No new platform-scope write policy is needed.

**First admin.** `TenantProvisioning` is the only class in the platform module allowed to open a tenant transaction (ArchUnit). It runs in the target tenant's context, refuses if the tenant already has an active tenant admin, creates or re-sends the invitation (E03 flow, role tenant admin), records `tenant.first_admin.invited` in **both** chains (the tenant's, with actor `PLATFORM_OPERATOR`, and the platform's), and returns nothing from the tenant.

**Suspension.** Status `SUSPENDED` makes the tenant host answer 403 `tenant-suspended` to every API request (resolution filter), deletes the tenant's sessions in the same transaction, and pauses its one-off jobs (rescheduled hourly without counting attempts); recurring fan-out already skips inactive tenants. Resume reverses the status; sessions stay gone.

**Audit.** Every operator action is recorded in the platform chain with actor `PLATFORM_OPERATOR`; tenant-visible effects (created, suspended, resumed, renamed, first admin invited) also in the tenant's chain, so the tenant can see what the platform did.

### Consequences
- Good: operator code cannot read tenant data even when wrong (RLS); credentials, cookies and sessions are separated by host; the tenant's own audit shows every platform action that touched it.
- Bad: operator and tenant code share a process, so a remote-code-execution flaw reaches both (same as option 1 sharing a database role). The platform scope is a transaction setting the application sets; a deliberate bypass in code would be caught by review and the existing source scan, not by PostgreSQL privileges.
- Follow-ups: a separate `memo_operator` database role entered with `SET LOCAL ROLE` (Phase 7, with ADR-0006's read-only ops role); operator recovery codes and a second operator (Phase 7); per-tenant platform settings beyond the registry (1b).

### Confirmation
- `PlatformScopeIT` (extended): platform scope reads zero rows and is refused writes on every tenant table, including those added in E05–E07.
- `OperatorPathIT`: platform endpoints 404 on a tenant host; tenant endpoints 404 on the operator host; a tenant session cookie gets 401 on the operator host and an operator session gets 401 on a tenant host.
- `OperatorAuthIT`: TOTP enrolment forced, lockout, step-up for suspend.
- `TenantProvisioningIT`: refuses when an active admin exists; both chains record the invitation.
- `SuspendedTenantIT`: every tenant endpoint (from the handler mappings) answers 403; sessions deleted; one-off tasks paused and resumed.
- ArchUnit: in the platform module only `TenantProvisioning` uses `TenantTransactions` or `TenantDsl`.

## Pros and cons of the options
### Separate application
- Good, because code and credentials are physically separate.
- Bad, because it doubles images, scans, deployment and auth code for one operator in 1a, and both would use the same database role anyway.

### Same application, separate host (chosen)
- Good, because host, cookie, account and database scope are all separated, and reviewed identity code is reused.
- Bad, because both paths share a process.

### Operators inside tenants
- Good, because nothing new is needed.
- Bad, because operators would be tenant users with tenant data access; contradicts Q6.

## More information
- https://www.postgresql.org/docs/18/ddl-rowsecurity.html (accessed 2026-11-08)
- https://docs.spring.io/spring-security/reference/servlet/configuration/java.html#_multiple_httpsecurity_instances (accessed 2026-11-08)
- https://developer.mozilla.org/en-US/docs/Web/HTTP/Cookies#define_where_cookies_are_sent (host-only cookies, accessed 2026-11-08)
