---
status: accepted
date: 2026-10-14
deciders: product owner (delegated), agent
---

# 0013 — Supply-chain scanning stack (SBOM, vulnerabilities, licences, secrets, SAST)

## Context and problem statement
AGENTS §3.1 and §4, together with TESTING_STRATEGY §6, require:
- an SBOM (CycloneDX);
- dependency and image scanning;
- a licence allow-list with a human-readable report;
- secret scanning;
- SAST (Semgrep with our own rules, SpotBugs + FindSecBugs, eslint security);
- all versions pinned.

The tools must also work with mirrored databases for air-gapped builds. Research: `epic-04-ci-and-supply-chain/research.md §R15`.

## Decision outcome

| Concern | Tool | Gate |
|---|---|---|
| SBOM | CycloneDX Gradle plugin (`:backend:cyclonedxBom`, `:shared`) + `@cyclonedx/cyclonedx-npm` (web) | produced on every PR; published per release |
| Dependency vulnerabilities | Trivy `sbom` on both SBOMs | PR fails on CRITICAL/HIGH with a fix available. Exceptions live in `.trivyignore` with an owner, a reason and an expiry (remediation SLAs from TESTING_STRATEGY §6). |
| Image vulnerabilities | Trivy `image` | same |
| IaC / Dockerfile / Helm | Trivy `config` | PR fails on HIGH/CRITICAL |
| Licences | `ci/license-check.mjs` over both SBOMs | Allow-list: Apache-2.0, MIT, BSD-2/3, ISC, EPL-2.0, MPL-2.0, LGPL (unmodified), plus a few permissive equivalents (0BSD, Unlicense, CC0-1.0, BlueOak-1.0.0, Python-2.0, Zlib, W3C, PostgreSQL). Unknown or other licences fail unless listed in `ci/license-exceptions.json` with a reason. Writes `licenses.md`. |
| Secrets | gitleaks (pre-commit + CI, full history on push to master) | zero findings |
| SAST | Semgrep CE (`.semgrep/` custom rules + `p/kotlin`, `p/typescript`, `p/react`), SpotBugs + FindSecBugs (`:backend:spotbugsMain`), eslint-plugin-security | no new high findings |
| Pinning | Gradle dependency locking (`gradle.lockfile`), pnpm lockfile, actions by SHA, images by digest | builds fail on unlocked changes |

Our Semgrep rules cover:
- no session-level `SET` or `set_config(..., false)`;
- no S3 presigned URLs (`S3Presigner`);
- no secret-looking values or tokens in log calls;
- no `dangerouslySetInnerHTML`;
- no Web Storage writes outside `src/lib/preferences.ts`;
- no `Instant.now()` outside `common.time`.

### Consequences
- Good: one tool (Trivy) for vulnerabilities and misconfiguration, and one licence mechanism for both ecosystems.
- Bad: the Trivy DB is downloaded on GitHub-hosted runners. Self-hosted or air-gapped runners use a mirrored DB (`TRIVY_DB_REPOSITORY`).

### Confirmation
The `security` and `images` jobs in `ci.yml`. The licence report is uploaded as an artefact.
