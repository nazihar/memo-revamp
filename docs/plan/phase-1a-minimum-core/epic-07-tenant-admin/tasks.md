# E07 Tenant lifecycle and administration — tasks

| # | Task | Size | Depends |
|---|---|---|---|
| T1 | **Operator accounts and sign-in:** `platform_operator` and token tables (platform-only RLS, meta-test shape), `OperatorAccounts`, `OperatorSignIn` (password, forced TOTP enrolment, lockout, step-up), operator session principal and cookie by host, operator filter chain, host routing in the resolution filter (`memo.platform.host`, reserved slugs), `operator-invite` command mode, demo operator seeding | L | — |
| T2 | **Tenant lifecycle:** platform-scope insert/update policies on the registry, `TenantLifecycle` (create, rename, suspend, resume), resolver with status and cross-node invalidation, `tenant-suspended` problem, session deletion, job pause, `platform` module with `PlatformTenantsController` and `TenantProvisioning`, platform audit query | L | T1 |
| T3 | **Tenant settings APIs:** `tenant.settings.manage`, security settings (incl. new lockout keys read by `LoginAttempts`), language and time-zone defaults (`locale.default` rename), terminology (`tenancy_term`, `/terms`, `/admin/terms`, `MailTexts` terms) | M | — |
| T4 | **Branding:** `branding` module, `tenant.logo` purpose, colours with contrast validation, public `/branding` and `/branding/logo`, admin API | M | T3 |
| T5 | **Audit query and export:** `AuditQuery`, `AuditExport` (ZIP stream, manifest, VERIFY.md), controllers, rate limit, `audit.exported` | M | — |
| T6 | **Web, operator console:** host detection, platform shell, sign-in and enrolment pages, tenants list, new tenant form, tenant page (suspend/resume/rename/invite), platform audit | L | T1, T2 |
| T7 | **Web, tenant admin console:** `/admin` overview, security, branding (with shared `FileUpload`), language and terms, audit viewer and export, suspended page; `useTerms` and term arguments on org screens; branding applied at start-up | L | T3–T5 |
| T8 | **E2E, docs, phase close prep:** exit-criterion journey (operator creates tenant → first admin accepts, enrols TOTP, signs in → lists and revokes a session → step-up prompt), suspension journey, axe on new screens; `SuspendedTenantIT` over all endpoints; matrix files; runbook `platform-operators.md`; `demo.md`; ASVS V8 rows; deploy (`MEMO_PLATFORM_HOST`, Caddy ask, Helm ingress); STATUS | M | T6, T7 |

T1, T3 and T5 can start in parallel; T2 after T1; T4 after T3.

## Acceptance criteria (selection)
- **T1:** Given an operator with a password but no TOTP, when they sign in, then they must enrol before any platform endpoint answers. Given a tenant session cookie, when it calls `/api/v1/platform/tenants` on the operator host, then 401; given an operator session, a tenant host answers 404 for platform paths and 401 for tenant paths. Given 20 wrong passwords, the operator is locked.
- **T2:** Given an operator creates tenant `acme`, then the registry row exists, the first admin has an `INVITED` account with tenant admin, an invitation mail is queued, and both chains record it. Given `acme` has an active admin, `first-admin` is 409. Given `acme` is suspended, every `/api/**` request on `acme`'s host is 403 `tenant-suspended`, its sessions are gone, and a mail task for it is rescheduled without a failed attempt; given it is resumed, the mail is sent. Given platform scope, reading or writing any tenant table yields nothing or is refused.
- **T3:** Given idle minutes 2 (below range), then 400 naming the range. Given `security.lockout.lock.after` 5, then the 5th wrong password locks. Given an override "department"/"departments" for `en`, `/terms?locale=en` returns it and `ar` returns Arabic defaults.
- **T4:** Given `#ffff00`, then 400 with the measured ratio; given `#1f4e8c`, saved. Given an SVG logo, rejected; given a PNG, `/branding/logo` serves it without a session.
- **T5:** Given an unfiltered export, an independent verifier recomputes every hash and the manifest's last hash and SHA-256 match; given one altered line, verification fails at that line; given an action filter, `continuous` is false. Given 6 exports in an hour, the 6th is 429.
- **T6/T7/T8:** axe finds nothing on the new screens in light/dark and LTR/RTL; the operator journey passes in Chromium in PR and in all browsers nightly.
