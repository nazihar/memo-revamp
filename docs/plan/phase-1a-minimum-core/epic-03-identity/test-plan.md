# Test plan — E03 Identity, sessions and step-up

## Scope and risks
New: tenant resolution, local accounts, sessions, MFA, step-up, rate limits, invitations, password reset, mail outbox, user administration, sign-in screens. Highest risks: authentication bypass, enumeration, session misuse across tenants, lockout DoS, secret exposure. See [design.md](design.md), [threat-model.md](threat-model.md).

## Tests per slice
| Slice | Layer | Test (behaviour) | CI stage |
|---|---|---|---|
| T1 | Integration | `TenantResolutionIT` (host, unknown host, single-tenant, cross-tenant session), `CaddyAskEndpointIT` | PR |
| T2 | Unit / property | `PasswordPolicyTest` (length, NFC, email/name, breached), `BreachedPasswordsTest` (Bloom filter: known common password rejected, false-positive rate bound), `PasswordEncodingTest` (Argon2id default, PBKDF2 option, upgrade on login) | PR |
| T3 | Integration | `LoginFlowIT` (states, uniform failures, timing equalisation), `SessionManagementIT` (fixation, idle/absolute, list/revoke, deactivated user), `CsrfIT`, `SessionPrincipalTest` (JSON round trip) | PR |
| T4 | Integration / concurrency | `RateLimiterIT` (two app contexts share limits; `Retry-After`), `LockoutIT` (throttle, lock, expiry, admin unlock) | PR |
| T5 | Unit / integration | `TotpTest` (RFC 6238 vectors, window, replay), `RecoveryCodesProperties` (jqwik: unique, alphabet, length), `MfaFlowIT` (enrol, confirm, verify, recovery once, encrypted at rest, policy all/admins, admin reset) | PR |
| T6 | Integration | `StepUpIT` (fresh vs stale, Problem Details, retry after step-up) | PR |
| T7 | Integration | `InvitationIT`, `PasswordResetIT` (enumeration-safe, expiry, single use, sessions revoked), `MailOutboxIT` (Mailpit Testcontainer: rendered, sent, retried), `MailTemplatesTest` (no sensitive subjects; localised) | PR |
| T8 | API / authorization | `UserAdminApiIT` (list keyset, invite idempotent, lifecycle guards, If-Match conflicts), `IdentityAuthorizationMatrixTest` generated from `identity-authz.yaml` (every allow/deny cell) | PR |
| all | Audit | `IdentityAuditIT`: every state-changing endpoint emits its event (`assertAudited`) with no secrets | PR |
| all | Cross-tenant | `IdentityCrossTenantIT`: users, MFA, tokens, mail outbox of tenant A invisible and immutable from B; tokens of A unusable on B's host | PR |
| T9 | Component | sign-in steps, enrolment (QR, recovery codes ack), step-up dialog + retry, route guard, admin users list | PR |
| T10 | E2E | `auth.spec.ts`: sign-in with TOTP; invite → accept via Mailpit → enrol → sign in; sessions list/revoke; step-up on admin action; password reset; lockout message | PR (smoke), nightly (cross-browser) |
| T10 | Security | ZAP authenticated active scan (weekly); `security.spec.ts` updated (no tokens in storage, cookie flags) | weekly / PR |
| T10 | Performance | Gatling `LoginSimulation` (login + session reads), budgets in design §9 | nightly |

## Authorization matrix tests
`identity-authz.yaml`: endpoint × {anonymous, password-only, user, user (stale step-up), admin, admin (stale step-up), deactivated, other tenant} → allow / 401 / 403. Every cell is a generated test.

## State-machine tests
`identity_user.status`: every allowed transition (design §1) and every forbidden one (INVITED → LOCKED, DEACTIVATED → LOCKED, self-deactivation, last-admin deactivation) → `UserLifecycleIT`.

## Security tests
STRIDE → tests per threat-model.md. ASVS rows updated: V6 (authentication), V7 (sessions), V2.4 (anti-automation), V8 (admin checks), V11 (secret encryption).

## Accessibility and i18n
axe on every auth screen (light/dark); keyboard-only sign-in including the code field; Arabic for all strings; one-time-code field uses `autocomplete="one-time-code"` and `inputmode="numeric"`.

## Exploratory charters
1. Try to sign in to tenant B with tenant A's session or credentials (45 min).
2. Try to enumerate accounts through login, reset and invitation timing and messages (30 min).
3. Abuse the reset and invitation links: reuse, expiry, other host, referer (30 min).

## Exit checklist
- [ ] All listed tests implemented and green in their stage
- [ ] No new quarantined tests
- [ ] ASVS checklist updated
