# E03 Identity, sessions and step-up — tasks

| # | Task | Size | Depends |
|---|---|---|---|
| T1 | **Tenant resolution:** `TenantResolutionFilter` (host → tenant via platform scope, cache, single-tenant mode), trusted forwarded headers, Caddy `ask` endpoint on the management port | M | — |
| T2 | **Accounts and passwords:** `identity_user` + migrations, `DelegatingPasswordEncoder` (Argon2id/PBKDF2), password policy, breached-password Bloom filter (build script + bundled file), demo users from env | M | T1 |
| T3 | **Login and sessions:** Spring Session JDBC (renamed tables, cleanup task), `MemoSecurityContextRepository` + session principal, JSON login/logout/session/csrf endpoints, timeouts, fixation protection, `ActorContextFilter` after security, security chain rewrite | L | T2 |
| T4 | **Rate limiting and lockout:** Bucket4j PostgreSQL, `RateLimiter`, limits on login/OTP/reset/invitation, throttling + temporary lock, cleanup task | M | T3 |
| T5 | **TOTP MFA:** RFC 6238 implementation, secret encryption (key provider, software key), enrolment with QR SVG, verify with replay protection, recovery codes, MFA policy (`AuthorizationManagerFactory` with tenant policy), admin MFA reset | L | T3 |
| T6 | **Step-up:** `StepUp` service, Problem Details, `/auth/step-up`, applied to admin mutations and `/me` sensitive actions | S | T5 |
| T7 | **Invitations, password reset and email:** action tokens, `notification` mail outbox + send task + templates, invitation accept flow, reset flow, sign-in alert | L | T3, T5 |
| T8 | **User administration API:** list (keyset), invite, deactivate/reactivate/unlock/MFA reset/revoke sessions with guards, Idempotency-Key, If-Match | M | T5, T6, T7 |
| T9 | **Web:** generated client + CSRF header + step-up interceptor; sign-in, enrolment, recovery, reset, invitation screens; route guard; account menu; `/me/security`; `/admin/users`; status page to `/status` | L | T3–T8 |
| T10 | **Test infra and ops:** Playwright per-role logins (TOTP from env), auth journeys e2e, authenticated ZAP context, Caddy on-demand TLS on the VM (Ansible), demo secrets in `gen-secrets.sh`/`dev.mjs`, docs | M | T9 |

## Acceptance criteria (selection; full list in test-plan.md)
- **T1:** Given host `acme.<base>`, requests run in tenant `acme`; given an unknown host, 404; given single-tenant mode, any host maps to the configured tenant.
- **T3:**
  - Given valid credentials for a user without MFA requirement, login returns `AUTHENTICATED` and rotates the session id.
  - Given a session older than the absolute timeout, the next request is 401 and the session is gone.
  - Given tenant A's session cookie on tenant B's host, the request is anonymous and the session is invalidated.
- **T4:** Given 20 failed logins, the account is LOCKED until `locked_until`; responses for locked, unknown and wrong-password cases are identical; the 21st request from one IP within a minute gets 429 with `Retry-After`.
- **T5:** Given an enrolled user, a valid code adds `FACTOR_TOTP`; reusing the same code fails; a recovery code works once.
- **T6:** Given `FACTOR_TOTP` older than the step-up window, an admin mutation returns 401 `step-up-required`; after `/auth/step-up` with a valid code, the retried request succeeds.
- **T7:** Given an invitation, the invitee sets a password and enrols MFA through the emailed link; the link cannot be used twice or after 7 days; a reset link expires after 30 minutes and revokes all sessions on use.
- **T8:** Given a tenant admin, they cannot deactivate themselves or the last active admin; a double-submitted invite with the same Idempotency-Key creates one user.
- **T9/T10:** Playwright: admin invites a user → user accepts via Mailpit link, enrols TOTP, signs in → lists and revokes a session → step-up dialog on an admin action.
