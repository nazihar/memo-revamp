---
status: accepted
date: 2026-10-18
deciders: product owner (Phase 1a Q5), agent
---

# 0024 — TOTP multi-factor authentication, recovery codes and step-up

## Context and problem statement
Phase 1a requires TOTP MFA, recovery codes and a step-up service ("fresh MFA within N minutes") for sensitive actions later (approve, sign, vote, security settings). The owner decided MFA is required by default and may be relaxed by a tenant admin only for non-admin users (Q5). Research: `epic-03-identity/research.md §R1, §R8`.

## Decision drivers
- ASVS L3 for authentication; phishing resistance comes with passkeys in 1b.
- Reuse Spring Security 7's factor model for authorization.
- No third-party services (QR codes rendered on the server).

## Considered options
1. **TOTP (RFC 6238) as a custom Spring Security factor; step-up through factor freshness**
2. Email one-time codes as the second factor (weaker; depends on mail)
3. A TOTP library (`java-otp`) instead of our own implementation

## Decision outcome
Chosen option: **1**, with our own RFC 6238 implementation.
- **TOTP:** HMAC-SHA1, 6 digits, 30-second step, ±1 step tolerance; secret 160 bits from `SecureRandom`, stored **encrypted** (AES-GCM with a key from the key provider abstraction: software key in 1a, KMS/HSM in 1b); last used time step stored, so a code cannot be replayed.
- **Enrolment:** server returns an `otpauth://` URI and an SVG QR code (ZXing); the user confirms with a valid code before MFA becomes active; then 10 **recovery codes** (10 characters, grouped) are shown once and stored as Argon2id hashes, each usable once.
- **Factor authority:** a successful TOTP or recovery-code check adds `FACTOR_TOTP` (custom `FactorGrantedAuthority`) with `issuedAt`.
- **Policy:** a global `AuthorizationManagerFactory` requires `FACTOR_PASSWORD` + `FACTOR_TOTP` for every API call except the auth endpoints, **when** MFA is required for the user (tenant setting `security.mfa.required` = `all` | `admins`; admins always). A user who must enrol and has not yet done so can call only the enrolment endpoints.
- **Step-up:** `StepUp.require(window)` checks that `FACTOR_TOTP.issuedAt` is within the window (default 5 min, tenant setting). Otherwise it throws `StepUpRequiredException` → 401 Problem Details `type: …/step-up-required` with the window; the SPA shows the code prompt and retries. `POST /api/v1/auth/step-up` re-verifies a code and refreshes the factor.
- **Admin actions:** tenant admins can reset a user's MFA (audited, user notified by email); a reset forces re-enrolment at next sign-in.

### Consequences
- Good: standard authenticator apps; replay-safe; one factor model for login, step-up and later SSO/passkeys.
- Bad: TOTP is phishable; passkeys (1b) are the stronger option.

### Confirmation
- `TotpTest` (RFC 6238 test vectors, window, replay), `MfaFlowIT` (enrol, verify, recovery code single use, admin reset), `StepUpIT` (fresh vs stale factor, Problem Details), property test on recovery-code generation (uniqueness, alphabet).
