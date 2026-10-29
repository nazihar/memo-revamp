# E03 Identity, sessions and step-up — threat model (STRIDE)

## Data flows
1. Browser → edge (Caddy/nginx) → backend: login, MFA, step-up, password reset, invitation (host decides the tenant).
2. Backend ↔ PostgreSQL: users, MFA secrets (encrypted), tokens (hashed), sessions (platform table), rate-limit buckets (platform table).
3. Backend → SMTP relay: invitation, reset and alert emails.
4. Caddy → backend management port: on-demand TLS `ask`.

| # | Threat | Flow | Mitigation | ASVS 5.0 | Test |
|---|---|---|---|---|---|
| S1 | Credential stuffing / brute force | 1 | Per-IP and per-account rate limits; progressive throttling; temporary lock after 20 failures; breached-password check | V6.3.1, V6.1.x (anti-automation), V2.4 | `LockoutIT`, `LoginRateLimitIT` |
| S2 | Account enumeration via login, reset or invitation responses and timing | 1 | Uniform 401 for all login failures; reset always 202; dummy Argon2 verification for unknown users to equalise timing | V6.3.8 | `LoginFlowIT` (unknown vs wrong password identical), `PasswordResetIT` |
| S3 | Session fixation / hijacking | 1 | Session id rotated on login and each factor; `HttpOnly`, `Secure`, `SameSite=Lax`, host-only cookie; idle + absolute timeouts | V7.2.x, V7.3.x | `SessionManagementIT` |
| S4 | Session from tenant A used on tenant B's host | 1 | Principal's tenant must equal the host's tenant, else session invalidated | V8.4.1 | `TenantResolutionIT` |
| S5 | TOTP replay or guessing | 1, 2 | Last used step stored; ±1 step window; OTP rate limit 5/min per user | V6.6.x | `TotpTest`, `MfaFlowIT` |
| T1 | CSRF on state-changing endpoints | 1 | `csrf { spa() }` with header token; `SameSite=Lax` | V3.5.x | `CsrfIT` |
| I1 | MFA secrets, reset tokens or recovery codes readable from the database | 2 | Secrets AES-GCM encrypted (key provider); tokens and codes stored only as hashes | V6.4.x, V11.x | `MfaFlowIT` (stored value ≠ secret), `ActionTokenIT` |
| I2 | Sensitive data in sessions / platform tables readable across tenants by the app role | 2 | Session holds ids and factor timestamps only; bucket keys are hashes | V14.2.x | RLS meta-test allow-list review; `SessionPrincipalTest` |
| I3 | Secrets in logs, audit or email subjects | 1, 2, 3 | Audit redaction; no passwords/codes/tokens logged; generic email subjects | V16.2.5 | `AuditRecordIT` redaction, `MailTemplatesTest` |
| I4 | Reset/invitation token leakage via URL referer or logs | 1, 3 | Token in path of a single-use, short-lived link; `Referrer-Policy: no-referrer`; tokens not logged (correlation-id only) | V7.x, V6.4.x | `ActionTokenIT` (single use, expiry) |
| R1 | User denies actions (password change, MFA reset, admin actions) | 1 | Audit events with actor, client and correlation id | V16.3.x | `IdentityAuditIT` |
| E1 | Non-admin reaches admin endpoints, or admin acts without fresh MFA | 1 | Deny-by-default; tenant-admin check in service layer; step-up on admin mutations | V8.2.x | authorization matrix tests |
| E2 | Admin locks out the tenant (deactivates self / last admin) | 1 | Guard: cannot deactivate self or the last active tenant admin | V8.x | `UserLifecycleIT` |
| D1 | Attacker locks known users out (lockout DoS) | 1 | Temporary lock with expiry, throttling instead of permanent lock, admin unlock | V6.3.x | `LockoutIT` |
| D2 | On-demand TLS abused to request certificates for arbitrary hosts | 4 | `ask` endpoint answers 200 only for existing tenant hosts; management port not public | V13.x | `CaddyAskEndpointIT` |

## Residual risks
- TOTP is phishable; passkeys arrive in 1b.
- Bloom-filter breached list covers common passwords only unless the customer mounts the full HIBP range files.
