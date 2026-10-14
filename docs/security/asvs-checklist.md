# OWASP ASVS 5.0 checklist

> Target: **Level 2** for all web and API work; **Level 3** for authentication, authorization, cryptography and audit (`AGENTS.md §3.4`). Each row links a requirement to its evidence: a test, a CI gate or a document. The checklist is updated per epic and reviewed at every phase exit (TESTING_STRATEGY §6). Requirement IDs refer to ASVS 5.0.0 (May 2025).
>
> Status values: `met` (evidence linked), `partial`, `planned (phase)`, `n/a (reason)`.

| Chapter | Requirement (summary) | Level | Status | Evidence |
|---|---|---|---|---|
| V2 Validation & business logic | Anti-automation on public endpoints | L2 | partial | Health endpoint result cache (E01, `HealthServiceTest`); rate limiting with Bucket4j planned (Phase 1a) |
| V3 Web frontend security | Strict CSP, no inline scripts | L2 | met (SPA + API) | API CSP `default-src 'none'` (`SecurityDefaultsTest`); SPA CSP from `web/security-headers.json`, no `unsafe-inline` (e2e `security.spec.ts`: headers, no inline script/style, CSP-violation fixture on every test) |
| V3 Web frontend security | No sensitive data in browser storage | L2 | met | Allow-listed preference keys only (e2e `security.spec.ts`, `AppShell.test.tsx`) |
| V3 Web frontend security | No third-party resources | L2 | met | e2e `the app loads nothing from third-party origins`; CSP `default-src 'self'` |
| V4 API & web service | Consistent error format, no internal details | L2 | met | RFC 9457 handler (`ProblemDetailsTest`) |
| V6 Authentication | MFA, lockout, session revocation, step-up | L3 | planned (1a) | — |
| V7 Session management | Server-side sessions, cookie flags, CSRF | L2 | partial | CSRF enabled; stateless until 1a (`SecurityDefaultsTest`) |
| V8 Authorization | Deny-by-default for every request | L3 | met (skeleton) | `SecurityConfig` + `SecurityDefaultsTest`; authorization matrix tests per epic |
| V8 Authorization | Least privilege for service identities | L3 | partial | DB app role DML-only, not owner, no BYPASSRLS (`SystemInfoRepositoryIT`); S3 app identity without bucket admin (`ObjectStorageIT`) |
| V8 Authorization | Multi-tenant data isolation enforced below the application | L3 | met (reference table) | RLS ENABLE+FORCE + policy convention (ADR-0006); `RlsMetaTest` (catalog), `CrossTenantIsolationIT` (read/list/update/delete/insert/join/view/function), `RlsNegativeIT` |
| V8 Authorization | Tenant context cannot leak across pooled connections; fails closed | L3 | met | `TenantTransactionsIT`, session-SET source scan in `ArchitectureRulesTest` |
| V11 Cryptography | Per-tenant envelope encryption | L3 | planned (1a/1b) | — |
| V12 Secure communication | TLS everywhere | L2 | planned (E05) | — |
| V13 Configuration | Secrets not in code, config or logs | L2 | met (Phase 0 scope) | Generated `.env` (git-ignored); random test secrets; `StorageProperties.toString` redacts (`ObjectStorageIT`); gitleaks pre-commit + CI (full history); Semgrep `memo-no-secrets-in-logs` |
| V13 Configuration | No unintended information disclosure | L2 | met | Health exposes names and status only (`HealthEndpointIT`); `server.error.include-*=never` |
| V14 Data protection | Cache-Control on sensitive responses | L2 | met (API) | `Cache-Control: no-store` (`SecurityDefaultsTest`) |
| V15 Secure coding & architecture | Dependency inventory, SBOM, pinned versions | L2 | met | `docs/dependencies.md`; CycloneDX SBOMs per PR/release; Gradle `gradle.lockfile` + pnpm lockfile; actions by SHA, images by digest |
| V15 Secure coding & architecture | Known-vulnerable components blocked | L2 | met | Trivy on SBOMs + images in PR, daily re-scan; security overrides for Tomcat/Jackson (ADR-0013) |
| V15 Secure coding & architecture | Licence compliance | L2 | met | `ci/license-check.mjs` (self-tested) over both SBOMs; AGPL/GPL only as separate services |
| V15 Secure coding & architecture | Signed, verifiable artefacts | L2 | partial (needs owner key) | cosign key-based signing + SBOM/provenance attestations in `release.yml` (ADR-0012) |
| V15 Secure coding & architecture | Hardened containers | L2 | met | non-root, read-only root FS, no capabilities (`ci/image-smoke.sh`); Trivy config clean |
| V15 Secure coding & architecture | Isolation of risky components | L2 | partial | ONLYOFFICE on an internal-only network (E01 manual check); NetworkPolicy (E05) |
| V16 Security logging & error handling | Security events logged without sensitive data | L3 | planned (1a) | Audit module |
