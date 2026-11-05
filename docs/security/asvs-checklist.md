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
| V8 Authorization | Deny-by-default for every request | L3 | met | Every handler declares its access; undeclared handlers are refused (`AccessInterceptor`, `AccessInterceptorTest`); `EndpointCoverageTest` fails the build for an endpoint missing from the authorisation matrix; generated matrix tests per epic (`AuthorizationMatrixTest`, ~400 cells) |
| V8 Authorization | Function and data level access checked server-side through one policy | L3 | met | `Policy` (ADR-0027): permission catalogue in code, tenant-defined roles, unit-scoped grants, clearance vs classification; `PolicyIT`, `OrgUnitsIT` (scope), `AccessAdminIT` (SQL filter equals in-memory decision) |
| V8 Authorization | Changes to access are protected and audited | L3 | met | Step-up on every role, grant, level and clearance change (matrix rule `tenantAdminStepUp`); last-admin guard under a row lock (`AccessAdminIT` concurrency); every change audited (`authz.*` events) |
| V8 Authorization | Access changes take effect at once on every node | L3 | met | Grants and the org tree cached with LISTEN/NOTIFY eviction at commit (ADR-0020); `PolicyIT` (grant visible at once), sign-out when a user becomes privileged without MFA (`AccessAdminIT`) |
| V8 Authorization | Least privilege for service identities | L3 | partial | DB app role DML-only, not owner, no BYPASSRLS (`SystemInfoRepositoryIT`); S3 app identity without bucket admin (`ObjectStorageIT`) |
| V8 Authorization | Multi-tenant data isolation enforced below the application | L3 | met (reference table) | RLS ENABLE+FORCE + policy convention (ADR-0006); `RlsMetaTest` (catalog), `CrossTenantIsolationIT` (read/list/update/delete/insert/join/view/function), `RlsNegativeIT` |
| V8 Authorization | Tenant context cannot leak across pooled connections; fails closed | L3 | met | `TenantTransactionsIT`, session-SET source scan in `ArchitectureRulesTest`; every transaction gets its tenant from a `ScopedValue` scope (ADR-0017, `TenantContextPropagationIT`) |
| V8 Authorization | Tenant context in background work (jobs, async listeners, executors); fails closed | L3 | met | Tenant travels in task data and events; `TenantTaskIT`, `TenantEventsIT`, `TenantContextPropagationIT` (executor), `EventPayloadRulesTest` (ids only in platform tables) |
| V8 Authorization | Platform (cross-tenant) operations cannot read tenant data | L3 | met (platform jobs) | Platform scope accepted only by `platform_*` policies (`PlatformScopeIT`, `RlsMetaTest`); operator access in E07 |
| V5 File handling | Uploads limited in size, type detected from content, extension must agree, unknown or active types refused | L2 | met | ADR-0029; `ContentInspectorTest`, `UploadIT` (size while streaming and declared), `HostileFileCorpusIT` (HTML as PNG, PDF as DOCX, polyglot, empty) |
| V5 File handling | Malware scan before storage; fail closed when the scanner is unavailable | L2 | met | clamd `INSTREAM` in the upload pass (`ClamdClientIT`, `UploadIT`), `ScannerDownIT` (503, nothing stored), corpus (EICAR, planted signature in zip) |
| V5 File handling | Archive and document bombs, macros, encrypted content refused within limits | L2 | met | Zip reader (entries, ratio, total), clamd limits and heuristics, PDFBox memory cap and page limit; `HostileFileCorpusIT` (nested zip, 200 MiB bomb, DOCM, VBA in DOCX, ODF macros, encrypted PDF/zip, 100,000-page PDF); nightly Jazzer (`UploadPathFuzzTest`) |
| V5 File handling | Stored under random names; served with `Content-Disposition`, `nosniff`, sandboxed CSP, never from storage URLs | L2 | met | Random 128-bit object keys (`UploadIT`); downloads only through the backend (`DownloadIT` headers); names sanitised (`FileNamesProperties`) |
| V11 Cryptography | Per-tenant envelope encryption of files with a vetted library, authenticated and range-readable | L3 | met (software KEK; KMS/HSM in 1b) | ADR-0028 (Tink streaming AEAD, keyset per tenant wrapped by the KEK); `FileCipherProperties` (ranges, flip, truncate, reorder, moved ciphertext), `KeysetStorageIT` (created once, wrapped, other tenant cannot unwrap) |
| V14 Data protection | No plaintext of uploads at rest anywhere (proxies, multipart, scratch files) | L2 | met | Multipart disabled (`UploadApiIT` 415); ciphertext spool; nginx request and response buffering off on `/api/v1/files`; PDFBox memory-only; runbook `files-and-malware-scanning.md` |
| V16 Security logging & error handling | Document opens and file rejections audited; tampering detected and audited | L3 | met | `file.opened` once per session and version (`DownloadIT`), `file.upload.rejected` with reason and signature (`UploadIT`), `file.integrity.failed` (`DownloadIT`) |
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
| V16 Security logging & error handling | Structured logs with correlation id, no client-controlled text | L2 | met | ECS JSON (`StructuredLoggingIT`); correlation id re-rendered from parsed bits (`CorrelationIdFilterTest`, FindSecBugs clean) |
| V16 Security logging & error handling | Audit record written in the same transaction as the change | L3 | met | `beforeCommit` flush (ADR-0019); `AuditSameTransactionIT` (rollback leaves neither; failed flush aborts the commit) |
| V16 Security logging & error handling | Audit log protected against modification | L3 | met | App role INSERT/SELECT only + immutability trigger (`AuditRecordIT`); hash chain + verifier (`AuditChainVerifierProperties`, `AuditTamperIT`); anchors in the object-locked bucket (`AuditAnchorIT`) |
| V16 Security logging & error handling | Security events logged without sensitive data | L3 | partial | Redaction of secret-looking keys (`AuditRecordIT`); identity events arrive in E03 |
| V13 Configuration | Management endpoints not exposed publicly | L2 | met | Actuator on port 8081, not proxied, only health probes anonymous (`ProbesIT`) |
| V13 Configuration | Least privilege for runtime credentials | L2 | met | Migration job holds migrator role; app holds app role only (`MigrateModeIT`, `ci/onprem-smoke.sh`) |
| V3 Web frontend security | Cross-origin isolation headers | L2 | met | COOP/COEP/CORP in `security-headers.json`; ZAP baseline 0 FAIL / 0 WARN |
