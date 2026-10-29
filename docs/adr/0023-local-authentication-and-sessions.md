---
status: accepted
date: 2026-10-18
deciders: product owner (delegated), agent
---

# 0023 — Local authentication and server-side sessions for the SPA

## Context and problem statement
AGENTS §2 locks server-side sessions in PostgreSQL (Spring Session JDBC) with `HttpOnly`, `Secure`, `SameSite` cookies, CSRF protection and no tokens in browser storage. Phase 1a needs local accounts with a safe password policy, a breached-password check, lockout and session management. Research: `epic-03-identity/research.md §R8, §R9`.

## Decision drivers
- ASVS 5.0 L3 for authentication (V6) and L2 for sessions (V7).
- JSON API for the SPA, no redirects; no tokens in JavaScript.
- No Java deserialisation of untrusted or complex graphs.
- On-prem friendly (no external services).

## Considered options
1. **Spring Security JSON login endpoints + Spring Session JDBC + our own minimal session principal (JSON)**
2. Spring Security form login + default `SecurityContext` serialisation
3. Stateless JWT in an `HttpOnly` cookie

## Decision outcome
Chosen option: **1**.
- **Endpoints:** `POST /api/v1/auth/login` (email, password) → 200 with the next step (`MFA_REQUIRED`, `MFA_ENROLMENT_REQUIRED` or `DONE`); `POST /api/v1/auth/logout`; `GET /api/v1/auth/session` (who am I, which factors, what is required next); `GET /api/v1/auth/csrf`.
- **Session store:** Spring Session JDBC on `identity_http_session` / `identity_http_session_attributes` (platform tables: no `tenant_id` column, allow-listed in the RLS meta-test; principal name `<tenantId>:<userId>`). Built-in cron cleanup disabled; a db-scheduler platform task deletes expired sessions (ADR-0018).
- **Session content:** one JSON attribute `memo.principal` = `{tenantId, userId, factors: [{type, issuedAt}], authenticatedAt}`; a custom `SecurityContextRepository` rebuilds the `Authentication` (with `FactorGrantedAuthority` per factor) on every request. Nothing else security-relevant is stored in the session.
- **Cookie:** `MEMO_SESSION`, `HttpOnly`, `Secure`, `SameSite=Lax`, host-only (no `Domain`, so it never leaks to another tenant's subdomain), path `/`.
- **Timeouts:** idle (default 30 min) and absolute (default 12 h) as tenant settings; the absolute limit is checked against `authenticatedAt` on each request.
- **Session fixation:** the session id changes on login and on each new factor.
- **CSRF:** `csrf { spa() }` (cookie `XSRF-TOKEN`, header `X-XSRF-TOKEN`); the web client sends the header on every mutating request.
- **Passwords:** `DelegatingPasswordEncoder`, default **Argon2id** (Bouncy Castle), PBKDF2 selectable for FIPS-bound installs (`memo.security.password-encoding`); rehash on login when the algorithm or parameters change. Policy: length 12–128 (tenant setting, never below 8), Unicode NFC, no composition rules, rejected if in the breached list or equal to the email or name.
- **Breached list:** bundled Bloom filter of the top 1 million common passwords (0.1 % false positives), plus optional offline HIBP range files (`memo.security.breached-passwords-dir`).
- **Lockout:** per-account progressive throttling after 5 failures (1 min doubling to 15 min), temporary lock after 20 consecutive failures; plus per-IP and per-account rate limits (ADR-0025). Responses never reveal whether the account exists.
- **Session list / revoke:** "my sessions" lists sessions by principal name (device from user agent, IP, last access); revoking deletes the session row; an admin can revoke all sessions of a user in their tenant.

### Consequences
- Good: no tokens in the browser; sessions revocable instantly; minimal, typed session payload.
- Bad: we own the JSON endpoints and the security-context repository (covered by tests); sessions are a platform table readable across tenants by the app role (ids only, no secrets).
- Follow-ups: SSO (1b) adds factors from IdPs into the same principal.

### Confirmation
- `LoginFlowIT`, `SessionManagementIT`, `PasswordPolicyTest`, `BreachedPasswordsTest`, `LockoutIT`, `CsrfIT`; RLS meta-test allow-list entries; ZAP authenticated scan (weekly).
