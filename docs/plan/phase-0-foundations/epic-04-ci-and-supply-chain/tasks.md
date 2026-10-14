# E04 CI pipeline and supply chain — tasks

| # | Task | Size | Depends |
|---|---|---|---|
| T1 | Gradle dependency locking + CycloneDX SBOM (backend, shared) + web SBOM | S | — |
| T2 | Licence check over the SBOMs + `licenses.md` report | S | T1 |
| T3 | SAST: SpotBugs + FindSecBugs; Semgrep custom rules; gitleaks config; pre-commit (lefthook) | M | — |
| T4 | Dockerfiles (backend distroless, web nginx-unprivileged) + Trivy image/config scans | M | — |
| T5 | Workflows: `ci.yml` (all PR gates incl. e2e smoke on CI stack), `nightly.yml`, `release.yml` (GHCR + cosign + attestations); actionlint; ADR index check | L | T1–T4 |
| T6 | Renovate config | S | T5 |
| T7 | `ci/README.md`: run each gate locally; self-hosted / air-gapped notes | S | T5 |

## Acceptance criteria
- **T1:** `./gradlew :backend:cyclonedxBom` and `pnpm --filter web sbom` write CycloneDX JSON. Changing a dependency without `--write-locks` fails the build.
- **T2:**
  - an AGPL or GPL library in the SBOM fails the check;
  - the report lists every component with its licence;
  - exceptions require a reason.
- **T3:**
  - each custom Semgrep rule has a positive and a negative fixture (`semgrep --test`);
  - gitleaks finds a planted fake key in a test file and none in the repository;
  - `spotbugsMain` passes.
- **T4:**
  - both images build;
  - they run as non-root and work with `--read-only` plus tmpfs;
  - the web image serves the CSP headers and proxies `/api`;
  - Trivy image scan shows no critical or high findings.
- **T5:** actionlint is clean. Every command the workflows run has been run locally (CI itself runs once pushed).
