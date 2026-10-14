# E01 Walking skeleton — threat model (STRIDE)

## Data flows
1. Browser → Vite dev server / web static server → backend `GET /api/v1/health`
2. Backend → PostgreSQL, SeaweedFS (S3), ONLYOFFICE (internal network), clamd (TCP), SMTP
3. Developer machine → Compose stack, which uses generated local credentials
4. CI → build and test (no deployment in E01)

## STRIDE

| # | Threat | Flow | Mitigation | ASVS 5.0 | Test |
|---|---|---|---|---|---|
| S1 | An attacker uses the unauthenticated endpoint to reach other routes | 1 | Spring Security deny-by-default; only `/api/v1/health` is permitted | V8.2.1 (function-level access) | `SecurityDefaultsTest`: an unknown route returns 401/404 problem, never 200 |
| T1 | Tampering with the committed OpenAPI contract or the generated client | 4 | Drift check: the regenerated spec must equal the committed one; the client must compile | V15.1 (secure build) | `OpenApiContractTest` |
| R1 | — (no state changes in this epic) | — | — | — | — |
| I1 | The health endpoint leaks versions, hosts or errors (reconnaissance) | 1 | Fixed response schema with names and status only; exception text is never serialized | V13.4.x (unintended info disclosure) | `HealthEndpointTest` asserts the exact JSON field set and that no stack or host string appears |
| I2 | Secrets committed in compose files or config | 3 | `.env` generated locally and git-ignored; only `.env.example` without values is committed; gitleaks (E04) | V13.3.1 (secrets management) | `.gitignore` check + gitleaks (E04) |
| I3 | Error responses leak stack traces | 1 | RFC 9457 handler with generic `detail`; `server.error.include-*=never` | V16.5.x (error handling) | `ProblemDetailsTest` |
| D1 | Health endpoint used to amplify load on downstream services | 1→2 | 5 s result cache; 2 s timeout per check; checks run in parallel; rate limit from Phase 1a | V2.4.1 (anti-automation) | `HealthServiceTest` (cache: N calls in 5 s produce one probe) |
| D2 | A stuck downstream service exhausts request threads | 2 | Socket and connect timeouts on every probe; virtual threads | V16.x resilience | `HealthServiceTest` (unresponsive socket → DOWN within the timeout) |
| E1 | ONLYOFFICE (an AGPL service that parses documents) gets outbound network access | 2 | Attached only to an `internal: true` Compose network | V15.2 (dependency isolation) | Manual check in the demo: `docker exec onlyoffice curl https://example.com` fails. Automated in E05 via a NetworkPolicy test |
| E2 | The app identity can change or delete bucket object-lock settings | 2 | Separate `admin` identity used only by `storage-init` (ADR-0005) | V8.x least privilege | `StorageBootstrapIT`: the app identity cannot `PutObjectLockConfiguration` |

## Residual risks and follow-ups
- ClamAV freshclam needs outbound internet access to `database.clamav.net` in dev. For on-prem we need a private mirror (E05 / Phase 7).
- `/v3/api-docs` must stay off in deployed environments. E05 checks this with a ZAP baseline rule and a Helm values default.
