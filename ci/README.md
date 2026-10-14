# CI gates — what runs where, and how to run each locally

The workflows (`.github/workflows/`) only orchestrate. Every gate is a command you can run on a developer machine. Stages are defined in TESTING_STRATEGY §9, and the decisions are in ADR-0011 to ADR-0014.

| Gate | Stage | Run locally |
|---|---|---|
| Format, lint, secrets on staged files | pre-commit | automatic (lefthook, installed by `pnpm install`) |
| Backend build, unit/property/module/architecture tests, persistence/RLS meta, cross-tenant + negative tests, API tests, OpenAPI + jOOQ drift, ktlint, detekt | PR | `./gradlew build` (needs Docker for Testcontainers) |
| SpotBugs + FindSecBugs | PR | `./gradlew :backend:spotbugsMain` |
| Web lint, typecheck, unit tests, client + i18n drift, build | PR | `pnpm --filter web lint typecheck test build`, `pnpm --filter web generate:api && pnpm --filter web i18n && git diff --exit-code` |
| E2E smoke (Playwright + axe) on the real stack | PR | `pnpm dev` (or `ci/e2e-stack.sh start`) then `pnpm --filter web e2e` |
| SBOMs | PR | `./gradlew cyclonedxBom` → `build/reports/cyclonedx/bom.json`; `pnpm sbom` → `build/sbom/web.cdx.json` |
| Licence allow-list + report | PR | `node ci/license-check.mjs --self-test`; `pnpm license-check` (copy the backend SBOM to `build/sbom/backend.cdx.json` first) |
| Vulnerabilities (SBOMs, images), IaC/Dockerfile misconfiguration | PR | `docker run aquasec/trivy:0.75.0 sbom …` / `image …` / `config …` (see `ci.yml`) |
| Secrets | pre-commit, PR, full history on `master` | `docker run -v "$PWD:/repo" zricethezav/gitleaks:v8.30.1 git /repo --config /repo/.gitleaks.toml` |
| SAST (custom rules with tests, registry packs) | PR | `docker run -v "$PWD:/src" -w /src semgrep/semgrep:1.180.0 semgrep --test /src/.semgrep` and `semgrep scan …` |
| Images: build, hardened smoke, Trivy | PR | build `bootJar` and `web/dist`, `docker build -f backend/Dockerfile …`, `ci/image-smoke.sh <backend> <web>` |
| ADR index, workflow lint | PR | `node ci/check-adr-index.mjs`; `docker run -v "$PWD:/repo" -w /repo rhysd/actionlint:1.7.12` |
| Cross-browser e2e, pseudo-locale, visual, Lighthouse, daily re-scan | nightly | see `nightly.yml` and `web/e2e/README.md` |
| Signed images + SBOM/provenance attestations, licence report | release (tag `v*`) | `release.yml` (needs the cosign key, see below) |

## Owner setup (one-time)
1. **Push and branch protection.** Require the `ci` checks on `master`.
2. **Signing key** (ADR-0012):
   - run `cosign generate-key-pair`;
   - store `COSIGN_PRIVATE_KEY` and `COSIGN_PASSWORD` as secrets of the `release` environment (with required reviewers);
   - commit `cosign.pub` as `deploy/cosign.pub`.
3. **Renovate** (ADR-0014): install the Renovate GitHub App on the repository.

## Self-hosted and air-gapped runners
- **Runners:** replace `runs-on: ubuntu-24.04` with `[self-hosted, linux]`. Runners need Docker, JDK ≥ 17 (Gradle provisions JDK 25), Node 24 and pnpm 10.
- **Trivy:** mirror the vulnerability DB and set `TRIVY_DB_REPOSITORY` / `TRIVY_JAVA_DB_REPOSITORY` to the mirror. Pass `--skip-db-update` if the DB is pre-seeded.
- **Semgrep:** registry packs (`p/…`) need internet access. Offline, run only `.semgrep/rules.yml` plus vendored copies of the packs.
- **Dependencies:** point Gradle, pnpm and Docker at internal mirrors (Nexus/Artifactory). Lockfiles keep resolution identical.
- **Verifying a release offline:** `cosign verify --key cosign.pub --insecure-ignore-tlog <image@digest>`.
