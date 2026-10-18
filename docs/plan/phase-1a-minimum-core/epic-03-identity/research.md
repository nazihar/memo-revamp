# E03 Identity, sessions and step-up — research

All sources were accessed on **2026-10-18**.

## R1 — Versions, licenses, maintenance

| Component | Current | Decision |
|---|---|---|
| Spring Security | 7.1.1 (Boot 4.1.1-managed); 7.2.0-M2 | Use the managed 7.1.1 |
| Spring Session JDBC | 4.1.1 (Boot-managed); 4.2.0-M1 | Use the managed 4.1.1 |
| Bucket4j (`bucket4j_jdk17-core`, `bucket4j_jdk17-postgresql`) | 8.21.0 (2026-10-02), Apache-2.0, active | **Adopt** (locked in AGENTS §2, "verify": verified, see R11) |
| Bouncy Castle `bcprov-jdk18on` | 1.86 (2026-09), Bouncy Castle licence (MIT-style, on our allow-list) | **Adopt** for Argon2id (Spring's `Argon2PasswordEncoder` needs it) |
| ZXing `core` | 3.5.4 (2025-11), Apache-2.0, low but steady activity | **Adopt** to render the TOTP enrolment QR code as SVG on the server (no third-party QR service, no client library) |
| TOTP | `java-otp` 1.0.0 (2026-08, MIT, single maintainer) vs our own RFC 6238 implementation (~60 lines on JDK HMAC) | **Own implementation** with the RFC 6238 test vectors: tiny, security-critical, and avoids a single-maintainer dependency on the auth path |

Sources: Maven Central metadata; `spring-boot-dependencies-4.1.1.pom`; https://github.com/bucket4j/bucket4j; https://www.rfc-editor.org/rfc/rfc6238

## R8 — Spring Security 7 for an SPA: login, MFA, CSRF, sessions

- **MFA model (Spring Security 7.1):** every successful mechanism adds a `FactorGrantedAuthority` (`FACTOR_PASSWORD`, `FACTOR_OTT`, `FACTOR_WEBAUTHN`, …) carrying `issuedAt`. Authorization rules require factors through `AuthorizationManagerFactories.multiFactor().requireFactor(...)` (globally as a bean, or per route), optionally `.when(...)` conditional and `.validDuration(...)` for freshness. TOTP is not built in; a custom provider adds its own factor authority.
- **Built-in flows are redirect-based** (form login, OTT pages). For an SPA we expose JSON endpoints that authenticate a factor and update the session's `Authentication`, and rely on the same factor authorities for authorization. A missing factor yields a Problem Details response the SPA turns into the next step.
- **CSRF for SPAs:** `csrf { spa() }`: cookie `XSRF-TOKEN` readable by the SPA, header `X-XSRF-TOKEN`, BREACH-safe handling built in, token rotated after login and logout (the SPA refetches it).
- **Spring Session JDBC:** custom table name via `SessionRepositoryCustomizer` (`setTableName`), attributes table = name + `_ATTRIBUTES`; cleanup is a Spring `@Scheduled` cron (we disable it and run cleanup as a db-scheduler platform task, ADR-0018); attributes are Java-serialised by default. `PRINCIPAL_NAME` column + index supports "list my sessions".
- **Serialisation decision:** instead of serialising Spring Security's `SecurityContext` (Java serialisation, or Jackson 2 security modules on our Jackson 3 stack), the session stores **one small JSON attribute of our own** (tenant, user, factors with `issuedAt`, authentication time), and a custom `SecurityContextRepository` rebuilds the `Authentication` per request. No deserialisation of arbitrary classes.

Sources: https://docs.spring.io/spring-security/reference/servlet/authentication/mfa.html, https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html, https://docs.spring.io/spring-session/reference/configuration/jdbc.html

## R9 — Passwords, breached-password check, lockout

| Topic | Options | Decision |
|---|---|---|
| Hashing | Argon2id (OWASP first choice; needs Bouncy Castle), bcrypt (72-byte limit), PBKDF2-HMAC-SHA256 (FIPS-approved; 600k iterations per OWASP) | **Argon2id** by default (m=19 MiB, t=2, p=1, OWASP), behind Spring's `DelegatingPasswordEncoder` so the stored `{id}` prefix lets a deployment choose **PBKDF2** for FIPS-bound customers and re-hash on next login |
| Policy | NIST SP 800-63B rev. 4: length ≥ 8 (≥ 15 recommended when single-factor), no composition rules, no periodic expiry, check against breached lists, allow all printable Unicode and spaces, max ≥ 64 | Minimum **12** (tenant setting, never below 8), maximum 128, no composition rules, Unicode NFC normalisation, compared against the breached list and against the user's email and name |
| Breached list | Full HIBP Pwned Passwords (CC-BY-4.0, ~1 billion SHA-1 hashes, tens of GB); a curated top-N list (SecLists `10-million-password-list`, MIT) | **Bundled Bloom filter of the top 1 million common passwords** (~1.8 MB at 0.1 % false positives) built at release time; **optional** offline HIBP range files mounted by the customer (k-anonymity prefix layout) for the full check |
| Lockout | Hard lockout after N failures (enables denial of service against known users); throttling (NIST: ≤ 100 consecutive failures per account) | **Throttling per account** (progressive delay after 5 failures: 1, 2, 4 … up to 15 minutes, reset on success) **plus a temporary lock** after 20 consecutive failures (unlocked by time or an admin), **plus per-IP rate limits** (R11). Failure responses are identical for unknown users, wrong passwords and locked accounts |

Sources: https://pages.nist.gov/800-63-4/sp800-63b.html, https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html, https://haveibeenpwned.com/Passwords, https://github.com/danielmiessler/SecLists

## R10 — Tenant resolution and account model
Decided by the owner (Phase 1a overview Q2, Q3): one account per tenant; tenant from the host name (`<slug>.<base domain>`), single-tenant mode for dedicated and on-prem installs. Details:
- The host comes from `Host` (Caddy/nginx forward it unchanged; `X-Forwarded-Host` is ignored unless the request comes from a configured trusted proxy).
- Slug → tenant id through platform scope (ADR-0017), cached in-process.
- **TLS for per-tenant hosts** on our VM: Caddy **on-demand TLS** with an `ask` endpoint on the backend's management port (not proxied), which answers 200 only for existing tenant hosts. Avoids DNS-provider credentials (wildcard certificates need DNS-01).
- Industry: SaaS products with per-tenant subdomains (Slack workspaces, Atlassian sites) use the host to pick the tenant before login; board portals typically issue one URL per customer. Our approach matches both.

Source: https://caddyserver.com/docs/automatic-https#on-demand-tls

## R11 — Rate limiting without Redis
- Bucket4j 8.21 PostgreSQL extension: `PostgreSQLSelectForUpdateBasedProxyManager` (row lock) and `PostgreSQLadvisoryLockBasedProxyManager`; table with `id BIGINT`, `state BYTEA`, optional `expires_at`.
- Cost: one short transaction per check (lock, read, write). Acceptable for the endpoints we limit (login, OTP, reset, invitation acceptance, later upload and search), not for every request.
- **Keys** are hashed to a `BIGINT` (first 8 bytes of SHA-256 of `<limit>:<subject>`), so the table holds no IP addresses or emails. The table is a platform table (allow-listed in the RLS meta-test).
- Client IP: the servlet `remoteAddr`, replaced by the forwarded address only when the request comes from a trusted proxy (`server.forward-headers-strategy=native` with Tomcat's `internalProxies`).

Source: https://github.com/bucket4j/bucket4j (PostgreSQL integration docs)

## Industry practice
- **Board portals** (OnBoard, Diligent, Nasdaq Boardvantage) advertise SSO, MFA and session timeouts; directors complain about frequent re-logins. We keep idle timeouts as a tenant setting and use step-up for sensitive actions instead of short sessions everywhere.
- **Microsoft Entra / Google Workspace** patterns we adopt: MFA enrolment forced at first sign-in when required by policy; recovery codes shown once; sign-in notifications for new sessions.
