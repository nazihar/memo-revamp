# E07 Tenant lifecycle and administration — threat model (STRIDE)

## Data flows
1. Operator browser → backend on the operator host: sign-in, tenants, platform audit.
2. Backend platform code → PostgreSQL in platform scope (registry, operators, platform chain).
3. `TenantProvisioning` → PostgreSQL in the new tenant's scope (first-admin invitation) → mail outbox.
4. Tenant browser → backend on a tenant host: admin console (security, branding, terms, audit query and export), public branding and terms.
5. Owner shell → `operator-invite` command → database, stdout.

| # | Threat | Flow | Mitigation | ASVS 5.0 | Test |
|---|---|---|---|---|---|
| S1 | A tenant user's session is used on the operator host, or an operator's on a tenant host | 1, 4 | Host-only cookies with different names; separate principal attribute and authority; each chain accepts only its own; host routing 404s the other side's paths | V7.x, V8.4.1 | `OperatorPathIT` |
| S2 | Operator credentials guessed or phished | 1 | Argon2id, breached-password check, lockout, TOTP always, rate limits per IP and account; SameSite=Strict cookie | V6.x | `OperatorAuthIT` |
| S3 | The enrolment link is intercepted | 5 | Printed only to the command's stdout (SSH session), 24 h, single use, stored hashed; token in the fragment | V6.x | `OperatorEnrolmentIT` |
| T1 | An operator alters tenant data | 2 | Platform scope: tenant tables' RLS rejects writes; only registry columns writable | V8.4.1 | `PlatformScopeIT` (writes refused on every tenant table) |
| T2 | Audit export altered after download to hide events | 4 | Chain hashes and anchors; manifest with SHA-256 of the events file; independent verifier | V16.x | `AuditExportIT` (altered line detected) |
| T3 | Branding colour or logo used to inject script or break contrast | 4 | Colours `#rrggbb` only, applied via CSSOM custom properties; contrast validated server-side; logo PNG/JPEG/WebP only, scanned (E05), served with `nosniff` and `Content-Disposition: inline` with fixed type | V5.x, V3.x | `BrandingIT` |
| T4 | Terms used to inject markup or ICU syntax into messages | 4 | Character allow-list (no `{ } < > #`); values passed as ICU arguments (escaped by react-intl and `MailTexts` HTML escaping) | V5.x | `TerminologyIT`, web unit test |
| R1 | An operator denies suspending a tenant or inviting an admin | 1, 3 | Platform chain entries with operator id; tenant chain entries for tenant-visible effects | V16.3.x | `PlatformTenantsIT` (both chains) |
| R2 | An admin weakens security settings unnoticed | 4 | Step-up; `tenant.setting.changed` audit with before/after | V16.3.x | `SecuritySettingsIT` |
| I1 | An operator reads tenant data | 2 | Platform scope sees zero rows of tenant tables (RLS); API returns registry fields only; ArchUnit: only `TenantProvisioning` opens tenant transactions in `platform` | V8.4.1 | `PlatformScopeIT`, ArchUnit |
| I2 | First-admin provisioning used to plant an account in a live tenant | 3 | Refused when an active tenant admin exists; step-up; audited in the tenant's chain where its admins see it | V8.2.x | `TenantProvisioningIT` |
| I3 | Public branding or terms endpoints reveal whether a tenant exists | 4 | Unknown host is 404 before these endpoints; they reveal only what the login page shows | V8.x | `TenantResolutionIT` (existing) |
| I4 | Audit query exposes client addresses to non-auditors | 4 | `audit.read` (privileged) required; object history views keep omitting them | V8.x | matrix |
| I5 | Export over a wide range exfiltrates in one go | 4 | Step-up, 1-year range cap, 5 per hour, export itself audited | V16.x | `AuditExportIT` |
| D1 | Export or audit query overloads the database | 4 | Keyset pagination, streamed export with fetch size, rate limits | — | `AuditPerformanceIT` |
| D2 | Suspension leaves sessions or jobs running | 2 | Sessions deleted in the same transaction; resolver 403 on every path; one-off tasks paused | V7.x | `SuspendedTenantIT` |
| E1 | A tenant admin escalates to operator | 4 | Separate account store and host; no API turns a user into an operator | V8.x | `OperatorPathIT` |
| E2 | A non-admin changes security settings, branding or terms | 4 | `tenant.settings.manage` (privileged) | V8.x | matrix |
| E3 | Lax MFA policy applied to admins | 4 | `admins` policy never relaxes privileged users (existing `AuthSettings.mfaRequired`) | V6.x | `SecuritySettingsIT` |

## Residual risks
- Operators and tenants share a process and a database role (ADR-0031): a code-execution flaw reaches both. A dedicated `memo_operator` role is a Phase 7 follow-up.
- One operator, no recovery codes: losing the authenticator means re-running the bootstrap command over SSH.
- Exports are unsigned until 1b's key provider; integrity rests on the chain and the anchors.
