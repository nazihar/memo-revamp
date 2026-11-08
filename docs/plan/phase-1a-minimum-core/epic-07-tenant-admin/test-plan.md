# Test plan — E07 Tenant lifecycle and administration

## Scope and risks
New: operator accounts and console on a separate host, tenant creation and suspension, first-admin provisioning, tenant admin console (security settings, branding, language and terms, audit viewer and export). Risks: operators reaching tenant data, session confusion between hosts, suspended tenants still working, weak settings accepted, unverifiable exports, injected branding or terms. See [design.md](design.md), [threat-model.md](threat-model.md).

## Tests per slice
| Slice | Layer | Test (behaviour) | CI stage |
|---|---|---|---|
| T1 | Integration | `OperatorAuthIT` (password + forced enrolment + TOTP, lockout, step-up, audit in platform chain), `OperatorEnrolmentIT` (command output, single-use link, expiry), `OperatorPathIT` (host routing both ways, cookies and principals not interchangeable), RLS meta-test for platform-only tables | PR |
| T2 | Integration | `PlatformTenantsIT` (create, rename, slug rules incl. reserved, both chains), `TenantProvisioningIT` (invite, refuse with active admin, re-invite), `SuspendedTenantIT` (every handler mapping 403, sessions deleted, task paused then resumed), `PlatformScopeIT` extended (no reads, writes refused on every tenant table), ArchUnit (tenant transactions in `platform`) | PR |
| T3 | Integration / unit | `SecuritySettingsIT` (ranges, step-up, audit, lockout keys honoured), `TerminologyIT` (validation, fallback per locale, version, audit), `MailTemplatesTest` (term placeholder) | PR |
| T4 | Integration / unit | `BrandingIT` (contrast refusal with ratio, logo types, public endpoints without session, ETag), `ContrastTest` (WCAG reference pairs) | PR |
| T5 | Integration | `AuditQueryApiIT` (filters, keyset, isolation), `AuditExportIT` (independent verifier, tamper, filtered not continuous, rate limit, `audit.exported`) | PR |
| T2–T5 | API / authorisation | matrix files `e07-platform.yaml` and `e07-tenant-admin.yaml`, coverage meta-test, OpenAPI drift | PR |
| T6, T7 | Component | operator sign-in and enrolment, tenants list and forms, suspend dialog; admin overview cards by permission, security form ranges, branding contrast feedback, `FileUpload` states, terms table, audit filters and export, suspended page; `useTerms` with Arabic gender | PR |
| T8 | E2E / a11y | `platform.spec.ts` (exit-criterion journey), `suspension.spec.ts`, `admin-console.spec.ts`; axe on every new screen (light/dark, LTR/RTL) | PR (Chromium), nightly (all browsers) |
| T5 | Performance | `AuditPerformanceIT` (query and export budgets, design §9) | nightly |

## Manual
- Keyboard and screen-reader pass over the admin console and the operator console (phase exit item).
- Moderated walkthrough of tenant administration with the owner at phase close.

## Exit checklist
- [ ] All listed tests green in their stage
- [ ] ASVS rows (V6, V7, V8, V16) updated
- [ ] `demo.md` written; demo of the operator journey to the owner
